package com.tztvhelper.app.core.kb

import android.content.Context
import com.tztvhelper.app.core.adb.AdbConnectionOracle
import com.tztvhelper.app.core.ai.TutorialLookup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 知识库门面。所有读写都走这里，界面不直接碰 SQLite。
 *
 * 关键约束（对应产品要求）：
 * 1. 搜索结果只能以候选身份入库，不能覆盖已推荐方法。
 * 2. 每次成功/失败都留记录，历史只增不删。
 * 3. 匹配顺序：型号+版本 → 型号 → 品牌通用 → 没有。
 */
class KbRepository(
    private val context: Context,
    private val oracle: ConnectionOracle = AdbConnectionOracle
) : TutorialLookup {

    /** 搜索缓存等模块复用同一个数据库实例 */
    fun database(): KbDatabase = db

    private val db = KbDatabase(context)
    private val seedLock = Mutex()

    suspend fun ensureSeeded() = withContext(Dispatchers.IO) {
        seedLock.withLock { syncFromAssets() }
    }

    /**
     * 内置知识库同步：按 kbVersion 判断要不要跑。
     *
     * 只新增缺失的品牌/型号/方法，绝不 upsert 已存在的方法——
     * 否则一次升级就会把用户验证出来的推荐状态、成功次数和历史记录覆盖掉。
     */
    private fun syncFromAssets() {
        val meta = context.getSharedPreferences("kb_meta", Context.MODE_PRIVATE)
        val root = JSONObject(assetsText("kb/brands.json"))
        val version = root.optString("kbVersion", "0")
        val firstRun = db.methodCount() == 0
        if (!firstRun && meta.getString(KEY_KB_VERSION, null) == version) return

        val incomingBrands = ArrayList<Brand>()
        val incomingModels = ArrayList<TvModel>()
        root.getJSONArray("brands").let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val brand = Brand(o.getString("id"), o.getString("name"), o.optStringList("aliases"))
                incomingBrands += brand
                o.optJSONArray("models")?.let { ms ->
                    for (j in 0 until ms.length()) {
                        val m = ms.getJSONObject(j)
                        incomingModels += TvModel(
                            m.getString("id"), brand.id, m.getString("model"),
                            m.optString("displayName", m.getString("model")),
                            m.optStringList("androidVersions")
                        )
                    }
                }
            }
        }

        val incomingMethods = ArrayList<Method>()
        JSONObject(assetsText("kb/tutorials.json")).getJSONArray("tutorials").let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val steps = o.getJSONArray("steps").let { ss ->
                    List(ss.length()) { k ->
                        val s = ss.getJSONObject(k)
                        TutorialStep(
                            s.optInt("n", k + 1), s.getString("title"), s.getString("detail"),
                            s.optStringList("path"), s.optString("note")
                        )
                    }
                }
                val sources = o.optJSONArray("sources")?.let { ss ->
                    List(ss.length()) { k ->
                        val s = ss.getJSONObject(k)
                        SourceRef(
                            SourceType.valueOf(s.optString("type", "UNKNOWN")),
                            s.optString("url"), s.optString("title"),
                            s.optLong("collectedAt"), Credibility.valueOf(s.optString("credibility", "UNVERIFIED"))
                        )
                    }
                } ?: emptyList()

                incomingMethods += Method(
                    id = o.getString("id"),
                    brandId = o.getString("brandId"),
                    modelId = o.optString("modelId").takeIf { it.isNotBlank() && it != "null" },
                    title = o.getString("title"),
                    scope = o.optString("scope", "brand"),
                    osMin = o.optString("osMin").takeIf { it.isNotBlank() },
                    osMax = o.optString("osMax").takeIf { it.isNotBlank() },
                    steps = steps,
                    notes = o.optString("notes"),
                    status = MethodStatus.CANDIDATE,
                    confidence = Credibility.valueOf(o.optString("confidence", "UNVERIFIED")),
                    sources = sources,
                    updatedAt = o.optLong("collectedAt", 0L)
                )
            }
        }

        incomingBrands.forEach { db.upsertBrand(it) }
        KbMerge.newModels(db.modelIds(), incomingModels).forEach { db.upsertModel(it) }
        val now = System.currentTimeMillis()
        KbMerge.newMethods(db.methodIds(), incomingMethods).forEach { m ->
            db.upsertMethod(m)
            db.insertHistory(MethodHistory(m.id, "CREATED", "内置资料入库，状态：候选", now))
        }
        meta.edit().putString(KEY_KB_VERSION, version).apply()
    }

    private fun assetsText(path: String): String =
        context.assets.open(path).bufferedReader().use { it.readText() }

    fun brands(): List<Brand> = db.brands()
    fun models(brandId: String): List<TvModel> = db.models(brandId)
    fun method(id: String): Method? = db.method(id)

    /** 手输型号 → 库内型号 ID。匹配不唯一或无匹配就返回 null（界面显示"品牌通用"） */
    fun resolveModel(brandId: String, modelText: String?): TvModel? {
        if (modelText.isNullOrBlank()) return null
        val brand = db.brands().firstOrNull { it.id == brandId } ?: return null
        return ModelMatcher.match(db.models(brandId), modelText, brand.name)
    }

    override fun resolveModelId(brandId: String?, modelText: String?): String? {
        if (brandId.isNullOrBlank()) return null
        return resolveModel(brandId, modelText)?.id
    }

    /** 按 型号+版本 → 型号 → 品牌通用 的顺序找可用方法；REJECTED 不参与 */
    override fun find(brandId: String?, modelId: String?, osVersion: String?): MatchResult {
        if (brandId.isNullOrBlank()) return MatchResult.NotFound(null, modelId)
        val all = db.methodsFor(brandId, modelId)
        if (all.isEmpty()) return MatchResult.NotFound(brandId, modelId)

        val pick = MatchPicker.pick(all, modelId, osVersion)
            ?: return MatchResult.NotFound(brandId, modelId)
        return MatchResult.Hit(pick.method, pick.level, "匹配分 ${pick.score}")
    }

    /** 搜索结果/用户反馈进来的新方法：一律候选 */
    fun addCandidate(
        brandId: String,
        modelId: String?,
        title: String,
        steps: List<TutorialStep>,
        notes: String,
        confidence: Credibility,
        sources: List<SourceRef>
    ): Method {
        val now = System.currentTimeMillis()
        val method = MethodRules.newCandidate(
            Method(
                id = "m-${now}-${(1000..9999).random()}",
                brandId = brandId,
                modelId = modelId,
                title = title,
                scope = if (modelId == null) "brand" else "model",
                steps = steps,
                notes = notes,
                confidence = confidence,
                sources = sources,
                updatedAt = now
            )
        )
        db.upsertMethod(method)
        db.insertHistory(MethodHistory(method.id, "CREATED", "新方法入库，状态：候选", now))
        return method
    }

    /**
     * 记录一次验证。
     * - SUCCESS_APP 必须由 [ConnectionOracle] 证实「真连着 + 设备与方法品牌/型号一致」，
     *   否则整条拒绝（返回 Rejected，不写库）。
     * - SUCCESS_APP 写入的设备型号/系统版本一律用 Adb.info 读到的真实值，不用手填值。
     * - 成功次数只增不减；失败不清零成功记录。
     * - 失败原因留空时固定记为「未提供原因」，verifications 表里不写 null。
     */
    fun recordVerification(
        methodId: String,
        result: VerifyResult,
        osVersion: String?,
        deviceModel: String?,
        reason: String?
    ): RecordOutcome {
        val current = db.method(methodId) ?: return RecordOutcome.Rejected("知识库里没有这条方法。")
        val brand = db.brands().firstOrNull { it.id == current.brandId }
            ?: return RecordOutcome.Rejected("知识库里没有这个品牌。")
        val model = current.modelId?.let { db.model(it) }

        if (result == VerifyResult.SUCCESS_APP) {
            VerificationRules.successProblem(oracle, current, brand, model)?.let {
                return RecordOutcome.Rejected(it)
            }
        }
        val effective = VerificationRules.effectiveResult(result, oracle, current, brand, model)
            ?: return RecordOutcome.Rejected("当前条件不能记录「APP 实测成功」。")

        val now = System.currentTimeMillis()
        val updated = MethodRules.afterVerification(current, effective, now)

        // APP 实测：设备和系统以电视自己报告的为准，不采用用户手填
        val realModel = if (effective == VerifyResult.SUCCESS_APP) {
            oracle.deviceModel()?.takeIf { it.isNotBlank() }
        } else {
            deviceModel?.takeIf { it.isNotBlank() } ?: oracle.deviceModel()
        }
        val realOs = if (effective == VerifyResult.SUCCESS_APP) {
            oracle.deviceRelease()?.takeIf { it.isNotBlank() } ?: osVersion
        } else {
            osVersion?.takeIf { it.isNotBlank() }
        }

        db.upsertMethod(updated)
        db.insertVerification(
            Verification(
                methodId, effective, realOs, realModel,
                VerificationRules.normalizeReason(reason),
                now
            )
        )

        val change = when {
            effective == VerifyResult.FAILURE && current.status == MethodStatus.RECOMMENDED -> "DEMOTED"
            updated.status == MethodStatus.RECOMMENDED && current.status != MethodStatus.RECOMMENDED -> "PROMOTED"
            effective == VerifyResult.FAILURE -> "FAILED"
            else -> "VERIFIED"
        }
        val detail = VerificationRules.historyDetail(
            effective, realOs, realModel, reason, updated.successCount, updated.failCount
        )
        db.insertHistory(MethodHistory(methodId, change, detail, now))
        return RecordOutcome.Updated(updated, detail)
    }

    fun history(methodId: String): List<MethodHistory> = db.history(methodId)
    fun verifications(methodId: String): List<Verification> = db.verifications(methodId)

    private fun JSONObject.optStringList(key: String): List<String> {
        val arr = optJSONArray(key) ?: return emptyList()
        return List(arr.length()) { arr.getString(it) }
    }

    @Suppress("unused")
    private fun JSONArray.toStringList(): List<String> = List(length()) { getString(it) }

    private companion object {
        const val KEY_KB_VERSION = "kb_version"
    }
}

/** 记录验证的结果：要么真的写进去了，要么带着原因被拒绝（界面要如实显示） */
sealed interface RecordOutcome {
    data class Updated(val method: Method, val detail: String) : RecordOutcome
    data class Rejected(val reason: String) : RecordOutcome
}

