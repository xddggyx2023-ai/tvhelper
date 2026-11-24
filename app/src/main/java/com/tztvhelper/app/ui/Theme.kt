package com.tztvhelper.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 全应用唯一的颜色/字号/间距来源。
 * 三个界面都从这里取，不再各自定义一套。
 */
object Tv {
    val bg = Color(0xFF0B1220)
    val card = Color(0xFF16213A)
    val cardHi = Color(0xFF1D2A45)
    val accent = Color(0xFF4DA3FF)
    val good = Color(0xFF7CF0C0)
    val warn = Color(0xFFFFB86B)
    val bad = Color(0xFFFF7B7B)
    val dim = Color(0xFFA9B6CC)
    val dimmer = Color(0xFF8794AB)
    val line = Color(0xFF22304D)
    val lineStrong = Color(0xFF3B4B6B)
    val badBg = Color(0xFF6B3B3B)
    val bright = Color(0xFFD5DEEE)
    val onAccent = Color(0xFF06101F)
}

/** 字号下限：正文 15sp、次要说明 13sp、最小 12sp（不再出现 11sp） */
object TvText {
    val hero = 26.sp
    val title = 20.sp
    val body = 15.sp
    val bodySmall = 14.sp
    val small = 13.sp
    val tiny = 12.sp
}

object TvSpace {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 20.dp
    val touch = 48.dp
}
