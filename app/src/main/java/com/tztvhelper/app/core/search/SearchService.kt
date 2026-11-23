package com.tztvhelper.app.core.search

import com.tztvhelper.app.core.kb.Credibility
import com.tztvhelper.app.core.kb.KbDatabase
import com.tztvhelper.app.core.kb.KbRepository
import com.tztvhelper.app.core.kb.SourceRef
import com.tztvhelper.app.core.kb.SourceType
import com.tztvhelper.app.core.kb.TutorialStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class SearchOutcome(
    val response: SearchResponse,
    val fromCache: Boolean,
    val cacheKey: String,
    /** 只有真实结果才为 true；模拟数据永远 false */
    val canSaveToKb: Boolean
)

sealed interface SaveResult {
    data class Saved(val methodId: String) : SaveResult
    data object MockBlocked : SaveResult
    data object NoSteps : SaveResult
    data class Failed(val reason: String) : SaveResult
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
        val key = SearchGuards.cacheKey(query.brand, query.model, query.osVersion)
        val now = System.currentTimeMillis()

        db.cacheGet(key)?.let { cached ->
            if (SearchGuards.isFresh(cached.createdAt, ttlMs, now)) {
                val parsed = runCatching { decodeSearchResponse(cached.payload, cached.provider) }.getOrNull()
                if (parsed != null) {
                    return@withContext SearchOutcome(
                        response = parsed,
                        fromCache = true,
                        cacheKey = key,
                        canSaveToKb = SearchGuards.canWriteToKb(parsed.isMock)
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
        SearchOutcome(resp, fromCache = false, cacheKey = key, canSaveToKb = SearchGuards.canWriteToKb(resp.isMock))
    }

    /**
     * 把选中的搜索结果转成"候选方法"入库。
     * 硬规则：模拟数据不许入库；没有步骤的不入库（避免空教程）。
     */
    fun saveAsCandidate(
        outcome: SearchOutcome,
        brandId: String,
        modelId: String?,
        title: String
    ): SaveResult {
        if (!outcome.canSaveToKb) return SaveResult.MockBlocked
        val hit = outcome.response.hits.firstOrNull() ?: return SaveResult.NoSteps
        if (hit.extractedSteps.isEmpty()) return SaveResult.NoSteps

        val steps = hit.extractedSteps.mapIndexed { i, s ->
            TutorialStep(n = i + 1, title = "第 ${i + 1} 步", detail = s, note = "来源：${hit.url}")
        }
        val sources = outcome.response.hits.map {
            SourceRef(
                type = it.sourceType,
                url = it.url,
                title = it.title,
                collectedAt = it.fetchedAt,
                credibility = it.credibility
            )
        }
        val method = repo.addCandidate(
            brandId = brandId,
            modelId = modelId,
            title = title.ifBlank { hit.title },
            steps = steps,
            notes = "由搜索结果整理，未经实机验证；原文摘要：${hit.snippet}",
            confidence = hit.credibility.takeIf { it != Credibility.UNVERIFIED } ?: Credibility.UNVERIFIED,
            sources = sources
        )
        return SaveResult.Saved(method.id)
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
