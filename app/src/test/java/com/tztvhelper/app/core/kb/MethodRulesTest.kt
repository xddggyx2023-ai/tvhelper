package com.tztvhelper.app.core.kb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 这几条断言就是"不允许新搜索结果覆盖已验证方法"的底线。
 */
class MethodRulesTest {

    private fun method(
        status: MethodStatus = MethodStatus.CANDIDATE,
        success: Int = 0,
        fail: Int = 0,
        confidence: Credibility = Credibility.UNVERIFIED,
        modelId: String? = null,
        scope: String = "brand"
    ) = Method(
        id = "m1", brandId = "xiaomi", modelId = modelId, title = "t", scope = scope,
        status = status, confidence = confidence, successCount = success, failCount = fail
    )

    @Test
    fun `新方法即使是官方来源也只是候选`() {
        val m = MethodRules.newCandidate(method(confidence = Credibility.OFFICIAL))
        assertEquals(MethodStatus.CANDIDATE, m.status)
        assertEquals(0, m.successCount)
    }

    @Test
    fun `APP实测成功直接把候选升为推荐`() {
        val m = MethodRules.afterVerification(method(), VerifyResult.SUCCESS_APP, 1000L)
        assertEquals(MethodStatus.RECOMMENDED, m.status)
        assertEquals(1, m.successCount)
        assertEquals(1000L, m.lastVerifiedAt)
    }

    @Test
    fun `一次用户口头反馈不足以升级`() {
        val m = MethodRules.afterVerification(method(), VerifyResult.SUCCESS_USER, 1000L)
        assertEquals(MethodStatus.CANDIDATE, m.status)
        assertEquals(1, m.successCount)
    }

    @Test
    fun `两次用户反馈成功才升级`() {
        val once = MethodRules.afterVerification(method(), VerifyResult.SUCCESS_USER, 1000L)
        val twice = MethodRules.afterVerification(once, VerifyResult.SUCCESS_USER, 2000L)
        assertEquals(MethodStatus.RECOMMENDED, twice.status)
        assertEquals(2, twice.successCount)
    }

    @Test
    fun `推荐方法失败后降为待复核但历史成功次数不清零`() {
        val recommended = method(status = MethodStatus.RECOMMENDED, success = 5, fail = 1)
        val after = MethodRules.afterVerification(recommended, VerifyResult.FAILURE, 3000L)
        assertEquals(MethodStatus.NEEDS_REVIEW, after.status)
        assertEquals(5, after.successCount)   // 成功记录保留
        assertEquals(2, after.failCount)
    }

    @Test
    fun `失败不会把候选方法直接判死`() {
        val after = MethodRules.afterVerification(method(), VerifyResult.FAILURE, 3000L)
        assertEquals(MethodStatus.CANDIDATE, after.status)
        assertEquals(1, after.failCount)
    }

    @Test
    fun `同一条目下推荐优先于候选`() {
        val recommended = method(status = MethodStatus.RECOMMENDED, success = 1)
        val candidate = method(status = MethodStatus.CANDIDATE, success = 0).copy(id = "m2")
        assertEquals("m1", MethodRules.pickBest(listOf(candidate, recommended))?.id)
    }

    @Test
    fun `精确型号加版本得分高于品牌通用`() {
        val brandGeneric = method()
        val modelExact = method(modelId = "mi4a", scope = "model").copy(osMin = "8.0", osMax = "9.9")
        assertTrue(
            MethodRules.score(modelExact, "mi4a", "9.0") >
                MethodRules.score(brandGeneric, "mi4a", "9.0")
        )
    }

    @Test
    fun `版本不在范围内时精确型号仍高于品牌通用`() {
        val outOfRange = method(modelId = "mi4a", scope = "model").copy(osMin = "8.0", osMax = "9.9")
        val brandGeneric = method()
        assertEquals(80, MethodRules.score(outOfRange, "mi4a", "11"))
        assertEquals(40, MethodRules.score(brandGeneric, "mi4a", "11"))
    }
}
