package com.tztvhelper.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADB 层的最后一道闸：即使界面传错，关键应用/系统应用也不能动 */
class PkgsGuardTest {

    @Test
    fun `桌面类应用算关键应用`() {
        assertTrue(Pkgs.isCritical("com.xiaomi.mitv.tvhome"))
        assertTrue(Pkgs.isCritical("com.google.android.tvlauncher"))
        assertTrue(Pkgs.isCritical("com.android.systemui"))
    }

    @Test
    fun `厂商的普通预装应用不算关键应用`() {
        // 小米商城/画报属于可管理的预装应用，不是桌面
        assertEquals(false, Pkgs.isCritical("com.xiaomi.mitv.shop"))
        assertEquals(false, Pkgs.isCritical("com.xiaomi.tv.gallery"))
    }

    @Test
    fun `关键应用禁止禁用`() {
        assertNotNull(Pkgs.denyDisableReason("com.xiaomi.mitv.tvhome"))
        assertNotNull(Pkgs.denyDisableReason("com.android.systemui"))
    }

    @Test
    fun `普通第三方应用允许禁用`() {
        assertNull(Pkgs.denyDisableReason("com.qiyi.video"))
    }

    @Test
    fun `系统应用禁止卸载`() {
        val reason = Pkgs.denyUninstallReason("com.xiaomi.mitv.appstore", isThirdParty = false)
        assertNotNull(reason)
        assertTrue(reason!!.contains("系统"))
    }

    @Test
    fun `关键应用禁止卸载`() {
        assertNotNull(Pkgs.denyUninstallReason("com.xiaomi.mitv.tvhome", isThirdParty = true))
    }

    @Test
    fun `第三方应用允许卸载`() {
        assertNull(Pkgs.denyUninstallReason("com.qiyi.video", isThirdParty = true))
    }

    @Test
    fun `非法包名一律拒绝`() {
        listOf(
            "com.foo; rm -rf /", "com.foo bar", "com.foo\npm uninstall x",
            "-k com.foo", "com.foo|cat", "", "android"
        ).forEach { pkg ->
            assertEquals("应当拒绝：$pkg", false, Pkgs.isValidName(pkg))
            assertNotNull(Pkgs.denyDisableReason(pkg))
        }
    }

    @Test
    fun `正常包名通过校验`() {
        assertTrue(Pkgs.isValidName("com.xiaomi.mitv.tvhome"))
        assertTrue(Pkgs.isValidName("tv.danmaku.bili"))
        assertEquals(false, Pkgs.isValidName("nodot"))
    }
}
