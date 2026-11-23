package com.tztvhelper.app.core.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 搜索响应严格解析：缺字段、版本不对、链接非法，一律拒绝 */
class SearchCodecTest {

    private val goodHit = """
        {
          "title": "小米电视开 ADB",
          "url": "https://example.com/a",
          "snippet": "摘要",
          "sourceType": "FORUM",
          "credibility": "UNVERIFIED",
          "fetchedAt": 1700000000000,
          "extractedSteps": ["打开设置", "账号与安全"]
        }
    """.trimIndent()

    private fun raw(
        version: String = "1",
        isMock: String? = "false",
        searchedAt: String = "1700000000000",
        hits: String = "[$goodHit]",
        provider: String = "\"test\""
    ): String {
        val mockPart = if (isMock == null) "" else "\"isMock\": $isMock,"
        return """
            {
              "v": $version,
              $mockPart
              "searchedAt": $searchedAt,
              "provider": $provider,
              "hits": $hits
            }
        """.trimIndent()
    }

    @Test
    fun `完整响应能解析`() {
        val r = decodeSearchResponse(raw(), "test")
        assertEquals(null, r.error)
        assertEquals(1, r.hits.size)
        assertFalse(r.isMock)
        assertEquals("https://example.com/a", r.hits[0].url)
        assertEquals(2, r.hits[0].extractedSteps.size)
    }

    @Test
    fun `缺少 isMock 字段必须拒绝`() {
        val r = decodeSearchResponse(raw(isMock = null), "test")
        assertEquals(SearchError.PARSE_FAILED, r.error)
        assertTrue(r.hits.isEmpty())
    }

    @Test
    fun `isMock 类型不对必须拒绝`() {
        val r = decodeSearchResponse(raw(isMock = "\"false\""), "test")
        assertEquals(SearchError.PARSE_FAILED, r.error)
    }

    @Test
    fun `错误协议版本必须拒绝`() {
        assertEquals(SearchError.PARSE_FAILED, decodeSearchResponse(raw(version = "2"), "test").error)
        assertEquals(SearchError.PARSE_FAILED, decodeSearchResponse(raw(version = "\"1\""), "test").error)
    }

    @Test
    fun `搜索时间缺失或为 0 必须拒绝`() {
        assertEquals(SearchError.PARSE_FAILED, decodeSearchResponse(raw(searchedAt = "0"), "test").error)
    }

    @Test
    fun `缺少 hits 数组必须拒绝`() {
        val json = """{"v":1,"isMock":false,"searchedAt":1700000000000,"provider":"t"}"""
        assertEquals(SearchError.PARSE_FAILED, decodeSearchResponse(json, "test").error)
    }

    @Test
    fun `无效链接的结果被拒绝，整包无有效结果时 PARSE_FAILED`() {
        val bad = goodHit.replace("https://example.com/a", "javascript:alert(1)")
        val r = decodeSearchResponse(raw(hits = "[$bad]"), "test")
        assertEquals(SearchError.PARSE_FAILED, r.error)
    }

    @Test
    fun `缺少抓取时间的结果被拒绝`() {
        val bad = goodHit.replace("\"fetchedAt\": 1700000000000,", "")
        val r = decodeSearchResponse(raw(hits = "[$bad]"), "test")
        assertEquals(SearchError.PARSE_FAILED, r.error)
    }

    @Test
    fun `来源类型或可信度不合法时该条被丢弃`() {
        val bad = goodHit.replace("\"sourceType\": \"FORUM\"", "\"sourceType\": \"随便\"")
        assertEquals(SearchError.PARSE_FAILED, decodeSearchResponse(raw(hits = "[$bad]"), "test").error)
    }

    @Test
    fun `好坏混合时只保留合格的那条`() {
        val bad = goodHit.replace("https://example.com/a", "not-a-url")
        val r = decodeSearchResponse(raw(hits = "[$bad, $goodHit]"), "test")
        assertEquals(null, r.error)
        assertEquals(1, r.hits.size)
        assertNotNull(r.hits[0].url)
    }

    @Test
    fun `isMock 为 true 时如实标记`() {
        val r = decodeSearchResponse(raw(isMock = "true"), "test")
        assertTrue(r.isMock)
    }

    @Test
    fun `返回的 JSON 能被再次解析`() {
        val r = decodeSearchResponse(raw(), "test")
        val again = decodeSearchResponse(r.toJson(), "test")
        assertEquals(r.hits.size, again.hits.size)
        assertEquals(r.isMock, again.isMock)
    }
}
