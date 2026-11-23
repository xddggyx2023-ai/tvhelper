package com.tztvhelper.app.core.kb

/**
 * 「APP 实测成功」必须真的有连接依据，不能由界面按钮自己宣布。
 *
 * 判断依据只来自 [ConnectionOracle]：会话是否真的建立、设备信息是否读到。
 * 用户口头确认只能记为 SUCCESS_USER。
 */
interface ConnectionOracle {
    fun isConnected(): Boolean
    fun deviceModel(): String?
}

object VerificationRules {

    /** 用户跳过原因填写时的固定文案，不能留空 */
    const val NO_REASON = "未提供原因"

    /**
     * 只有「真的连着 + 读到了设备信息」才允许 SUCCESS_APP。
     * 其它结果一律放行（用户反馈本来就不需要连接）。
     */
    fun effectiveResult(requested: VerifyResult, oracle: ConnectionOracle): VerifyResult? =
        when {
            requested != VerifyResult.SUCCESS_APP -> requested
            oracle.isConnected() && !oracle.deviceModel().isNullOrBlank() -> requested
            else -> null
        }

    fun normalizeReason(text: String?): String =
        text?.trim()?.takeIf { it.isNotBlank() } ?: NO_REASON

    /** 变更历史的文字，原因必出现在里面（没有就写"未提供原因"） */
    fun historyDetail(
        result: VerifyResult,
        osVersion: String?,
        deviceModel: String?,
        reason: String?,
        successCount: Int,
        failCount: Int
    ): String = buildString {
        append("结果=").append(result.name)
        osVersion?.takeIf { it.isNotBlank() }?.let { append("，系统=").append(it) }
        deviceModel?.takeIf { it.isNotBlank() }?.let { append("，机型=").append(it) }
        if (result == VerifyResult.FAILURE) append("，原因=").append(normalizeReason(reason))
        append("；累计成功=").append(successCount).append("，失败=").append(failCount)
    }
}
