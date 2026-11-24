package com.tztvhelper.app.data

/**
 * 包名校验与危险操作拦截。
 *
 * 这里是纯逻辑（不依赖 Android），Adb 层和界面都调用它。
 * 界面藏按钮只是体验，真正的底线在这一层：即使有人绕过界面，
 * 关键应用和系统应用也禁用/卸载不了。
 */
object Pkgs {

    private val names = mapOf(
        "com.xiaomi.mitv.tvhome" to "小米电视主页",
        "com.xiaomi.mitv.appstore" to "小米应用商店",
        "com.xiaomi.mitv.shop" to "小米商城",
        "com.xiaomi.mitv.payment" to "小米支付",
        "com.xiaomi.tv.gallery" to "小米电视画报",
        "com.qiyi.video" to "爱奇艺",
        "com.qiyi.video.tv" to "银河奇异果",
        "com.tencent.qqlive" to "腾讯视频",
        "com.ktcp.video" to "酷喵影视",
        "com.youku.phone" to "优酷",
        "com.hunantv.imgo.activity" to "芒果TV",
        "tv.danmaku.bili" to "哔哩哔哩",
        "com.google.android.youtube.tv" to "YouTube TV",
        "com.android.vending" to "Google Play"
    )

    /** 包名只允许字母、数字、下划线和点；拼进 shell 命令前必须过这一关 */
    private val VALID = Regex("^[A-Za-z0-9_.]+$")

    fun label(pkg: String): String = names[pkg] ?: pkg

    fun isValidName(pkg: String): Boolean = pkg.isNotBlank() && VALID.matches(pkg) && pkg.contains('.')

    /**
     * 这些包一旦禁用，电视可能黑屏、开不了机或者没法再操作。
     * 桌面（launcher）类也在里面——禁用桌面等于电视没有主页。
     */
    fun isCritical(pkg: String): Boolean {
        val lower = pkg.lowercase()
        if (lower == "android") return true
        // 已知的电视桌面 / 系统主页（只匹配桌面本身，不能把整个厂商前缀都算进去）
        val launchers = listOf(
            "tvhome", "tvlauncher", "leanbacklauncher", "launcher",
            "com.tcl.fflauncher", "com.xiaomi.mitv.tvhome"
        )
        if (launchers.any { lower.contains(it) }) return true

        val risky = listOf(
            "systemui", "settings", "packageinstaller", "inputmethod",
            "providers.", "telephony", "bluetooth", "wifi", "keychain", "vpn",
            "shell", "permissioncontroller", "media.audio", "media.camera"
        )
        if (risky.any { lower.contains(it) }) return true
        return lower.startsWith("com.android.") && !lower.contains("chrome")
    }

    /** null = 允许；非 null = 不允许的原因（直接给用户看） */
    fun denyDisableReason(pkg: String): String? = when {
        !isValidName(pkg) -> "包名不合法，已拒绝执行。"
        isCritical(pkg) -> "「${label(pkg)}」是系统关键应用，禁用后电视可能没法操作，已拒绝。"
        else -> null
    }

    fun denyUninstallReason(pkg: String, isThirdParty: Boolean): String? = when {
        !isValidName(pkg) -> "包名不合法，已拒绝执行。"
        isCritical(pkg) -> "「${label(pkg)}」是系统关键应用，不能卸载。"
        !isThirdParty -> "只能卸载你自己装的应用；出厂预装和系统应用不提供卸载。"
        else -> null
    }
}
