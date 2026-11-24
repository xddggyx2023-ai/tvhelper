package com.tztvhelper.app.core.search

import com.tztvhelper.app.core.kb.Credibility
import com.tztvhelper.app.core.kb.KbDatabase
import com.tztvhelper.app.core.kb.KbRepository
import com.tztvhelper.app.core.kb.SourceRef
import com.tztvhelper.app.core.kb.SourceType
import com.tztvhelper.app.core.kb.TutorialStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 一次搜索结果，连同"它属于哪台设备"一起保存。
 * 界面切品牌/型号后，旧结果不能拿来存到新品牌名下。
 */
data class SearchOutcome(
    val query: SearchQuery,
    val response: SearchResponse,
    val fromCache: Boolean,
    val cacheKey: String
) {
    val canSaveToKb: Boolean
        get() = response.error == null && SearchGuards.canWriteToKb(response.isMock)
}

sealed interface SaveResult {
    data class Saved(val methodId: String) : SaveResult
    data object MockBlocked : SaveResult
    data object NoSteps : SaveResult
    data object NoHit : SaveResult
    data object IdentityMismatch : SaveResult
    data object InvalidUrl : SaveResult

    /** 用户填了型号，但知识库里没有这个型号：不能存成品牌通用（否则会推给同品牌所有型号） */
    data object UnresolvedModel : SaveResult
    data class Failed(val reason: String) : SaveResult
}

/** 纯函数：保存前的全部校验，界面藏按钮不能替代它 */
object SearchSaveGuard {

    enum class Problem { RESPONSE_ERROR, MOCK, IDENTITY_MISMATCH, NO_HIT, INVALID_URL, NO_STEPS, UNRESOLVED_MODEL }

    /**
     * 型号作用域校验（纯函数）：
     * 用户填了型号却解析不到库内型号时，拒绝存成品牌通用。
     * 返回 null = 可以存（要么没填型号→品牌通用，要么型号已解析→型号级）。
     */
    fun modelScopeProblem(modelText: String?, resolvedModelId: String?): Problem? =
        if (!modelText.isNullOrBlank() && resolvedModelId.isNullOrBlank()) Problem.UNRESOLVED_MODEL else null

    fun check(
        outcome: SearchOutcome,
        brandId: String,
        model: String?,
        osVersion: String?,
        hitIndex: Int
    ): Problem? {
        val r = outcome.response
        if (r.error != null) return Problem.RESPONSE_ERROR
        if (r.isMock) return Problem.MOCK
        if (!SearchGuards.identityMatches(
                outcome.query.brandId, outcome.query.model, outcome.query.osVersion,
                brandId, model, osVersion
            )
        ) return Problem.IDENTITY_MISMATCH

        val hit = r.hits.getOrNull(hitIndex) ?: return Problem.NO_HIT
        if (!SearchGuards.isValidHttpUrl(hit.url)) return Problem.INVALID_URL
        if (hit.extractedSteps.isEmpty()) return Problem.NO_STEPS
        return null
    }
}

/**
 * 搜索编排：缓存 → 调搜索源 → 落缓存和原始来源 → 供界面展示 → 用户决定是否转成候选方法。
 *
 * 缓存的意义不只是省流量：同一型号短时间内重复搜，结果一致，也避免把不同型号的教程串台。
 */
class SearchService(
    private val db: KbDatabase,
    private val repo: KbRepository,
    private val provider: SearchProvider,
    private val ttlMs: Long
) {

    suspend fun search(query: SearchQuery): SearchOutcome = withContext(Dispatchers.IO) {
        val key = SearchGuards.cacheKey(query.brandId, query.brand, query.model, query.osVersion)
        val now = System.currentTimeMillis()

        db.cacheGet(key)?.let { cached ->
            if (SearchGuards.isFresh(cached.createdAt, ttlMs, now)) {
                val parsed = runCatching { decodeSearchResponse(cached.payload, cached.provider) }.getOrNull()
                if (parsed != null && parsed.error == null) {
                    return@withContext SearchOutcome(
                        query = query,
                        response = parsed,
                        fromCache = true,
                        cacheKey = key
                    )
                }
            }
        }

        val resp = provider.search(query)
        if (resp.error == null) {
            db.cachePut(
                KbDatabase.CachedSearch(
                    key = key,
                    provider = resp.provider,
                    payload = resp.toJson(),
                    isMock = resp.isMock,
                    createdAt = now,
                    expiresAt = now + ttlMs
                )
            )
            db.insertSearchHits(
                key,
                resp.hits.map {
                    KbDatabase.SearchHitRow(
                        it.title, it.url, it.snippet,
                        it.sourceType.name, it.credibility.name, it.fetchedAt, resp.isMock
                    )
                }
            )
        }
        SearchOutcome(query = query, response = resp, fromCache = false, cacheKey = key)
    }

    /**
     * 把用户明确选中的那一条结果转成"候选方法"。
     * 硬规则（服务层自己校验，不依赖界面）：
     * - 失败响应、模拟数据、跨品牌/跨型号、无效链接、没有步骤，一律拒绝。
     * - modelId 由服务自己解析，不接受外部传入（外部传错会把 4A 的教程挂到 4C 上）。
     * - 填了型号但库里没有这个型号时，拒绝存成品牌通用。
     */
    fun saveAsCandidate(
        outcome: SearchOutcome,
        brandId: String,
        modelText: String?,
        osVersion: String?,
        hitIndex: Int,
        title: String
    ): SaveResult {
        val resolvedModelId = repo.resolveModelId(brandId, modelText)
        if (SearchSaveGuard.modelScopeProblem(modelText, resolvedModelId) == SearchSaveGuard.Problem.UNRESOLVED_MODEL) {
            return SaveResult.UnresolvedModel
        }
        return when (val problem = SearchSaveGuard.check(outcome, brandId, modelText, osVersion, hitIndex)) {
            SearchSaveGuard.Problem.RESPONSE_ERROR ->
                SaveResult.Failed("这次搜索本身失败了（${outcome.response.error}），不能入库")
            SearchSaveGuard.Problem.MOCK -> SaveResult.MockBlocked
            SearchSaveGuard.Problem.IDENTITY_MISMATCH -> SaveResult.IdentityMismatch
            SearchSaveGuard.Problem.NO_HIT -> SaveResult.NoHit
            SearchSaveGuard.Problem.INVALID_URL -> SaveResult.InvalidUrl
            SearchSaveGuard.Problem.NO_STEPS -> SaveResult.NoSteps
            SearchSaveGuard.Problem.UNRESOLVED_MODEL -> SaveResult.UnresolvedModel
            null -> {
                val hit = outcome.response.hits[hitIndex]
                val steps = hit.extractedSteps.mapIndexed { i, s ->
                    TutorialStep(n = i + 1, title = "第 ${i + 1} 步", detail = s, note = "来源：${hit.url}")
                }
                val method = repo.addCandidate(
                    brandId = brandId,
                    modelId = resolvedModelId,
                    title = title.ifBlank { hit.title },
                    steps = steps,
                    notes = "由搜索结果整理，未经实机验证；原文摘要：${hit.snippet}",
                    confidence = hit.credibility,
                    sources = listOf(
                        SourceRef(
                            type = hit.sourceType,
                            url = hit.url,
                            title = hit.title,
                            collectedAt = hit.fetchedAt,
                            credibility = hit.credibility
                        )
                    )
                )
                SaveResult.Saved(method.id)
            }
        }
    }

    companion object {
        fun sourceLabel(t: SourceType): String = when (t) {
            SourceType.OFFICIAL -> "官方"
            SourceType.FORUM -> "论坛"
            SourceType.USER -> "用户"
            SourceType.UNKNOWN -> "来源不明"
        }
    }
}
