package com.tztvhelper.app.core.search

import com.tztvhelper.app.core.kb.Credibility
import com.tztvhelper.app.core.kb.SourceType
import org.json.JSONArray
import org.json.JSONObject

/**
 * 搜索服务的线上协议（v1）。
 *
 * 约定：
 * - APP 只调自己的 VPS，不直连任何第三方搜索服务，Key 只在服务器上。
 * - 服务端返回的每条结果都必须带 url + 抓取时间 + 可信度，APP 原样展示。
 * - mock 标记只能由服务端或本地 MockProvider 置位，任何真实请求都不得置位。
 */
object SearchProtocol {
    const val VERSION = 1
    const val PATH = "/v1/search"
}

fun SearchQuery.toJson(): String = JSONObject().apply {
    put("v", SearchProtocol.VERSION)
    put("brand", brand)
    put("model", model ?: JSONObject.NULL)
    put("osVersion", osVersion ?: JSONObject.NULL)
    put("maxResults", maxResults)
}.toString()

fun SearchResponse.toJson(): String = JSONObject().apply {
    put("v", SearchProtocol.VERSION)
    put("provider", provider)
    put("searchedAt", searchedAt)
    put("isMock", isMock)
    error?.let { put("error", it.name) } ?: put("error", JSONObject.NULL)
    put("hits", JSONArray().apply {
        hits.forEach { h ->
            put(JSONObject().apply {
                put("title", h.title)
                put("url", h.url)
                put("snippet", h.snippet)
                put("sourceType", h.sourceType.name)
                put("credibility", h.credibility.name)
                put("fetchedAt", h.fetchedAt)
                put("extractedSteps", JSONArray(h.extractedSteps))
            })
        }
    })
}.toString()

fun decodeSearchResponse(raw: String, providerId: String): SearchResponse {
    val o = JSONObject(raw)
    val hits = o.optJSONArray("hits")?.let { arr ->
        List(arr.length()) { i ->
            val h = arr.getJSONObject(i)
            SearchHit(
                title = h.optString("title"),
                url = h.optString("url"),
                snippet = h.optString("snippet"),
                sourceType = runCatching { SourceType.valueOf(h.optString("sourceType")) }
                    .getOrDefault(SourceType.UNKNOWN),
                credibility = runCatching { Credibility.valueOf(h.optString("credibility")) }
                    .getOrDefault(Credibility.UNVERIFIED),
                fetchedAt = h.optLong("fetchedAt", o.optLong("searchedAt", 0L)),
                extractedSteps = h.optJSONArray("extractedSteps")?.let { s ->
                    List(s.length()) { s.getString(it) }
                } ?: emptyList()
            )
        }
    } ?: emptyList()

    val err = o.optString("error").takeIf { it.isNotBlank() && it != "null" }
        ?.let { runCatching { SearchError.valueOf(it) }.getOrNull() }

    return SearchResponse(
        hits = hits,
        error = err,
        searchedAt = o.optLong("searchedAt", System.currentTimeMillis()),
        provider = o.optString("provider", providerId),
        isMock = o.optBoolean("isMock", false)
    )
}

/** 纯函数：不含 Android/JSON 依赖，可直接单测 */
object SearchGuards {

    /** HTTP 状态码 → 用户能看懂的错误分类 */
    fun mapHttpStatus(code: Int): SearchError = when (code) {
        in 200..299 -> SearchError.PARSE_FAILED // 不该走到这，兜底
        401, 403 -> SearchError.BLOCKED
        408, 504 -> SearchError.TIMEOUT
        429 -> SearchError.RATE_LIMIT
        in 500..599 -> SearchError.NETWORK
        else -> SearchError.NETWORK
    }

    /**
     * 只有真实搜索结果才允许写进知识库。
     * 模拟数据一律不许落库——否则以后真假混在一起，用户没法判断。
     */
    fun canWriteToKb(isMock: Boolean): Boolean = !isMock

    /** 缓存键：同一品牌/型号/版本才复用，避免把别的型号的教程套过来 */
    fun cacheKey(brand: String, model: String?, osVersion: String?): String {
        fun norm(s: String?) = (s ?: "").trim().lowercase().replace(Regex("\\s+"), "")
        return "search:v${SearchProtocol.VERSION}:${norm(brand)}|${norm(model)}|${norm(osVersion)}"
    }

    fun isFresh(createdAt: Long, ttlMs: Long, now: Long): Boolean =
        createdAt > 0 && now - createdAt in 0 until ttlMs
}
