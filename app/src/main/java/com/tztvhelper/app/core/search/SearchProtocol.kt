package com.tztvhelper.app.core.search

import com.tztvhelper.app.core.kb.Credibility
import com.tztvhelper.app.core.kb.SourceType
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

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
    put("brandId", brandId)
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

/**
 * 严格解析。必需字段缺失/类型不对 → 整包 PARSE_FAILED。
 * 单条结果不合格 → 丢掉这条；一条都不剩时同样 PARSE_FAILED。
 */
fun decodeSearchResponse(raw: String, providerId: String): SearchResponse {
    val now = System.currentTimeMillis()
    fun bad() = SearchResponse(error = SearchError.PARSE_FAILED, searchedAt = now, provider = providerId)

    val o = runCatching { JSONObject(raw) }.getOrNull() ?: return bad()

    // 1) 协议版本必须存在、是数字、且是我们认识的版本
    if (!o.has("v") || o.get("v") !is Number) return bad()
    if ((o.get("v") as Number).toInt() != SearchProtocol.VERSION) return bad()

    // 2) isMock 必须显式给出且是布尔值——缺了绝不能当 false
    if (!o.has("isMock") || o.get("isMock") !is Boolean) return bad()
    val isMock = o.getBoolean("isMock")

    // 3) 搜索时间必须有效
    if (!o.has("searchedAt") || o.get("searchedAt") !is Number) return bad()
    val searchedAt = (o.get("searchedAt") as Number).toLong()
    if (searchedAt <= 0) return bad()

    val arr = o.optJSONArray("hits") ?: return bad()
    val hits = mutableListOf<SearchHit>()
    var dropped = 0

    for (i in 0 until arr.length()) {
        val h = arr.optJSONObject(i)
        if (h == null) { dropped++; continue }

        val title = h.optString("title").trim()
        val url = h.optString("url").trim()
        val src = runCatching { SourceType.valueOf(h.optString("sourceType")) }.getOrNull()
        val cred = runCatching { Credibility.valueOf(h.optString("credibility")) }.getOrNull()
        val fetchedRaw = h.opt("fetchedAt")
        val fetched = if (fetchedRaw is Number) fetchedRaw.toLong() else 0L

        val valid = title.isNotEmpty() &&
            SearchGuards.isValidHttpUrl(url) &&
            src != null && cred != null &&
            fetched > 0
        if (!valid) { dropped++; continue }

        hits += SearchHit(
            title = title,
            url = url,
            snippet = h.optString("snippet"),
            sourceType = src!!,
            credibility = cred!!,
            fetchedAt = fetched,
            extractedSteps = h.optJSONArray("extractedSteps")?.let { s ->
                (0 until s.length()).mapNotNull { s.optString(it).takeIf { t -> t.isNotBlank() } }
            } ?: emptyList()
        )
    }

    if (arr.length() > 0 && hits.isEmpty()) return bad()
    if (dropped > 0 && hits.isEmpty()) return bad()

    val err = o.optString("error").takeIf { it.isNotBlank() && it != "null" }
        ?.let { runCatching { SearchError.valueOf(it) }.getOrNull() }

    return SearchResponse(
        hits = hits,
        error = err,
        searchedAt = searchedAt,
        provider = o.optString("provider").takeIf { it.isNotBlank() } ?: providerId,
        isMock = isMock
    )
}

/** 纯函数：不含 Android/JSON 依赖，可直接单测 */
object SearchGuards {

    /** HTTP 状态码 → 用户能看懂的错误分类 */
    fun mapHttpStatus(code: Int): SearchError = when (code) {
        in 200..299 -> SearchError.PARSE_FAILED
        401, 403 -> SearchError.BLOCKED
        408, 504 -> SearchError.TIMEOUT
        429 -> SearchError.RATE_LIMIT
        else -> SearchError.NETWORK
    }

    /** 只有真实搜索结果才允许写进知识库 */
    fun canWriteToKb(isMock: Boolean): Boolean = !isMock

    /** 结果链接必须是 http/https 且有主机名，否则不许展示、不许入库 */
    fun isValidHttpUrl(url: String): Boolean {
        if (url.isBlank()) return false
        val u = runCatching { URI(url) }.getOrNull() ?: return false
        val scheme = u.scheme?.lowercase() ?: return false
        return (scheme == "http" || scheme == "https") && !u.host.isNullOrBlank()
    }

    /** 禁止向明文 HTTP 地址发送 Bearer 令牌 */
    fun maySendToken(baseUrl: String): Boolean =
        baseUrl.startsWith("https://", ignoreCase = true)

    fun normalizeText(s: String?): String =
        (s ?: "").trim().lowercase().replace(Regex("\\s+"), "")

    /** 保存前核对：搜索结果属于哪台设备，就只能存到哪台设备名下 */
    fun identityMatches(
        storedBrandId: String,
        storedModel: String?,
        storedOs: String?,
        brandId: String,
        model: String?,
        osVersion: String?
    ): Boolean =
        storedBrandId.isNotBlank() &&
            storedBrandId == brandId &&
            normalizeText(storedModel) == normalizeText(model) &&
            normalizeText(storedOs) == normalizeText(osVersion)

    /** 缓存键：同一品牌/型号/版本才复用，避免把别的型号的教程套过来 */
    fun cacheKey(brandId: String, brand: String, model: String?, osVersion: String?): String {
        fun norm(s: String?) = (s ?: "").trim().lowercase().replace(Regex("\\s+"), "")
        return "search:v${SearchProtocol.VERSION}:${brandId.ifBlank { norm(brand) }}|${norm(model)}|${norm(osVersion)}"
    }

    fun isFresh(createdAt: Long, ttlMs: Long, now: Long): Boolean =
        createdAt > 0 && now - createdAt in 0 until ttlMs
}
