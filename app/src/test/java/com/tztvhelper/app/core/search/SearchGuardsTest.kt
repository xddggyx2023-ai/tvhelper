package com.tztvhelper.app.core.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchGuardsTest {

    @Test
    fun `缓存键忽略大小写和空格`() {
        assertEquals(
            SearchGuards.cacheKey("xiaomi", "小米", " 4A ", "8.1"),
            SearchGuards.cacheKey("xiaomi", "小米", "4a", "8.1")
        )
    }

    @Test
    fun `不同型号不同缓存键，避免串台`() {
        assertNotEquals(
            SearchGuards.cacheKey("xiaomi", "小米", "4A", null),
            SearchGuards.cacheKey("xiaomi", "小米", "4C", null)
        )
    }

    @Test
    fun `不同品牌即使型号同名也不共用缓存`() {
        assertNotEquals(
            SearchGuards.cacheKey("xiaomi", "小米", "4A", null),
            SearchGuards.cacheKey("hisense", "海信", "4A", null)
        )
    }

    @Test
    fun `明文 HTTP 不发送令牌`() {
        assertTrue(SearchGuards.maySendToken("https://api.example.com"))
        assertFalse(SearchGuards.maySendToken("http://api.example.com"))
        assertFalse(SearchGuards.maySendToken("ftp://api.example.com"))
    }

    @Test
    fun `结果链接必须是有主机名的 http 或 https`() {
        assertTrue(SearchGuards.isValidHttpUrl("https://example.com/a"))
        assertTrue(SearchGuards.isValidHttpUrl("http://example.com"))
        assertFalse(SearchGuards.isValidHttpUrl(""))
        assertFalse(SearchGuards.isValidHttpUrl("javascript:alert(1)"))
        assertFalse(SearchGuards.isValidHttpUrl("example.com/a"))
        assertFalse(SearchGuards.isValidHttpUrl("https:///no-host"))
    }

    @Test
    fun `保存身份校验：品牌型号版本必须一致`() {
        assertTrue(SearchGuards.identityMatches("xiaomi", "4A", "8.1", "xiaomi", " 4a ", "8.1"))
        assertFalse(SearchGuards.identityMatches("xiaomi", "4A", null, "hisense", "4A", null))
        assertFalse(SearchGuards.identityMatches("xiaomi", "4A", null, "xiaomi", "4C", null))
        assertFalse(SearchGuards.identityMatches("xiaomi", "4A", "8.1", "xiaomi", "4A", "9"))
    }

    @Test
    fun `缓存有效期边界`() {
        val ttl = 1000L
        assertTrue(SearchGuards.isFresh(createdAt = 500, ttlMs = ttl, now = 1000))
        assertFalse(SearchGuards.isFresh(createdAt = 0, ttlMs = ttl, now = 500))
        assertFalse(SearchGuards.isFresh(createdAt = 100, ttlMs = ttl, now = 1200))
        // 时钟被往回调：也算不新鲜，避免用未来时间伪造缓存
        assertFalse(SearchGuards.isFresh(createdAt = 2000, ttlMs = ttl, now = 1000))
    }

    @Test
    fun `模拟数据不许写进知识库`() {
        assertFalse(SearchGuards.canWriteToKb(isMock = true))
        assertTrue(SearchGuards.canWriteToKb(isMock = false))
    }

    @Test
    fun `HTTP 状态映射到用户能看懂的错误`() {
        assertEquals(SearchError.BLOCKED, SearchGuards.mapHttpStatus(403))
        assertEquals(SearchError.BLOCKED, SearchGuards.mapHttpStatus(401))
        assertEquals(SearchError.RATE_LIMIT, SearchGuards.mapHttpStatus(429))
        assertEquals(SearchError.TIMEOUT, SearchGuards.mapHttpStatus(504))
        assertEquals(SearchError.NETWORK, SearchGuards.mapHttpStatus(500))
        assertEquals(SearchError.NETWORK, SearchGuards.mapHttpStatus(418))
    }
}
