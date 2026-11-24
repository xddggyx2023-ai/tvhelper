package com.tztvhelper.app.core.guide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideFlowTest {

    private val started = GuideFlow.reduce(GuideState(), GuideEvent.Start("m1", totalSteps = 3)).state

    @Test
    fun `开始引导从第一步进入`() {
        assertEquals(GuideStage.STEP, started.stage)
        assertEquals(0, started.stepIndex)
        assertEquals("m1", started.methodId)
    }

    @Test
    fun `没有步骤的方法不能开始`() {
        val s = GuideFlow.reduce(GuideState(), GuideEvent.Start("m1", 0)).state
        assertEquals(GuideStage.IDLE, s.stage)
    }

    @Test
    fun `下一步逐级推进`() {
        val s1 = GuideFlow.reduce(started, GuideEvent.Next).state
        assertEquals(1, s1.stepIndex)
        val s2 = GuideFlow.reduce(s1, GuideEvent.Next).state
        assertEquals(2, s2.stepIndex)
        assertTrue(s2.isLastStep)
    }

    @Test
    fun `最后一步之后进入端口检测并触发扫描`() {
        val last = started.copy(stepIndex = 2)
        val t = GuideFlow.reduce(last, GuideEvent.Next)
        assertEquals(GuideStage.PORT_CHECK, t.state.stage)
        assertEquals(listOf(GuideEffect.ScanPort), t.effects)
    }

    @Test
    fun `我没找到进入开发者选项分支`() {
        val t = GuideFlow.reduce(started, GuideEvent.NotFound).state
        assertEquals(GuideStage.TROUBLESHOOT, t.stage)
        assertEquals(TroubleshootBranch.NO_DEVELOPER_OPTIONS, t.branch)
        assertEquals(0, t.stepIndex) // 不丢当前步骤
    }

    @Test
    fun `和我看到的不一样进入选项变灰分支`() {
        val t = GuideFlow.reduce(started, GuideEvent.Different).state
        assertEquals(TroubleshootBranch.OPTION_GREYED, t.branch)
    }

    @Test
    fun `排查完回到同一步而不是从头开始`() {
        val trouble = started.copy(stepIndex = 1, stage = GuideStage.TROUBLESHOOT, branch = TroubleshootBranch.NO_DEVELOPER_OPTIONS)
        val t = GuideFlow.reduce(trouble, GuideEvent.BranchDone).state
        assertEquals(GuideStage.STEP, t.stage)
        assertEquals(1, t.stepIndex)
        assertNull(t.branch)
    }

    @Test
    fun `电视没有 ADB 开关也有固定分支`() {
        val t = GuideFlow.reduce(started, GuideEvent.NoAdbSwitch).state
        assertEquals(TroubleshootBranch.NO_ADB_SWITCH, t.branch)
    }

    @Test
    fun `端口开着进入连接`() {
        val check = started.copy(stage = GuideStage.PORT_CHECK)
        val t = GuideFlow.reduce(check, GuideEvent.PortOpen)
        assertEquals(GuideStage.CONNECT, t.state.stage)
        assertEquals(listOf(GuideEffect.Connect), t.effects)
    }

    @Test
    fun `端口没开退回上一步`() {
        val check = started.copy(stage = GuideStage.PORT_CHECK, stepIndex = 2)
        val t = GuideFlow.reduce(check, GuideEvent.PortClosed).state
        assertEquals(GuideStage.STEP, t.stage)
        assertEquals(1, t.stepIndex)
    }

    @Test
    fun `第一步没开端口就退回第一步`() {
        val check = GuideState(stage = GuideStage.PORT_CHECK, methodId = "m1", stepIndex = 0, totalSteps = 1)
        val t = GuideFlow.reduce(check, GuideEvent.PortClosed).state
        assertEquals(0, t.stepIndex)
        assertEquals(GuideStage.STEP, t.stage)
    }

    @Test
    fun `连接成功且档案一致直接完成`() {
        val connect = started.copy(stage = GuideStage.CONNECT)
        val t = GuideFlow.reduce(connect, GuideEvent.Connected(sameAsProfile = true)).state
        assertEquals(GuideStage.DONE, t.stage)
        assertEquals(false, t.awaitConfirm)
    }

    @Test
    fun `连接成功但品牌型号不一致先等用户确认`() {
        val connect = started.copy(stage = GuideStage.CONNECT)
        val t = GuideFlow.reduce(connect, GuideEvent.Connected(sameAsProfile = false)).state
        assertEquals(GuideStage.CONNECT, t.stage)
        assertTrue(t.awaitConfirm)
        val accepted = GuideFlow.reduce(
            GuideFlow.withDevice(t, "Hisense", "HZ55E3D", "9"),
            GuideEvent.AcceptDetected
        ).state
        assertEquals(GuideStage.DONE, accepted.stage)
        assertEquals("HZ55E3D", accepted.detectedModel)
    }

    @Test
    fun `保持我填的也能完成并清掉检测值`() {
        val connect = started.copy(stage = GuideStage.CONNECT, awaitConfirm = true)
        val kept = GuideFlow.reduce(
            GuideFlow.withDevice(connect, "Hisense", "HZ55E3D", "9"),
            GuideEvent.KeepMine
        ).state
        assertEquals(GuideStage.DONE, kept.stage)
        assertNull(kept.detectedModel)
    }

    @Test
    fun `电视没弹窗进入固定分支并能重连`() {
        val connect = started.copy(stage = GuideStage.CONNECT)
        val trouble = GuideFlow.reduce(connect, GuideEvent.NoAuthDialog).state
        assertEquals(TroubleshootBranch.NO_AUTH_DIALOG, trouble.branch)
        val retry = GuideFlow.reduce(trouble, GuideEvent.RetryConnect)
        assertEquals(GuideStage.CONNECT, retry.state.stage)
        assertEquals(listOf(GuideEffect.Connect), retry.effects)
    }

    @Test
    fun `无关阶段的事件不会乱跳`() {
        assertEquals(started, GuideFlow.reduce(started, GuideEvent.PortOpen).state)
        assertEquals(GuideStage.IDLE, GuideFlow.reduce(GuideState(), GuideEvent.Next).state.stage)
        assertEquals(GuideStage.IDLE, GuideFlow.reduce(GuideState(), GuideEvent.Connected(true)).state.stage)
    }

    @Test
    fun `排查文案是固定内容`() {
        val dev = GuideCopy.branch(TroubleshootBranch.NO_DEVELOPER_OPTIONS)
        assertEquals(GuideCopy.NO_DEV_BODY, dev.body)
        assertTrue(dev.body.contains("连点 5～7 次"))
        val grey = GuideCopy.branch(TroubleshootBranch.OPTION_GREYED)
        assertEquals(GuideCopy.GREY_BODY, grey.body)
        assertTrue(grey.body.contains("灰"))
        val none = GuideCopy.branch(TroubleshootBranch.NO_ADB_SWITCH)
        assertEquals(GuideCopy.NO_SWITCH_BODY, none.body)
        val auth = GuideCopy.branch(TroubleshootBranch.NO_AUTH_DIALOG)
        assertEquals(GuideCopy.NO_AUTH_BODY, auth.body)
        assertTrue(auth.body.contains("授权"))
        assertEquals(dev, GuideCopy.branch(TroubleshootBranch.NO_DEVELOPER_OPTIONS))
    }

    @Test
    fun `进度编码解码往返一致`() {
        val s = started.copy(stepIndex = 2, branch = TroubleshootBranch.NO_DEVELOPER_OPTIONS)
        val back = GuideProgress.decode(GuideProgress.encode(s))
        assertEquals(s, back)
    }

    @Test
    fun `无效进度不会恢复成错误状态`() {
        assertNull(GuideProgress.decode(null))
        assertNull(GuideProgress.decode(""))
        assertNull(GuideProgress.decode("stage=NOPE"))
        assertNull(GuideProgress.decode("stage=STEP;method=;step=0;total=0"))
        assertNull(GuideProgress.decode("stage=STEP;method=m1;step=9;total=3"))
    }

    @Test
    fun `进度存取走同一份存储`() {
        var box: String? = null
        val store = GuideProgressStore(object : GuideStorage {
            override fun read(): String? = box
            override fun write(value: String) { box = value }
        })
        assertNull(store.load())
        val s = started.copy(stepIndex = 1, branch = TroubleshootBranch.OPTION_GREYED)
        store.save(s)
        assertNotNull(box)
        assertEquals(s, store.load())
        store.clear()
        assertNull(store.load())
    }
}
