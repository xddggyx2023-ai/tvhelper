package com.tztvhelper.app.core.ai

import com.tztvhelper.app.core.kb.MatchResult
import com.tztvhelper.app.core.kb.Method
import com.tztvhelper.app.core.kb.MethodStatus

/**
 * 本地规则问答。回答只有三种来源：
 * 1) 知识库里查到的真实方法（带来源、状态、成功/失败次数）
 * 2) 固定的操作说明/排查清单
 * 3) 明确告诉用户"我不知道/知识库没有"
 *
 * 它不会"编"步骤，也不会假装是联网大模型——界面上必须写清"本地规则"。
 */
class RuleEngine(private val lookup: TutorialLookup) : AiEngine {

    override fun answer(input: String, profile: DeviceProfile): AiAnswer {
        val intent = IntentClassifier.classify(input)
        return when (intent) {
            Intent.IDENTIFY_DEVICE -> identify(profile)
            Intent.FIND_TUTORIAL -> tutorial(profile, input)
            Intent.STEP_HELP -> stepHelp(profile, input)
            Intent.BEFORE_START -> beforeStart()
            Intent.AFTER_OPENED -> afterOpened(profile)
            Intent.CONNECT_FAILED -> connectFailed(profile)
            Intent.RISK_CHECK -> risk()
            Intent.WHAT_IS_ADB -> whatIsAdb()
            Intent.UNKNOWN -> unknown(profile)
        }
    }

    // ---------- 各意图 ----------

    private fun identify(profile: DeviceProfile): AiAnswer {
        val known = if (profile.isComplete) {
            "你现在保存的是：${profile.brandName ?: ""}" +
                (profile.model?.let { " $it" } ?: "") +
                (profile.osVersion?.let { "，系统 $it" } ?: "") +
                "。如果不对，直接在下面改成正确的。\n\n"
        } else ""

        return AiAnswer(
            text = known +
                "看型号有三个地方，从简单到麻烦：\n" +
                "1. 电视设置里：设置 → 关于 / 系统信息 / 本机信息，找「型号」「设备型号」「软件版本」。\n" +
                "2. 电视背面或底部的产品标签：上面印着完整型号（最准，但要挪电视）。\n" +
                "3. 开机第一屏或关于页面的版本号：位数较多，通常能对应到系列。\n\n" +
                "把品牌和型号告诉我（例如「小米 4A」），我就能查对应的开启方法。",
            confidence = AnswerConfidence.GENERAL,
            risk = RiskLevel.LOW,
            actions = listOf(AiAction.SAVE_PROFILE, AiAction.OPEN_GUIDE),
            intent = Intent.IDENTIFY_DEVICE
        )
    }

    private fun tutorial(profile: DeviceProfile, raw: String): AiAnswer {
        if (profile.brandId.isNullOrBlank()) {
            return AiAnswer(
                text = "先告诉我是哪个品牌，我才能查对应的开启方法。\n\n" +
                    "在「我的电视」里选品牌（小米、长虹、海信、TCL、创维、索尼、华为……），" +
                    "型号和系统版本知道就填、不知道可以先空着。",
                confidence = AnswerConfidence.NO_KB,
                risk = RiskLevel.LOW,
                actions = listOf(AiAction.SAVE_PROFILE, AiAction.OPEN_GUIDE),
                intent = Intent.FIND_TUTORIAL
            )
        }
        return tutorialAnswer(profile)
    }

    private fun stepHelp(profile: DeviceProfile, raw: String): AiAnswer {
        if (profile.brandId.isNullOrBlank()) {
            return tutorial(profile, raw)
        }
        val base = tutorialAnswer(profile)
        val prefix = "菜单里找不到某一步，多半是固件版本不一样，不用怀疑自己。\n" +
            "先在「关于 / 关于本机」里对着版本号连点 5～7 次，开发者选项才会出现；" +
            "如果连开发者选项都没有，这台电视可能没开放 ADB。\n\n"
        return base.copy(text = prefix + base.text, intent = Intent.STEP_HELP)
    }

    private fun tutorialAnswer(profile: DeviceProfile): AiAnswer {
        val brand = profile.brandName ?: "该品牌"
        val modelId = lookup.resolveModelId(profile.brandId, profile.model)
        return when (val r = lookup.find(profile.brandId, modelId, profile.osVersion)) {
            is MatchResult.Hit -> fromMethod(r.method, brand, r.level)
            is MatchResult.NotFound -> AiAnswer(
                text = "本地知识库里没有「$brand" +
                    (profile.model?.let { " $it" } ?: "") +
                    "」的可靠方法，我不会凭空编一套步骤给你。\n\n" +
                    "能做的有三件事：\n" +
                    "1. 型号先留空，按品牌通用方法再查一次（很多型号通用）；\n" +
                    "2. 在教程页把你的型号反馈上来，后续补充；\n" +
                    "3. 如果电视上看不到开发者选项，可能这台机器没开放 ADB——这个结论要检测过才能下。",
                confidence = AnswerConfidence.NO_KB,
                risk = RiskLevel.MEDIUM,
                actions = listOf(AiAction.OPEN_GUIDE, AiAction.OPEN_SEARCH),
                intent = Intent.FIND_TUTORIAL
            )
        }
    }

    private fun fromMethod(m: Method, brand: String, level: com.tztvhelper.app.core.kb.MatchLevel): AiAnswer {
        val (conf, risk, statusLine) = when (m.status) {
            MethodStatus.RECOMMENDED ->
                Triple(AnswerConfidence.KB_RECOMMENDED, RiskLevel.LOW, "已推荐：有 ${m.successCount} 次成功记录")
            MethodStatus.NEEDS_REVIEW ->
                Triple(AnswerConfidence.KB_NEEDS_REVIEW, RiskLevel.HIGH, "待复核：以前成功过，但后来失败 ${m.failCount} 次")
            MethodStatus.REJECTED ->
                Triple(AnswerConfidence.KB_NEEDS_REVIEW, RiskLevel.HIGH, "已否决：这条方法被确认不可用")
            MethodStatus.CANDIDATE ->
                Triple(AnswerConfidence.KB_CANDIDATE, RiskLevel.MEDIUM, "候选：还没有实机验证记录")
        }

        val text = buildString {
            appendLine("找到一条 $brand 的方法：${m.title}")
            when (level) {
                com.tztvhelper.app.core.kb.MatchLevel.EXACT_MODEL_OS ->
                    appendLine("匹配程度：你的型号 + 系统版本都对上了。")
                com.tztvhelper.app.core.kb.MatchLevel.MODEL ->
                    appendLine("匹配程度：型号对上了，系统版本没有专门记录。")
                com.tztvhelper.app.core.kb.MatchLevel.BRAND_GENERIC ->
                    appendLine("匹配程度：这是 $brand 的品牌通用方法，没有你那个型号的专属教程，菜单可能和你的电视有出入。")
            }
            appendLine("状态：$statusLine。")
            if (m.status != MethodStatus.RECOMMENDED) {
                appendLine("这条资料还没在真机上确认过，请一步一步对照电视画面；哪一步和你的电视不一样，就停下告诉我。")
            }
            if (m.notes.isNotBlank()) appendLine("注意：${m.notes}")
        }.trim()

        return AiAnswer(
            text = text,
            steps = m.steps,
            sources = m.sources,
            risk = risk,
            confidence = conf,
            actions = listOf(AiAction.OPEN_GUIDE),
            intent = Intent.FIND_TUTORIAL,
            methodId = m.id,
            methodTitle = m.title
        )
    }

    private fun beforeStart(): AiAnswer = AiAnswer(
        text = "开始之前确认四件事：\n" +
            "1. 手机和电视连同一个 Wi-Fi（不能一个连 5G、一个连访客网络）；\n" +
            "2. 遥控器在手边，电视上要弹窗让你点「允许」；\n" +
            "3. 电视能正常上网（有些电视断网后设置项会变灰）；\n" +
            "4. 先知道电视型号，不同品牌菜单位置完全不一样。\n\n" +
            "整个过程不需要 root，也不用拆机。",
        confidence = AnswerConfidence.GENERAL,
        risk = RiskLevel.LOW,
        actions = listOf(AiAction.SAVE_PROFILE),
        intent = Intent.BEFORE_START
    )

    private fun afterOpened(profile: DeviceProfile): AiAnswer = AiAnswer(
        text = "电视上开关打开后，按顺序做：\n" +
            "1. 在电视「关于 / 网络信息」里记下 IP（形如 192.168.1.23）；\n" +
            "2. 手机连同一个 Wi-Fi，回到首页点「搜索附近的电视」；\n" +
            "3. 搜到后点连接，这时电视会弹「允许 USB 调试吗」，用遥控器选「允许」并勾选不再询问；\n" +
            "4. 连上后 APP 会自动读品牌、型号、系统版本，用来校正档案。\n\n" +
            "连不上不代表你没开对：扫描不到有可能是端口没开、网络被隔离，或者电视需要重新确认授权。",
        confidence = AnswerConfidence.GENERAL,
        risk = RiskLevel.LOW,
        actions = listOf(AiAction.OPEN_SCAN),
        intent = Intent.AFTER_OPENED
    )

    private fun connectFailed(profile: DeviceProfile): AiAnswer = AiAnswer(
        text = "按这个顺序排查，别一次改好几项：\n" +
            "1. 电视上的 ADB 开关是否还开着（有些电视重启后自动关闭）；\n" +
            "2. 手机和电视是否同一网段（手机 IP 和电视 IP 前三段要一样）；\n" +
            "3. 电视 IP 是否变了——路由器重新分配过地址就会变；\n" +
            "4. 路由器是否开了「访客网络」或「AP 隔离」，会禁止设备互访；\n" +
            "5. 电视是 Android 11 以上？那可能要「无线调试 + 配对码」，不是传统的 5555 端口。\n\n" +
            "重要：扫描不到不等于 ADB 没开。可能是端口没开放、被防火墙拦，或者响应超时。" +
            "要确认得用「全端口扫描」看端口状态（这个功能在设计里，还没做）。",
        confidence = AnswerConfidence.GENERAL,
        risk = RiskLevel.LOW,
        actions = listOf(AiAction.OPEN_SCAN),
        intent = Intent.CONNECT_FAILED
    )

    private fun risk(): AiAnswer = AiAnswer(
        text = "这个 APP 的边界，说清楚：\n" +
            "· 不需要 root，不刷机，不改系统分区，不改 build.prop；\n" +
            "· 卸载只对第三方应用开放，系统预装应用禁止卸载和禁用；\n" +
            "· 禁用是可恢复的，卸载前会让你确认；\n" +
            "· 所有操作都是你自己点的，APP 不会在后台偷偷执行命令。\n\n" +
            "但风险不是零：禁用错应用可能导致某个功能异常，卸载第三方应用会丢它的数据。" +
            "第一次建议只做只读操作（看信息、列应用），熟悉了再动。",
        confidence = AnswerConfidence.GENERAL,
        risk = RiskLevel.LOW,
        intent = Intent.RISK_CHECK
    )

    private fun whatIsAdb(): AiAnswer = AiAnswer(
        text = "ADB 是安卓的调试通道，电视上打开它，手机就能像接电脑一样给电视装应用、查看已装应用。\n\n" +
            "它不是 root，也不改系统；关掉开关就断开。装第三方桌面、卸载自己装的应用都靠它。\n\n" +
            "麻烦的地方在于：每个品牌的开关位置都不一样，而且默认是关的，必须你在电视上手动打开。",
        confidence = AnswerConfidence.GENERAL,
        risk = RiskLevel.LOW,
        intent = Intent.WHAT_IS_ADB
    )

    private fun unknown(profile: DeviceProfile): AiAnswer {
        val hint = if (profile.isComplete) {
            "你现在保存的是 ${profile.brandName} ${profile.model ?: ""}。"
        } else "还没填电视品牌。"
        return AiAnswer(
            text = "这句话我没听懂。$hint\n\n" +
                "可以这样问：\n" +
                "· 我的电视型号在哪看\n" +
                "· 小米电视怎么开 ADB\n" +
                "· 我按教程开了，下一步做什么\n" +
                "· 连不上电视怎么办\n" +
                "· 会不会把电视弄坏",
            confidence = AnswerConfidence.GENERAL,
            risk = RiskLevel.LOW,
            actions = listOf(AiAction.SAVE_PROFILE),
            intent = Intent.UNKNOWN
        )
    }
}
