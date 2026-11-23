package com.tztvhelper.app.core

import android.content.Context
import com.tztvhelper.app.BuildConfig

/**
 * 可配置项集中在这里。服务器地址不写死在代码常量里：
 * 1) 构建时 BuildConfig.SEARCH_BASE_URL 给默认值（可为空）
 * 2) 安装后可被本地设置覆盖（以后加"高级设置"页直接用这两个 setter）
 */
object AppConfig {

    private const val PREF = "app_config"
    private const val K_BASE = "search_base_url"
    private const val K_TOKEN = "search_token"
    private const val K_TTL = "search_ttl_ms"

    private const val DEFAULT_TTL = 7L * 24 * 60 * 60 * 1000

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun searchBaseUrl(ctx: Context): String =
        prefs(ctx).getString(K_BASE, "").orEmpty().ifBlank { BuildConfig.SEARCH_BASE_URL }

    fun setSearchBaseUrl(ctx: Context, url: String) =
        prefs(ctx).edit().putString(K_BASE, url.trim()).apply()

    fun searchToken(ctx: Context): String = prefs(ctx).getString(K_TOKEN, "").orEmpty()

    fun setSearchToken(ctx: Context, token: String) =
        prefs(ctx).edit().putString(K_TOKEN, token.trim()).apply()

    fun searchCacheTtlMs(ctx: Context): Long = prefs(ctx).getLong(K_TTL, DEFAULT_TTL)

    fun setSearchCacheTtlMs(ctx: Context, ttlMs: Long) =
        prefs(ctx).edit().putLong(K_TTL, ttlMs.coerceAtLeast(0)).apply()
}
