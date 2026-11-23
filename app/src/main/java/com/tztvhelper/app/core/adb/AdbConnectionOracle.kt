package com.tztvhelper.app.core.adb

import com.tztvhelper.app.core.kb.ConnectionOracle

/**
 * 真实的连接状态来源。只有 Kadb 会话建立、且设备信息读取成功，才算"连着"。
 * GuideActivity 的按钮和 KbRepository 的校验都读它——不是界面自己说了算。
 */
object AdbConnectionOracle : ConnectionOracle {

    override fun isConnected(): Boolean = Adb.isConnected() && Adb.info != null

    override fun deviceModel(): String? = Adb.info?.model
}
