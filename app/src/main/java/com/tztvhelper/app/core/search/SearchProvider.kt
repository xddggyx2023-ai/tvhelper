package com.tztvhelper.app.core.search

import com.tztvhelper.app.core.kb.Credibility
import com.tztvhelper.app.core.kb.SourceType

/**
 * 实时搜索的抽象。第一版实现是 DisabledSearchProvider：
 * 没有服务器/搜索源时，明确告诉用户"未配置"，绝不编造结果。
 *
 * 以后接服务器只需新增 HttpSearchProvider，界面和知识库代码不动。
 */
interface SearchProvider {
    val id: String
    suspend fun search(query: SearchQuery): SearchResponse
}

data class SearchQuery(
    /** 品牌 ID（如 xiaomi）。保存结果时必须用它核对归属，防止跨品牌误存 */
    val brandId: String,
    val brand: String,
    val model: String?,
    val osVersion: String?,
    val maxResults: Int = 8
)

data class SearchHit(
    val title: String,
    val url: String,
    val snippet: String,
    val sourceType: SourceType,
    val credibility: Credibility,
    val fetchedAt: Long,
    /** AI 整理出的步骤；为空表示只拿到原始结果，还没分析 */
    val extractedSteps: List<String> = emptyList()
)

data class SearchResponse(
    val hits: List<SearchHit> = emptyList(),
    val error: SearchError? = null,
    val searchedAt: Long = 0L,
    val provider: String = "",
    /** true 表示本地模拟数据，不是真实联网结果。界面必须明示，且不许写进知识库。 */
    val isMock: Boolean = false
)

enum class SearchError {
    NOT_CONFIGURED,  // 还没服务器/搜索源
    NETWORK,
    BLOCKED,         // 被拦截/需要验证码
    TIMEOUT,
    RATE_LIMIT,
    PARSE_FAILED
}

/** 第一版：没有搜索源。返回明确的"未配置"，由界面如实告知用户。 */
class DisabledSearchProvider : SearchProvider {
    override val id = "disabled"
    override suspend fun search(query: SearchQuery) =
        SearchResponse(error = SearchError.NOT_CONFIGURED, searchedAt = System.currentTimeMillis())
}
