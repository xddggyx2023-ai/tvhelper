package com.tztvhelper.app.core.profile

import android.content.Context
import com.tztvhelper.app.core.ai.DeviceProfile

/** 电视档案：用户自己填/确认的，不靠网络猜。 */
class DeviceProfileStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("device_profile", Context.MODE_PRIVATE)

    fun load(): DeviceProfile = DeviceProfile(
        brandId = prefs.getString(K_BRAND_ID, null),
        brandName = prefs.getString(K_BRAND_NAME, null),
        model = prefs.getString(K_MODEL, null),
        osVersion = prefs.getString(K_OS, null),
        adbOpened = prefs.getBoolean(K_ADB, false)
    )

    fun save(p: DeviceProfile) {
        prefs.edit()
            .putString(K_BRAND_ID, p.brandId)
            .putString(K_BRAND_NAME, p.brandName)
            .putString(K_MODEL, p.model)
            .putString(K_OS, p.osVersion)
            .putBoolean(K_ADB, p.adbOpened)
            .apply()
    }

    fun clear() = prefs.edit().clear().apply()

    private companion object {
        const val K_BRAND_ID = "brand_id"
        const val K_BRAND_NAME = "brand_name"
        const val K_MODEL = "model"
        const val K_OS = "os_version"
        const val K_ADB = "adb_opened"
    }
}
