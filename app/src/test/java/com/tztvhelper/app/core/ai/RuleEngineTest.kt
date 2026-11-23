package com.tztvhelper.app.core.ai

import com.tztvhelper.app.core.kb.Credibility
import com.tztvhelper.app.core.kb.MatchLevel
import com.tztvhelper.app.core.kb.MatchResult
import com.tztvhelper.app.core.kb.Method
import com.tztvhelper.app.core.kb.MethodStatus
import com.tztvhelper.app.core.kb.SourceRef
import com.tztvhelper.app.core.kb.SourceType
import com.tztvhelper.app.core.kb.TutorialStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleEngineTest {

    private val profile = DeviceProfile(brandId = "xiaomi", brandName = "小米", model = "4A", osVersion = "8.1")

    private fun method(
        status: MethodStatus,
        success: Int = 0,
        fail: Int = 0
    ) = Method(
        id = "m1", brandId = "xiaomi", modelId = null, title = "小米电视开 ADB", scope = "brand",
        status = status, confidence = Credibility.UNVERIFIED,
        successCount = success, failCount = fail,
        steps = listOf(TutorialStep(1, "打开设置", "遥控器进入设置", listOf("设置"))),
        notes = "不同固件菜单位置不同",
        sources = listOf(
            SourceRef(SourceType.FORUM, "https://example.com/a", "某论坛帖子", 1000L, Credibility.FORUM)
        )
    )

    private fun engine(result: MatchResult) = RuleEngine(object : TutorialLookup {
        override fun find(brandId: String?, modelId: String?, osVersion: String?) = result
    })

    @Test
    fun `意图识别：开好了走下一步`() {
        assertEquals(Intent.AFTER_OPENED, IntentClassifier.classify("我按教程开了，下一步做什么"))
    }

    @Test
    fun `意图识别：连不上优先于教程`() {
        assertEquals(Intent.CONNECT_FAILED, IntentClassifier.classify("搜不到电视，连不上"))
    }

    @Test
    fun `意图识别：问型号`() {
        assertEquals(Intent.IDENTIFY_DEVICE, IntentClassifier.classify("我的电视型号在哪看"))
    }

    @Test
    fun `意图识别：问风险`() {
        assertEquals(Intent.RISK_CHECK, IntentClassifier.classify("会不会把电视弄坏"))
    }

    @Test
    fun `没有品牌时先要品牌，不编步骤`() {
        val a = engine(MatchResult.NotFound("xiaomi", null))
            .answer("怎么开 ADB", DeviceProfile())
        assertEquals(AnswerConfidence.NO_KB, a.confidence)
        assertTrue(a.steps.isEmpty())
        assertTrue(a.text.contains("品牌"))
    }

    @Test
    fun `候选方法给出步骤但标注未验证`() {
        val a = engine(MatchResult.Hit(method(MethodStatus.CANDIDATE), MatchLevel.BRAND_GENERIC, "分 40"))
            .answer("怎么开 ADB", profile)
        assertEquals(AnswerConfidence.KB_CANDIDATE, a.confidence)
        assertEquals(RiskLevel.MEDIUM, a.risk)
        assertEquals(1, a.steps.size)
        assertTrue(a.text.contains("还没在真机上确认"))
        assertEquals(1, a.sources.size)
    }

    @Test
    fun `推荐方法风险低`() {
        val a = engine(MatchResult.Hit(method(MethodStatus.RECOMMENDED, success = 3), MatchLevel.BRAND_GENERIC, "分 40"))
            .answer("怎么开 ADB", profile)
        assertEquals(AnswerConfidence.KB_RECOMMENDED, a.confidence)
        assertEquals(RiskLevel.LOW, a.risk)
        assertTrue(a.text.contains("3 次成功"))
    }

    @Test
    fun `待复核方法风险高并说明失败次数`() {
        val a = engine(MatchResult.Hit(method(MethodStatus.NEEDS_REVIEW, success = 2, fail = 3), MatchLevel.BRAND_GENERIC, "分 40"))
            .answer("怎么开 ADB", profile)
        assertEquals(AnswerConfidence.KB_NEEDS_REVIEW, a.confidence)
        assertEquals(RiskLevel.HIGH, a.risk)
        assertTrue(a.text.contains("失败 3 次"))
    }

    @Test
    fun `知识库没有时明确说没有`() {
        val a = engine(MatchResult.NotFound("xiaomi", null)).answer("怎么开 ADB", profile)
        assertEquals(AnswerConfidence.NO_KB, a.confidence)
        assertTrue(a.steps.isEmpty())
        assertTrue(a.text.contains("没有"))
        assertTrue(a.text.contains("不会凭空编"))
    }

    @Test
    fun `看不懂时给示例而不是瞎答`() {
        val a = engine(MatchResult.NotFound("xiaomi", null)).answer("emmm 你好", profile)
        assertEquals(Intent.UNKNOWN, a.intent)
        assertTrue(a.text.contains("没听懂"))
    }

    @Test
    fun `开好了的回答指向检测而不是重新教程`() {
        val a = engine(MatchResult.NotFound("xiaomi", null)).answer("开好了，下一步", profile)
        assertEquals(Intent.AFTER_OPENED, a.intent)
        assertTrue(a.actions.contains(AiAction.OPEN_SCAN))
        assertTrue(a.text.contains("允许 USB 调试"))
    }
}
