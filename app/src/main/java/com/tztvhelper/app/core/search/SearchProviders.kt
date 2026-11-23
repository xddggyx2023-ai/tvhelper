package com.tztvhelper.app.core.search

import com.tztvhelper.app.core.kb.Credibility
import com.tztvhelper.app.core.kb.SourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/**
 * 调自建 VPS 的搜索代理。服务器地址为空时不要实例化它——
 * 由 SearchProviderFactory 决定用 Mock 还是 Disabled。
 *
 * 这里的实现是"现在就写好、等 VPS 就能用"：协议固定、错误分类固定、超时固定，
 * 以后服务端上线不需要改 APP。
 */
class HttpSearchProvider(
    private val baseUrl: String,
    private val apiToken: String? = null
) : SearchProvider {

    override val id: String = "http-${SearchProtocol.VERSION}"

    override suspend fun search(query: SearchQuery): SearchResponse = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            val url = URL(baseUrl.trimEnd('/') + SearchProtocol.PATH)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8_000
                readTimeout = 15_000
                doOutput = true
                useCaches = false
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                apiToken?.takeIf { it.isNotBlank() }?.let {
                    // 明文 HTTP 绝不携带令牌，避免令牌在链路上裸奔
                    if (SearchGuards.maySendToken(baseUrl)) {
                        setRequestProperty("Authorization", "Bearer $it")
                    }
                }
            }
            conn.outputStream.use { it.write(query.toJson().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val now = System.currentTimeMillis()
            if (code !in 200..299) {
                return@withContext SearchResponse(
                    error = SearchGuards.mapHttpStatus(code),
                    searchedAt = now,
                    provider = id
                )
            }
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val parsed = runCatching { decodeSearchResponse(body, id) }.getOrNull()
                ?: return@withContext SearchResponse(
                    error = SearchError.PARSE_FAILED, searchedAt = now, provider = id
                )
            // 服务器必须明确告诉我们是真实结果；缺这个字段按"不可信"处理
            parsed.copy(provider = id)
        } catch (e: SocketTimeoutException) {
            SearchResponse(error = SearchError.TIMEOUT, searchedAt = System.currentTimeMillis(), provider = id)
        } catch (e: UnknownHostException) {
            SearchResponse(error = SearchError.NETWORK, searchedAt = System.currentTimeMillis(), provider = id)
        } catch (e: Exception) {
            SearchResponse(error = SearchError.NETWORK, searchedAt = System.currentTimeMillis(), provider = id)
        } finally {
            conn?.disconnect()
        }
    }
}

/**
 * 本地模拟服务：只为验证"请求 → 缓存 → 展示 → 转候选"这条流程。
 * 每条结果都带 isMock=true，界面上必须显示"模拟数据"。
 * 模拟结果不允许写进知识库（见 SearchGuards.canWriteToKb）。
 */
class MockSearchProvider(private val loadFixture: () -> String) : SearchProvider {

    override val id = "mock-local"

    override suspend fun search(query: SearchQuery): SearchResponse = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val raw = runCatching { loadFixture() }.getOrNull()
            ?: return@withContext SearchResponse(
                error = SearchError.PARSE_FAILED, searchedAt = now, provider = id, isMock = true
            )
        val root = runCatching { JSONObject(raw) }.getOrNull()
            ?: return@withContext SearchResponse(
                error = SearchError.PARSE_FAILED, searchedAt = now, provider = id, isMock = true
            )
        val arr = root.optJSONArray("hits") ?: return@withContext SearchResponse(
            hits = emptyList(), searchedAt = now, provider = id, isMock = true
        )
        val hits = List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            SearchHit(
                title = o.optString("title"),
                url = o.optString("url"),
                snippet = o.optString("snippet"),
                sourceType = runCatching { SourceType.valueOf(o.optString("sourceType")) }
                    .getOrDefault(SourceType.UNKNOWN),
                credibility = runCatching { Credibility.valueOf(o.optString("credibility")) }
                    .getOrDefault(Credibility.UNVERIFIED),
                fetchedAt = now,
                extractedSteps = o.optJSONArray("steps")?.let { s ->
                    List(s.length()) { s.getString(it) }
                } ?: emptyList()
            )
        }
        SearchResponse(hits = hits, searchedAt = now, provider = id, isMock = true)
    }
}
