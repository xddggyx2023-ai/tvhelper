package com.tztvhelper.app.core.ai

/** 纯关键词规则，不做假装"理解"的事。看不懂就返回 UNKNOWN，让用户换个说法。 */
object IntentClassifier {

    private val rules: List<Pair<Intent, List<String>>> = listOf(
        Intent.AFTER_OPENED to listOf(
            "开好了", "打开了", "已经开了", "开完了", "开完了adb", "下一步", "然后呢", "接下来做什么", "开好之后"
        ),
        Intent.CONNECT_FAILED to listOf(
            "连不上", "连接不上", "连接失败", "搜不到", "扫描不到", "找不到设备", "没反应", "超时", "拒绝连接"
        ),
        Intent.RISK_CHECK to listOf(
            "安全吗", "有风险", "会不会坏", "会不会弄坏", "弄坏", "坏掉", "变砖", "保修", "危险",
            "敢不敢", "会不会出问题", "影响系统"
        ),
        Intent.WHAT_IS_ADB to listOf(
            "adb是什么", "什么是adb", "adb是啥", "adb干什么", "adb有什么用", "为什么要开adb"
        ),
        Intent.STEP_HELP to listOf(
            "找不到", "没有这个", "没有那一项", "菜单里没有", "没有看到", "没看到", "在哪一项", "没有那个选项"
        ),
        Intent.BEFORE_START to listOf(
            "准备什么", "需要准备", "开始之前", "要注意什么", "前提", "要先做什么"
        ),
        Intent.IDENTIFY_DEVICE to listOf(
            "什么型号", "型号在哪", "在哪看型号", "怎么看型号", "设备信息", "系统版本在哪", "我的电视是", "查型号"
        ),
        Intent.FIND_TUTORIAL to listOf(
            "怎么开", "如何开", "开启", "教程", "步骤", "打开adb", "开adb", "adb打开"
        )
    )

    fun classify(input: String): Intent {
        val t = input.lowercase().replace(Regex("\\s+"), "")
        for ((intent, keys) in rules) {
            if (keys.any { t.contains(it) }) return intent
        }
        return if (t.contains("adb")) Intent.FIND_TUTORIAL else Intent.UNKNOWN
    }
}
