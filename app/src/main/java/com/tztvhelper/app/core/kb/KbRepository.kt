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
        seedLock.withLock {
            if (db.methodCount() == 0) seedFromAssets()
        }
    }

    private fun seedFromAssets() {
        val brandsJson = context.assets.open("kb/brands.json").bufferedReader().use { it.readText() }
        JSONObject(brandsJson).getJSONArray("brands").let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                db.upsertBrand(Brand(o.getString("id"), o.getString("name"), o.optStringList("aliases")))
                o.optJSONArray("models")?.let { ms ->
                    for (j in 0 until ms.length()) {
                        val m = ms.getJSONObject(j)
                        db.upsertModel(
                            TvModel(
                                m.getString("id"), o.getString("id"), m.getString("model"),
                                m.optString("displayName", m.getString("model")),
                                m.optStringList("androidVersions")
                            )
                        )
                    }
                }
            }
        }

        val tutJson = context.assets.open("kb/tutorials.json").bufferedReader().use { it.readText() }
        JSONObject(tutJson).getJSONArray("tutorials").let { arr ->
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

                val method = Method(
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
                db.upsertMethod(method)
                db.insertHistory(MethodHistory(method.id, "CREATED", "内置资料入库，状态：候选", method.updatedAt))
            }
        }
    }

    fun brands(): List<Brand> = db.brands()
    fun models(brandId: String): List<TvModel> = db.models(brandId)

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
     * - SUCCESS_APP 必须由 [ConnectionOracle] 证实真的连着，否则整条拒绝（返回 null，不写库）。
     * - 成功次数只增不减；失败不清零成功记录。
     * - 失败原因留空时固定记为「未提供原因」，不允许空白。
     */
    fun recordVerification(
        methodId: String,
        result: VerifyResult,
        osVersion: String?,
        deviceModel: String?,
        reason: String?
    ): Method? {
        val current = db.method(methodId) ?: return null
        val effective = VerificationRules.effectiveResult(result, oracle) ?: return null
        val now = System.currentTimeMillis()
        val updated = MethodRules.afterVerification(current, effective, now)

        db.upsertMethod(updated)
        db.insertVerification(
            Verification(
                methodId, effective, osVersion,
                deviceModel?.takeIf { it.isNotBlank() } ?: oracle.deviceModel(),
                reason?.let { VerificationRules.normalizeReason(it) },
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
            effective, osVersion, deviceModel, reason, updated.successCount, updated.failCount
        )
        db.insertHistory(MethodHistory(methodId, change, detail, now))
        return updated
    }

    fun history(methodId: String): List<MethodHistory> = db.history(methodId)
    fun verifications(methodId: String): List<Verification> = db.verifications(methodId)

    private fun JSONObject.optStringList(key: String): List<String> {
        val arr = optJSONArray(key) ?: return emptyList()
        return List(arr.length()) { arr.getString(it) }
    }

    @Suppress("unused")
    private fun JSONArray.toStringList(): List<String> = List(length()) { getString(it) }
}
