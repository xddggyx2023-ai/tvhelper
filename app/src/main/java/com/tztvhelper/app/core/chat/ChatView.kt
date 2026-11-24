package com.tztvhelper.app.core.chat

import com.tztvhelper.app.core.ai.AiAnswer
import com.tztvhelper.app.core.ai.AnswerConfidence
import com.tztvhelper.app.core.ai.AiAction
import com.tztvhelper.app.core.ai.DeviceProfile
import com.tztvhelper.app.core.ai.Intent
import com.tztvhelper.app.core.ai.RiskLevel
import com.tztvhelper.app.core.guide.GuideStage

/** 一条助手消息最多两个操作按钮：一个主要、一个次要 */
enum class ChatActionKind { OPEN_GUIDE, SEARCH_WEB, SET_PROFILE, OPEN_SCAN }

data class ChatAction(val kind: ChatActionKind, val label: String)

/** 步骤与来源的折叠状态（纯数据，可单测） */
data class AnswerFold(val stepsOpen: Boolean = false, val sourcesOpen: Boolean = false) {
    fun toggleSteps(): AnswerFold = copy(stepsOpen = !stepsOpen)
    fun toggleSources(): AnswerFold = copy(sourcesOpen = !sourcesOpen)
    val stepButton: String
        get() = if (stepsOpen) "收起步骤" else "查看全部步骤"
}

/**
 * 助手回答的展示规则：全在这里，界面只负责画。
 *
 * 三件事必须守住：
 * 1. 内部词（候选/待复核/EXACT）不进普通用户的视线。
 * 2. 依据与风险只在不放心时用一句大白话说，不堆标签。
 * 3. 操作按钮最多两个。
 */
object AnswerPresenter {

    private val internalPrefixes = listOf("状态：", "匹配程度：")

    /** 去掉 RuleEngine 文本里的内部状态行，保留结论与注意事项 */
    fun visibleText(answer: AiAnswer): String =
        answer.text.lineSequence()
            .filterNot { line -> internalPrefixes.any { line.trim().startsWith(it) } }
            .joinToString("\n")
            .trim()
            .replace(Regex("\n{3,}"), "\n\n")

    /**
     * 依据/风险的一句话。已推荐且低风险时不显示。
     * 只用大白话，不出现"候选""待复核"。
     */
    fun metaLine(answer: AiAnswer): String? = when {
        answer.confidence == AnswerConfidence.KB_RECOMMENDED && answer.risk == RiskLevel.LOW -> null
        answer.confidence == AnswerConfidence.KB_RECOMMENDED ->
            "这条方法在真电视上成功过，后来也出现过失败，操作时留意电视的反应。"
        answer.confidence == AnswerConfidence.KB_CANDIDATE ->
            "这条方法还没在真电视上验证过，电视菜单可能和它略有不同。"
        answer.confidence == AnswerConfidence.KB_NEEDS_REVIEW ->
            "有人按这条方法失败过，先看清每一步再动手。"
        answer.confidence == AnswerConfidence.NO_KB ->
            "知识库里没有这个型号的准确做法，下面是可以自己查的方向。"
        else -> "这是通用说明，不是针对你型号的专属教程。"
    }

    /** 最多两个操作按钮；按钮不重复 */
    fun actions(answer: AiAnswer, hasBrand: Boolean): List<ChatAction> {
        val out = ArrayList<ChatAction>(2)
        if (answer.methodId != null && answer.confidence != AnswerConfidence.NO_KB) {
            out += ChatAction(ChatActionKind.OPEN_GUIDE, "查看完整教程")
        } else if (answer.confidence == AnswerConfidence.NO_KB && hasBrand &&
            answer.intent in setOf(Intent.FIND_TUTORIAL, Intent.STEP_HELP, Intent.UNKNOWN)
        ) {
            out += ChatAction(ChatActionKind.SEARCH_WEB, "联网搜索这个型号")
        }
        if (answer.actions.contains(AiAction.OPEN_SCAN)) {
            out += ChatAction(ChatActionKind.OPEN_SCAN, "去首页搜电视")
        }
        if (answer.actions.contains(AiAction.SAVE_PROFILE) && out.none { it.kind == ChatActionKind.OPEN_GUIDE }) {
            out += ChatAction(ChatActionKind.SET_PROFILE, "设置我的电视")
        }
        return out.distinctBy { it.kind }.take(2)
    }

    /** 引擎一次给多步时，默认只显示第 1 步 */
    fun stepsToShow(steps: List<*>, fold: AnswerFold): Int =
        if (fold.stepsOpen) steps.size else minOf(1, steps.size)

    fun hasSteps(answer: AiAnswer): Boolean = answer.steps.isNotEmpty()
}

/**
 * 每条助手消息下面的快捷回复（不算操作按钮）。
 * 只在最新一条上显示，最多 4 个。
 */
object QuickReplies {

    const val SKIP_MODEL = "不知道，跳过"

    fun forAnswer(answer: AiAnswer?, profile: DeviceProfile, stage: GuideStage): List<String> = when {
        stage == GuideStage.STEP ->
            listOf("我看到了，下一步", "我没找到这个", "和我看到的不一样")
        stage == GuideStage.TROUBLESHOOT ->
            listOf("试过了还是不行", "回到刚才那一步")
        stage == GuideStage.CONNECT ->
            listOf("电视弹窗我点了允许", "电视没弹窗")
        stage == GuideStage.PORT_CHECK -> emptyList()
        answer == null -> emptyList()
        !profile.isComplete -> emptyList()
        answer.confidence == AnswerConfidence.NO_KB ->
            listOf("我的电视型号在哪看", "先按品牌通用方法查")
        answer.confidence in setOf(
            AnswerConfidence.KB_RECOMMENDED,
            AnswerConfidence.KB_CANDIDATE,
            AnswerConfidence.KB_NEEDS_REVIEW
        ) -> listOf("开始一步步引导", "我的电视型号在哪看")
        else -> listOf("怎么开 ADB", "连不上电视怎么办", "会不会把电视弄坏")
    }.take(4)
}

/**
 * 首次进入的对话式建档：先问品牌，再问型号。
 * 不先甩一张表单给用户。
 */
object ChatOnboarding {

    const val SKIP_MODEL = QuickReplies.SKIP_MODEL

    fun firstPrompt(): String =
        "你好，我是开启助手。\n" +
            "你不需要懂 ADB，我一步一步陪你做：先确认你的电视，再带你在电视上打开开关，最后连上。\n\n" +
            "先问一句：你的电视是什么品牌？"

    fun afterBrand(brandName: String): String =
        "好的，$brandName。\n" +
            "你的电视型号是什么？知道就直接打字发我（例如 4A、X90J）；" +
            "不知道也没关系，点下面的「$SKIP_MODEL」，我按 $brandName 的通用方法带你。"

    fun afterSkipModel(brandName: String): String =
        "没关系，型号先空着，我按 $brandName 的通用方法带你。\n" +
            "通用方法里的菜单名可能和你的电视略有不同，遇到不一样的地方随时告诉我。"

    fun welcome(profile: DeviceProfile): String {
        val name = profile.brandName ?: "你的电视"
        val model = profile.model?.takeIf { it.isNotBlank() }?.let { " $it" } ?: ""
        return "欢迎回来。我记得你的电视是 $name$model。\n" +
            "可以直接问「怎么开 ADB」，也可以点下面的「开始一步步引导」。"
    }

    /** 档案里还没有品牌 → 先问品牌，不要先问型号 */
    fun needsBrand(profile: DeviceProfile): Boolean = profile.brandId.isNullOrBlank()
}
