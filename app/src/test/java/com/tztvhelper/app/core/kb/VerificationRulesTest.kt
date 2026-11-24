package com.tztvhelper.app.core.kb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 「APP 实测成功」必须有连接依据，且设备要和方法的品牌/型号对得上 */
class VerificationRulesTest {

    private class FakeOracle(
        private val connected: Boolean,
        private val model: String?,
        private val brand: String? = null,
        private val release: String? = null
    ) : ConnectionOracle {
        override fun isConnected() = connected
        override fun deviceModel() = model
        override fun deviceBrand() = brand
        override fun deviceRelease() = release
    }

    private val xiaomi = Brand("xiaomi", "小米", listOf("Xiaomi", "Redmi"))
    private val hisense = Brand("hisense", "海信", listOf("Hisense", "VIDAA"))
    private val xiaomi4a = TvModel("xiaomi-4a", "xiaomi", "4A", "小米电视 4A")
    private val xiaomi4c = TvModel("xiaomi-4c", "xiaomi", "4C", "小米电视 4C")

    private fun method(modelId: String? = "xiaomi-4a", brandId: String = "xiaomi") = Method(
        id = "m1", brandId = brandId, modelId = modelId,
        title = "开 ADB", scope = if (modelId == null) "brand" else "model",
        steps = listOf(TutorialStep(1, "打开设置", "找到设置"))
    )

    private val connectedXiaomi4a = FakeOracle(true, "MiTV4A", "Xiaomi", "8.1.0")

    @Test
    fun `没有连接时不能记录 APP 实测成功`() {
        val oracle = FakeOracle(connected = false, model = null, brand = null)
        assertNull(VerificationRules.effectiveResult(VerifyResult.SUCCESS_APP, oracle, method(), xiaomi, xiaomi4a))
        assertNotNull(VerificationRules.successProblem(oracle, method(), xiaomi, xiaomi4a))
    }

    @Test
    fun `连上了但读不到设备信息也不能算 APP 实测`() {
        val oracle = FakeOracle(connected = true, model = null, brand = "Xiaomi")
        assertNull(VerificationRules.effectiveResult(VerifyResult.SUCCESS_APP, oracle, method(), xiaomi, xiaomi4a))
    }

    @Test
    fun `有真实连接且设备一致才允许 APP 实测成功`() {
        assertEquals(
            VerifyResult.SUCCESS_APP,
            VerificationRules.effectiveResult(VerifyResult.SUCCESS_APP, connectedXiaomi4a, method(), xiaomi, xiaomi4a)
        )
        assertNull(VerificationRules.successProblem(connectedXiaomi4a, method(), xiaomi, xiaomi4a))
    }

    @Test
    fun `连着小米电视时不能给海信方法记 APP 实测`() {
        val problem = VerificationRules.successProblem(connectedXiaomi4a, method(brandId = "hisense"), hisense, null)
        assertNotNull(problem)
        assertTrue(problem!!.contains("不是「海信」"))
        assertNull(
            VerificationRules.effectiveResult(
                VerifyResult.SUCCESS_APP, connectedXiaomi4a, method(brandId = "hisense"), hisense, null
            )
        )
    }

    @Test
    fun `连着小米 4A 时不能给 4C 的方法记 APP 实测`() {
        val problem = VerificationRules.successProblem(connectedXiaomi4a, method("xiaomi-4c"), xiaomi, xiaomi4c)
        assertNotNull(problem)
        assertNull(
            VerificationRules.effectiveResult(
                VerifyResult.SUCCESS_APP, connectedXiaomi4a, method("xiaomi-4c"), xiaomi, xiaomi4c
            )
        )
    }

    @Test
    fun `品牌通用方法只核对品牌，不硬套型号`() {
        assertNull(
            VerificationRules.successProblem(connectedXiaomi4a, method(modelId = null), xiaomi, null)
        )
    }

    @Test
    fun `品牌别名能识别电视上报的英文品牌名`() {
        assertTrue(VerificationRules.matchesBrand("Xiaomi", xiaomi))
        assertTrue(VerificationRules.matchesBrand("Hisense", hisense))
        assertEquals(false, VerificationRules.matchesBrand("Sony", xiaomi))
    }

    @Test
    fun `带分隔符的真实型号也能对上`() {
        assertTrue(VerificationRules.matchesModel("KD-55X8500G", "55X8500G"))
        assertTrue(VerificationRules.matchesModel("MiTV4A", "4A"))
        assertEquals(false, VerificationRules.matchesModel("MiTV4C", "4A"))
    }

    @Test
    fun `用户手动反馈不需要连接`() {
        val oracle = FakeOracle(connected = false, model = null, brand = null)
        assertEquals(
            VerifyResult.SUCCESS_USER,
            VerificationRules.effectiveResult(VerifyResult.SUCCESS_USER, oracle, method(), xiaomi, xiaomi4a)
        )
        assertEquals(
            VerifyResult.FAILURE,
            VerificationRules.effectiveResult(VerifyResult.FAILURE, oracle, method(), xiaomi, xiaomi4a)
        )
    }

    @Test
    fun `失败原因为空记为未提供原因`() {
        assertEquals(VerificationRules.NO_REASON, VerificationRules.normalizeReason(null))
        assertEquals(VerificationRules.NO_REASON, VerificationRules.normalizeReason("   "))
        assertEquals("第 3 步找不到菜单", VerificationRules.normalizeReason(" 第 3 步找不到菜单 "))
    }

    @Test
    fun `失败原因会写进历史记录`() {
        val detail = VerificationRules.historyDetail(
            VerifyResult.FAILURE, "8.1", "MiTV4A", "第 3 步选项是灰的", 2, 1
        )
        assertTrue(detail.contains("第 3 步选项是灰的"))
        assertTrue(detail.contains("累计成功=2"))
        assertTrue(detail.contains("失败=1"))
    }

    @Test
    fun `不填原因的历史也明确写未提供原因`() {
        val detail = VerificationRules.historyDetail(VerifyResult.FAILURE, null, null, null, 1, 1)
        assertTrue(detail.contains("未提供原因"))
    }
}
