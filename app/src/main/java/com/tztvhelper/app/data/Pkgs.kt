package com.tztvhelper.app.data

/** 让包名好懂一点：常见电视应用的中文名 */
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

    fun label(pkg: String): String = names[pkg] ?: pkg

    /** 这些包一旦禁用，电视可能黑屏、开不了机或者没法再操作 —— 直接不给按钮 */
    fun isCritical(pkg: String): Boolean {
        if (pkg == "android") return true
        val risky = listOf(
            "systemui", "settings", "launcher", "packageinstaller", "inputmethod",
            "providers.", "telephony", "bluetooth", "wifi", "keychain", "vpn",
            "shell", "permissioncontroller", "media.audio", "media.camera"
        )
        val lower = pkg.lowercase()
        if (risky.any { lower.contains(it) }) return true
        return pkg.startsWith("com.android.") && !lower.contains("chrome")
    }
}
