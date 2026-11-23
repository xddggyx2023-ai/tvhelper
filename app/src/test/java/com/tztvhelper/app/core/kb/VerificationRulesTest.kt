package com.tztvhelper.app.core.kb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** 「APP 实测成功」必须有连接依据，不能由按钮自己宣布 */
class VerificationRulesTest {

    private class FakeOracle(
        private val connected: Boolean,
        private val model: String?
    ) : ConnectionOracle {
        override fun isConnected() = connected
        override fun deviceModel() = model
    }

    @Test
    fun `没有连接时不能记录 APP 实测成功`() {
        val oracle = FakeOracle(connected = false, model = null)
        assertNull(VerificationRules.effectiveResult(VerifyResult.SUCCESS_APP, oracle))
    }

    @Test
    fun `连上了但读不到设备信息也不能算 APP 实测`() {
        val oracle = FakeOracle(connected = true, model = null)
        assertNull(VerificationRules.effectiveResult(VerifyResult.SUCCESS_APP, oracle))
    }

    @Test
    fun `有真实连接才允许 APP 实测成功`() {
        val oracle = FakeOracle(connected = true, model = "MiTV4A")
        assertEquals(
            VerifyResult.SUCCESS_APP,
            VerificationRules.effectiveResult(VerifyResult.SUCCESS_APP, oracle)
        )
    }

    @Test
    fun `用户手动反馈不需要连接`() {
        val oracle = FakeOracle(connected = false, model = null)
        assertEquals(
            VerifyResult.SUCCESS_USER,
            VerificationRules.effectiveResult(VerifyResult.SUCCESS_USER, oracle)
        )
        assertEquals(
            VerifyResult.FAILURE,
            VerificationRules.effectiveResult(VerifyResult.FAILURE, oracle)
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
        assertNotNull(detail)
        assertEquals(true, detail.contains("第 3 步选项是灰的"))
        assertEquals(true, detail.contains("累计成功=2"))
        // 以前的成功次数仍在
        assertEquals(true, detail.contains("失败=1"))
    }

    @Test
    fun `不填原因的历史也明确写未提供原因`() {
        val detail = VerificationRules.historyDetail(
            VerifyResult.FAILURE, null, null, null, 1, 1
        )
        assertEquals(true, detail.contains("未提供原因"))
    }
}
