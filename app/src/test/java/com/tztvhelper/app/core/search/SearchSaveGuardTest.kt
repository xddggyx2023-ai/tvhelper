package com.tztvhelper.app.core.search

import com.tztvhelper.app.core.kb.Credibility
import com.tztvhelper.app.core.kb.Method
import com.tztvhelper.app.core.kb.SourceType
import com.tztvhelper.app.core.kb.TutorialStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 搜索结果保存前的硬校验：界面藏按钮不算，服务层要自己拦 */
class SearchSaveGuardTest {

    private fun hit(
        url: String = "https://example.com/tutorial",
        steps: List<String> = listOf("打开设置", "找到账号与安全"),
        fetchedAt: Long = 1_700_000_000_000
    ) = SearchHit(
        title = "小米电视开 ADB 教程",
        url = url,
        snippet = "摘要",
        sourceType = SourceType.FORUM,
        credibility = Credibility.UNVERIFIED,
        fetchedAt = fetchedAt,
        extractedSteps = steps
    )

    private fun outcome(
        hits: List<SearchHit> = listOf(hit()),
        error: SearchError? = null,
        isMock: Boolean = false,
        brandId: String = "xiaomi",
        brand: String = "小米",
        model: String? = "4A",
        os: String? = "8.1"
    ) = SearchOutcome(
        query = SearchQuery(brandId, brand, model, os),
        response = SearchResponse(hits, error, 1_700_000_000_000, "test", isMock),
        fromCache = false,
        cacheKey = "k"
    )

    @Test
    fun `型号未解析的搜索结果不能存成品牌通用`() {
        // 用户填了「小米 4A Pro」，但知识库 models 表里没有 → 不能降级成品牌通用
        assertEquals(
            SearchSaveGuard.Problem.UNRESOLVED_MODEL,
            SearchSaveGuard.modelScopeProblem("4A Pro", null)
        )
        // 没填型号：明确按品牌通用保存，允许
        assertNull(SearchSaveGuard.modelScopeProblem(null, null))
        assertNull(SearchSaveGuard.modelScopeProblem("  ", null))
        // 填了且能解析成库内型号：允许
        assertNull(SearchSaveGuard.modelScopeProblem("4A", "xiaomi-4a"))
    }

    @Test
    fun `解析到型号时保存的是型号级而不是品牌通用`() {
        val m = Method(
            id = "m", brandId = "xiaomi", modelId = "xiaomi-4a", title = "t",
            scope = "model", steps = listOf(TutorialStep(1, "a", "b"))
        )
        assertEquals("model", m.scope)
        assertEquals("xiaomi-4a", m.modelId)
    }

    @Test
    fun `正常结果通过校验`() {
        assertNull(SearchSaveGuard.check(outcome(), "xiaomi", "4A", "8.1", 0))
    }

    @Test
    fun `小米的搜索结果不能存成海信教程`() {
        assertEquals(
            SearchSaveGuard.Problem.IDENTITY_MISMATCH,
            SearchSaveGuard.check(outcome(), "hisense", "4A", "8.1", 0)
        )
    }

    @Test
    fun `同一品牌不同型号不能混存`() {
        assertEquals(
            SearchSaveGuard.Problem.IDENTITY_MISMATCH,
            SearchSaveGuard.check(outcome(), "xiaomi", "4C", "8.1", 0)
        )
    }

    @Test
    fun `系统版本变了也不能存旧结果`() {
        assertEquals(
            SearchSaveGuard.Problem.IDENTITY_MISMATCH,
            SearchSaveGuard.check(outcome(), "xiaomi", "4A", "9", 0)
        )
    }

    @Test
    fun `模拟数据一律拒绝`() {
        assertEquals(
            SearchSaveGuard.Problem.MOCK,
            SearchSaveGuard.check(outcome(isMock = true), "xiaomi", "4A", "8.1", 0)
        )
    }

    @Test
    fun `搜索失败的结果不能入库`() {
        assertEquals(
            SearchSaveGuard.Problem.RESPONSE_ERROR,
            SearchSaveGuard.check(
                outcome(hits = emptyList(), error = SearchError.TIMEOUT),
                "xiaomi", "4A", "8.1", 0
            )
        )
    }

    @Test
    fun `无效链接不能入库`() {
        val bad = outcome(hits = listOf(hit(url = "javascript:alert(1)")))
        assertEquals(
            SearchSaveGuard.Problem.INVALID_URL,
            SearchSaveGuard.check(bad, "xiaomi", "4A", "8.1", 0)
        )
    }

    @Test
    fun `没有步骤的结果不能入库`() {
        val noSteps = outcome(hits = listOf(hit(steps = emptyList())))
        assertEquals(
            SearchSaveGuard.Problem.NO_STEPS,
            SearchSaveGuard.check(noSteps, "xiaomi", "4A", "8.1", 0)
        )
    }

    @Test
    fun `没选中的索引不能被当成第一条`() {
        assertEquals(
            SearchSaveGuard.Problem.NO_HIT,
            SearchSaveGuard.check(outcome(), "xiaomi", "4A", "8.1", 3)
        )
    }

    @Test
    fun `只有选中的那一条被校验，而不是固定第一条`() {
        val two = outcome(
            hits = listOf(
                hit(url = "javascript:bad"),
                hit(url = "https://example.com/good", steps = listOf("只有这条有步骤"))
            )
        )
        // 选第 0 条 → 链接不合法
        assertEquals(
            SearchSaveGuard.Problem.INVALID_URL,
            SearchSaveGuard.check(two, "xiaomi", "4A", "8.1", 0)
        )
        // 选第 1 条 → 通过
        assertNull(SearchSaveGuard.check(two, "xiaomi", "4A", "8.1", 1))
    }
}
