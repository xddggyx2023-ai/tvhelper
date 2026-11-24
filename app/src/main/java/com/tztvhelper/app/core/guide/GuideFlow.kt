package com.tztvhelper.app.core.guide

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * 逐步引导的状态机（纯 Kotlin，无 Android 依赖，可单测）。
 *
 * 设计底线：
 * - 一次只走一步，不一次甩整篇教程。
 * - 按钮对应的转移是固定的；「没找到」「不一样」进入固定排查分支，不允许现编文案。
 * - 端口扫描结果决定下一步：开着 → 连接；没开 → 退回上一步。
 * - 进度可编码保存，退出再进来能接着走。
 */
data class GuideState(
    val stage: GuideStage = GuideStage.IDLE,
    val methodId: String? = null,
    val stepIndex: Int = 0,
    val totalSteps: Int = 0,
    val branch: TroubleshootBranch? = null,
    val awaitConfirm: Boolean = false,
    val detectedBrand: String? = null,
    val detectedModel: String? = null,
    val detectedOs: String? = null
) {
    val isLastStep: Boolean get() = totalSteps > 0 && stepIndex >= totalSteps - 1
    val stepNumber: Int get() = (stepIndex + 1).coerceAtMost(totalSteps.coerceAtLeast(1))
}

sealed interface GuideEvent {
    data class Start(val methodId: String, val totalSteps: Int) : GuideEvent
    data object Next : GuideEvent
    data object NotFound : GuideEvent
    data object Different : GuideEvent
    data object NoAdbSwitch : GuideEvent
    data object BranchDone : GuideEvent
    data object PortOpen : GuideEvent
    data object PortClosed : GuideEvent
    data class Connected(val sameAsProfile: Boolean) : GuideEvent
    data object NoAuthDialog : GuideEvent
    data object AcceptDetected : GuideEvent
    data object KeepMine : GuideEvent
    data object RetryConnect : GuideEvent
    data object Reset : GuideEvent
}

sealed interface GuideEffect {
    data object ScanPort : GuideEffect
    data object Connect : GuideEffect
}

data class GuideTransition(
    val state: GuideState,
    val effects: List<GuideEffect> = emptyList()
)

object GuideFlow {

    fun reduce(state: GuideState, event: GuideEvent): GuideTransition = when (event) {
        is GuideEvent.Start -> {
            if (event.totalSteps <= 0) GuideTransition(state)
            else GuideTransition(
                GuideState(
                    stage = GuideStage.STEP,
                    methodId = event.methodId,
                    stepIndex = 0,
                    totalSteps = event.totalSteps
                )
            )
        }

        GuideEvent.Next -> when (state.stage) {
            GuideStage.STEP -> if (state.isLastStep) {
                GuideTransition(state.copy(stage = GuideStage.PORT_CHECK), listOf(GuideEffect.ScanPort))
            } else {
                GuideTransition(state.copy(stepIndex = state.stepIndex + 1))
            }
            else -> GuideTransition(state)
        }

        GuideEvent.NotFound -> when (state.stage) {
            GuideStage.STEP -> GuideTransition(
                state.copy(stage = GuideStage.TROUBLESHOOT, branch = TroubleshootBranch.NO_DEVELOPER_OPTIONS)
            )
            else -> GuideTransition(state)
        }

        GuideEvent.Different -> when (state.stage) {
            GuideStage.STEP -> GuideTransition(
                state.copy(stage = GuideStage.TROUBLESHOOT, branch = TroubleshootBranch.OPTION_GREYED)
            )
            else -> GuideTransition(state)
        }

        GuideEvent.NoAdbSwitch -> when (state.stage) {
            GuideStage.STEP, GuideStage.TROUBLESHOOT -> GuideTransition(
                state.copy(stage = GuideStage.TROUBLESHOOT, branch = TroubleshootBranch.NO_ADB_SWITCH)
            )
            else -> GuideTransition(state)
        }

        GuideEvent.BranchDone -> when (state.stage) {
            GuideStage.TROUBLESHOOT -> GuideTransition(
                state.copy(stage = GuideStage.STEP, branch = null)
            )
            else -> GuideTransition(state)
        }

        GuideEvent.PortOpen -> when (state.stage) {
            GuideStage.PORT_CHECK -> GuideTransition(
                state.copy(stage = GuideStage.CONNECT), listOf(GuideEffect.Connect)
            )
            else -> GuideTransition(state)
        }

        GuideEvent.PortClosed -> when (state.stage) {
            GuideStage.PORT_CHECK -> GuideTransition(
                state.copy(stage = GuideStage.STEP, stepIndex = (state.stepIndex - 1).coerceAtLeast(0))
            )
            else -> GuideTransition(state)
        }

        is GuideEvent.Connected -> when (state.stage) {
            GuideStage.CONNECT -> if (event.sameAsProfile) {
                GuideTransition(state.copy(stage = GuideStage.DONE, awaitConfirm = false))
            } else {
                GuideTransition(state.copy(stage = GuideStage.CONNECT, awaitConfirm = true))
            }
            else -> GuideTransition(state)
        }

        GuideEvent.NoAuthDialog -> when (state.stage) {
            GuideStage.CONNECT -> GuideTransition(
                state.copy(stage = GuideStage.TROUBLESHOOT, branch = TroubleshootBranch.NO_AUTH_DIALOG)
            )
            else -> GuideTransition(state)
        }

        GuideEvent.AcceptDetected -> when {
            state.stage == GuideStage.CONNECT && state.awaitConfirm ->
                GuideTransition(state.copy(stage = GuideStage.DONE, awaitConfirm = false))
            else -> GuideTransition(state)
        }

        GuideEvent.KeepMine -> when {
            state.stage == GuideStage.CONNECT && state.awaitConfirm ->
                GuideTransition(
                    state.copy(
                        stage = GuideStage.DONE, awaitConfirm = false,
                        detectedBrand = null, detectedModel = null, detectedOs = null
                    )
                )
            else -> GuideTransition(state)
        }

        GuideEvent.RetryConnect -> when (state.stage) {
            GuideStage.CONNECT -> GuideTransition(
                state.copy(stage = GuideStage.CONNECT, awaitConfirm = false), listOf(GuideEffect.Connect)
            )
            GuideStage.TROUBLESHOOT -> if (state.branch == TroubleshootBranch.NO_AUTH_DIALOG) {
                GuideTransition(
                    state.copy(stage = GuideStage.CONNECT, branch = null), listOf(GuideEffect.Connect)
                )
            } else GuideTransition(state)
            else -> GuideTransition(state)
        }

        GuideEvent.Reset -> GuideTransition(GuideState())
    }

    /** 接上检测到的真实设备信息（界面拿到 Adb.info 后调用） */
    fun withDevice(state: GuideState, brand: String, model: String, os: String): GuideState =
        state.copy(detectedBrand = brand, detectedModel = model, detectedOs = os)
}

/** 排查分支的固定文案：同一分支永远是同一段内容，不因上下文变化 */
data class BranchCopy(val title: String, val body: String, val next: String)

object GuideCopy {

    const val NO_DEV_TITLE = "菜单里没有「开发者选项」"
    const val NO_DEV_BODY = "大多数电视把它藏起来了。到「设置 → 关于本机 / 关于电视」，找到「版本号」，用遥控器连点 5～7 次，屏幕会提示「已进入开发者模式」。"
    const val NO_DEV_NEXT = "连点完成后，返回上一级菜单，重新找「开发者选项」。"

    const val GREY_TITLE = "开发者选项是灰的，点不动"
    const val GREY_BODY = "选项是灰的，点不动。常见原因有三种：电视登录了管理员账号但你不是管理员；电视处于工程/演示模式；系统刚恢复出厂还没联网激活。"
    const val GREY_NEXT = "先重启电视再看一次；还是灰的，就是这台电视没有向普通用户开放，APP 也开不了。"

    const val NO_SWITCH_TITLE = "电视里没有 ADB 开关"
    const val NO_SWITCH_BODY = "部分品牌把「USB 调试 / ADB 调试」换成了别的名字，常见叫「开发者选项 → USB 调试」「远程调试」或「ADB 开关」。"
    const val NO_SWITCH_NEXT = "在开发者选项里逐项找这几个名字；一个都没有，说明这台电视的系统没有开放 ADB。"

    const val NO_AUTH_TITLE = "连接时电视没有弹授权窗口"
    const val NO_AUTH_BODY = "电视必须和手机在同一个 Wi-Fi，且电视上的 ADB 开关已经打开。连接时电视应该弹出「允许调试」的授权窗口；部分电视只在第一次连接时弹，之后会自动允许。"
    const val NO_AUTH_NEXT = "回到电视前，用遥控器重新打开一次 ADB 开关，再点「重新连接」。"

    fun branch(b: TroubleshootBranch): BranchCopy = when (b) {
        TroubleshootBranch.NO_DEVELOPER_OPTIONS -> BranchCopy(NO_DEV_TITLE, NO_DEV_BODY, NO_DEV_NEXT)
        TroubleshootBranch.OPTION_GREYED -> BranchCopy(GREY_TITLE, GREY_BODY, GREY_NEXT)
        TroubleshootBranch.NO_ADB_SWITCH -> BranchCopy(NO_SWITCH_TITLE, NO_SWITCH_BODY, NO_SWITCH_NEXT)
        TroubleshootBranch.NO_AUTH_DIALOG -> BranchCopy(NO_AUTH_TITLE, NO_AUTH_BODY, NO_AUTH_NEXT)
    }
}

/** 进度编码：纯字符串，可存 SharedPreferences，也可单测 */
object GuideProgress {

    fun encode(s: GuideState): String {
        val fields = linkedMapOf(
            "stage" to s.stage.name,
            "method" to (s.methodId ?: ""),
            "step" to s.stepIndex.toString(),
            "total" to s.totalSteps.toString(),
            "branch" to (s.branch?.name ?: ""),
            "confirm" to if (s.awaitConfirm) "1" else "0"
        )
        return fields.entries.joinToString(";") { (k, v) ->
            "$k=${URLEncoder.encode(v, "UTF-8")}"
        }
    }

    fun decode(text: String?): GuideState? {
        if (text.isNullOrBlank()) return null
        val map = text.split(";").mapNotNull {
            val i = it.indexOf('=')
            if (i <= 0) null else it.substring(0, i) to it.substring(i + 1)
        }.toMap()
        val stage = runCatching { GuideStage.valueOf(map["stage"] ?: "") }.getOrNull() ?: return null
        val method = map["method"]?.let { URLDecoder.decode(it, "UTF-8") }?.takeIf { it.isNotBlank() }
        val step = map["step"]?.toIntOrNull() ?: 0
        val total = map["total"]?.toIntOrNull() ?: 0
        val branch = map["branch"]?.takeIf { it.isNotBlank() }?.let {
            runCatching { TroubleshootBranch.valueOf(it) }.getOrNull()
        }
        if (stage == GuideStage.STEP && (method == null || total <= 0 || step !in 0 until total)) return null
        return GuideState(
            stage = stage,
            methodId = method,
            stepIndex = step.coerceAtLeast(0),
            totalSteps = total.coerceAtLeast(0),
            branch = branch,
            awaitConfirm = map["confirm"] == "1"
        )
    }
}

/** 存储抽象：Android 用 SharedPreferences，单测用内存 Map */
interface GuideStorage {
    fun read(): String?
    fun write(value: String)
}

class GuideProgressStore(private val storage: GuideStorage) {
    fun load(): GuideState? = GuideProgress.decode(storage.read())

    fun save(state: GuideState) {
        if (state.stage == GuideStage.IDLE) storage.write("")
        else storage.write(GuideProgress.encode(state))
    }

    fun clear() = storage.write("")
}
