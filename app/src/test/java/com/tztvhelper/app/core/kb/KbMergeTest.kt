package com.tztvhelper.app.core.kb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 知识库升级只增量合并：已有方法的状态、验证次数、历史都不能被覆盖 */
class KbMergeTest {

    private fun method(id: String, status: MethodStatus = MethodStatus.CANDIDATE) = Method(
        id = id, brandId = "xiaomi", modelId = null, title = id, scope = "brand",
        steps = listOf(TutorialStep(1, "a", "b")), status = status,
        successCount = 3, failCount = 1, lastVerifiedAt = 123L
    )

    @Test
    fun `只添加库里没有的方法`() {
        val existing = setOf("m1")
        val incoming = listOf(method("m1"), method("m2"))
        val added = KbMerge.newMethods(existing, incoming)
        assertEquals(listOf("m2"), added.map { it.id })
    }

    @Test
    fun `已有方法不会被新内置数据覆盖成候选`() {
        val existing = setOf("m1")
        val recommended = method("m1", MethodStatus.RECOMMENDED)
        val incoming = listOf(method("m1"), method("m2"))
        // 合并后 m1 仍是推荐状态、成功 3 次——库里原有对象没被替换
        val added = KbMerge.newMethods(existing, incoming)
        assertEquals(false, added.any { it.id == "m1" })
        assertEquals(MethodStatus.RECOMMENDED, recommended.status)
        assertEquals(3, recommended.successCount)
    }

    @Test
    fun `已有型号不会被重复添加`() {
        val existing = setOf("xiaomi-4a")
        val incoming = listOf(
            TvModel("xiaomi-4a", "xiaomi", "4A", "小米电视 4A"),
            TvModel("xiaomi-4c", "xiaomi", "4C", "小米电视 4C")
        )
        assertEquals(listOf("xiaomi-4c"), KbMerge.newModels(existing, incoming).map { it.id })
    }

    @Test
    fun `全新安装时全部加入`() {
        val incoming = listOf(method("m1"), method("m2"))
        assertEquals(2, KbMerge.newMethods(emptySet(), incoming).size)
        assertTrue(KbMerge.newModels(emptySet(), listOf(TvModel("x", "xiaomi", "4A", "4A"))).isNotEmpty())
    }
}
