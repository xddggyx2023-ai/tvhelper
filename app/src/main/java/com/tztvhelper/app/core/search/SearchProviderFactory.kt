package com.tztvhelper.app.core.search

import android.content.Context
import com.tztvhelper.app.BuildConfig
import com.tztvhelper.app.core.AppConfig

/**
 * 决定这一版用哪种搜索源。规则很简单，但每条都要在界面上说清楚：
 * - 配置了服务器地址 → 真实联网（HttpSearchProvider）
 * - 没配 + Debug 包 → 本地模拟数据（MockSearchProvider，界面必须标"模拟"）
 * - 没配 + Release 包 → 明确返回"未配置"，不假装能搜
 */
object SearchProviderFactory {

    fun create(ctx: Context): SearchProvider {
        val base = AppConfig.searchBaseUrl(ctx)
        if (base.isNotBlank()) {
            return HttpSearchProvider(base, AppConfig.searchToken(ctx))
        }
        return if (BuildConfig.DEBUG) {
            MockSearchProvider {
                ctx.assets.open("kb/mock-search.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
            }
        } else {
            DisabledSearchProvider()
        }
    }

    fun describe(ctx: Context): String = when {
        AppConfig.searchBaseUrl(ctx).isNotBlank() -> "已配置搜索服务：${AppConfig.searchBaseUrl(ctx)}"
        BuildConfig.DEBUG -> "未配置服务器，当前使用本地模拟搜索数据（仅供流程验证，不是真实结果）"
        else -> "未配置搜索服务：当前只能查本地知识库"
    }
}
