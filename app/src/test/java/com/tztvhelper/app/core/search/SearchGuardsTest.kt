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
            SearchGuards.cacheKey("Xiaomi", " 4A ", "8.1"),
            SearchGuards.cacheKey("xiaomi", "4a", "8.1")
        )
    }

    @Test
    fun `不同型号不同缓存键，避免串台`() {
        assertNotEquals(
            SearchGuards.cacheKey("xiaomi", "4A", null),
            SearchGuards.cacheKey("xiaomi", "4C", null)
        )
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
