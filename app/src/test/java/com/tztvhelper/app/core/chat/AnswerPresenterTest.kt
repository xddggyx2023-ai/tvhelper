package com.tztvhelper.app.core.chat

import com.tztvhelper.app.core.ai.AiAction
import com.tztvhelper.app.core.ai.AiAnswer
import com.tztvhelper.app.core.ai.AnswerConfidence
import com.tztvhelper.app.core.ai.DeviceProfile
import com.tztvhelper.app.core.ai.Intent
import com.tztvhelper.app.core.ai.RiskLevel
import com.tztvhelper.app.core.guide.GuideStage
import com.tztvhelper.app.core.kb.TutorialStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnswerPresenterTest {

    private fun answer(
        confidence: AnswerConfidence = AnswerConfidence.GENERAL,
        risk: RiskLevel = RiskLevel.LOW,
        intent: Intent = Intent.FIND_TUTORIAL,
        actions: List<AiAction> = emptyList(),
        methodId: String? = null,
        steps: List<TutorialStep> = emptyList(),
        text: String = "找到一条方法。"
    ) = AiAnswer(
        text = text, steps = steps, risk = risk, confidence = confidence,
        actions = actions, intent = intent, methodId = methodId
    )

    @Test
    fun `已推荐且低风险不显示依据风险行`() {
        assertNull(
            AnswerPresenter.metaLine(answer(AnswerConfidence.KB_RECOMMENDED, RiskLevel.LOW))
        )
    }

    @Test
    fun `未验证的方法用大白话提示`() {
        val line = AnswerPresenter.metaLine(answer(AnswerConfidence.KB_CANDIDATE, RiskLevel.MEDIUM))
        assertEquals("这条方法还没在真电视上验证过，电视菜单可能和它略有不同。", line)
        assertFalse(line!!.contains("候选"))
    }

    @Test
    fun `需要复核与没查到也各有一句说明`() {
        assertTrue(
            AnswerPresenter.metaLine(answer(AnswerConfidence.KB_NEEDS_REVIEW, RiskLevel.HIGH))!!
                .contains("失败过")
        )
        assertTrue(
            AnswerPresenter.metaLine(answer(AnswerConfidence.NO_KB, RiskLevel.MEDIUM))!!
                .contains("没有这个型号")
        )
        assertFalse(
            AnswerPresenter.metaLine(answer(AnswerConfidence.NO_KB, RiskLevel.MEDIUM))!!
                .contains("候选")
        )
    }

    @Test
    fun `最多两个操作按钮且不重复`() {
        val a = answer(
            confidence = AnswerConfidence.KB_CANDIDATE,
            methodId = "m1",
            actions = listOf(AiAction.OPEN_GUIDE, AiAction.SAVE_PROFILE, AiAction.OPEN_SCAN)
        )
        val actions = AnswerPresenter.actions(a, hasBrand = true)
        assertEquals(2, actions.size)
        assertEquals(ChatActionKind.OPEN_GUIDE, actions[0].kind)
        assertEquals(ChatActionKind.OPEN_SCAN, actions[1].kind)
    }

    @Test
    fun `知识库没答案且已选品牌时才给联网搜索`() {
        val noKb = answer(confidence = AnswerConfidence.NO_KB, intent = Intent.FIND_TUTORIAL)
        assertEquals(
            ChatActionKind.SEARCH_WEB,
            AnswerPresenter.actions(noKb, hasBrand = true).first().kind
        )
        assertTrue(AnswerPresenter.actions(noKb, hasBrand = false).none { it.kind == ChatActionKind.SEARCH_WEB })
    }

    @Test
    fun `步骤默认只显示第一步`() {
        val steps = listOf(
            TutorialStep(1, "打开设置", "按遥控器齿轮键"),
            TutorialStep(2, "进关于", "找到关于本机")
        )
        val a = answer(steps = steps)
        assertEquals(1, AnswerPresenter.stepsToShow(a.steps, AnswerFold()))
        assertEquals(2, AnswerPresenter.stepsToShow(a.steps, AnswerFold(stepsOpen = true)))
    }

    @Test
    fun `步骤与来源的折叠能来回切换`() {
        var fold = AnswerFold()
        assertFalse(fold.stepsOpen)
        fold = fold.toggleSteps()
        assertTrue(fold.stepsOpen)
        fold = fold.toggleSteps()
        assertFalse(fold.stepsOpen)
        fold = fold.toggleSources()
        assertTrue(fold.sourcesOpen)
        assertEquals("查看全部步骤", fold.toggleSteps().let { AnswerFold() }.stepButton)
    }

    @Test
    fun `内部状态词不出现在正文`() {
        val a = answer(
            text = "找到一条 小米 的方法：开 ADB\n状态：候选：还没有实机验证记录\n匹配程度：型号对上了\n注意：先连 Wi-Fi"
        )
        val visible = AnswerPresenter.visibleText(a)
        assertFalse(visible.contains("状态："))
        assertFalse(visible.contains("匹配程度："))
        assertTrue(visible.contains("注意：先连 Wi-Fi"))
    }

    @Test
    fun `快捷回复按引导阶段生成`() {
        val profile = DeviceProfile("xiaomi", "小米", "4A", "8.1")
        assertEquals(3, QuickReplies.forAnswer(answer(), profile, GuideStage.STEP).size)
        assertEquals(
            listOf("我看到了，下一步", "我没找到这个", "和我看到的不一样"),
            QuickReplies.forAnswer(answer(), profile, GuideStage.STEP)
        )
        assertEquals(2, QuickReplies.forAnswer(answer(), profile, GuideStage.TROUBLESHOOT).size)
        assertTrue(QuickReplies.forAnswer(answer(), profile, GuideStage.PORT_CHECK).isEmpty())
    }

    @Test
    fun `快捷回复跟着意图变化且有上限`() {
        val profile = DeviceProfile("xiaomi", "小米", "4A", "8.1")
        val noKb = QuickReplies.forAnswer(answer(confidence = AnswerConfidence.NO_KB), profile, GuideStage.IDLE)
        assertTrue(noKb.contains("先按品牌通用方法查"))
        val kb = QuickReplies.forAnswer(
            answer(confidence = AnswerConfidence.KB_RECOMMENDED), profile, GuideStage.IDLE
        )
        assertTrue(kb.contains("开始一步步引导"))
        assertTrue(kb.size <= 4)
        assertTrue(QuickReplies.forAnswer(answer(), DeviceProfile(), GuideStage.IDLE).isEmpty())
    }

    @Test
    fun `首次进入先问品牌不先问型号`() {
        val empty = DeviceProfile()
        assertTrue(ChatOnboarding.needsBrand(empty))
        assertTrue(ChatOnboarding.firstPrompt().contains("什么品牌"))
        assertFalse(ChatOnboarding.firstPrompt().contains("型号是什么"))
        val withBrand = DeviceProfile("xiaomi", "小米")
        assertFalse(ChatOnboarding.needsBrand(withBrand))
        assertTrue(ChatOnboarding.afterBrand("小米").contains("型号"))
    }

    @Test
    fun `跳过型号也能继续`() {
        val text = ChatOnboarding.afterSkipModel("小米")
        assertTrue(text.contains("通用方法"))
        assertEquals("不知道，跳过", ChatOnboarding.SKIP_MODEL)
    }
}
