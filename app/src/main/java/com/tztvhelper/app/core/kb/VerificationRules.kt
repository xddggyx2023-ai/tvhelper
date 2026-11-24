package com.tztvhelper.app.core.kb

/**
 * 「APP 实测成功」必须真的有连接依据，不能由界面按钮自己宣布。
 *
 * 判断依据只来自 [ConnectionOracle]：会话是否真的建立、设备信息是否读到，
 * 以及**连上的这台电视**是不是用户正在看的那条方法所属的品牌/型号。
 * 用户口头确认只能记为 SUCCESS_USER。
 */
interface ConnectionOracle {
    fun isConnected(): Boolean
    fun deviceModel(): String?
    fun deviceBrand(): String?
    fun deviceRelease(): String?
}

object VerificationRules {

    /** 用户跳过原因填写时的固定文案，不能留空 */
    const val NO_REASON = "未提供原因"

    /**
     * SUCCESS_APP 的完整校验。返回 null 表示允许，否则是拒绝原因。
     *
     * 三道关：真的连着 → 读得到设备信息 → 设备和方法的品牌/型号对得上。
     * 第三关很关键：连着小米电视时，不能给「海信」的方法记 APP 实测成功。
     */
    fun successProblem(
        oracle: ConnectionOracle,
        method: Method,
        brand: Brand,
        model: TvModel?
    ): String? {
        if (!oracle.isConnected()) return "APP 还没有连上电视。"
        val deviceModel = oracle.deviceModel()
        if (deviceModel.isNullOrBlank()) return "连上了，但读不到电视型号。"
        val deviceBrand = oracle.deviceBrand()
        if (deviceBrand.isNullOrBlank()) return "连上了，但读不到电视品牌。"

        if (!matchesBrand(deviceBrand, brand)) {
            return "现在连的是「$deviceBrand」，不是「${brand.name}」，不能算这条方法的实测成功。"
        }
        if (method.modelId != null) {
            val key = model?.model?.takeIf { it.isNotBlank() } ?: method.modelId
            if (!matchesModel(deviceModel, key)) {
                return "现在连的是「$deviceModel」，不是「$key」，不能算这条方法的实测成功。"
            }
        }
        return null
    }

    /**
     * 只有通过 [successProblem] 才允许 SUCCESS_APP。
     * 其它结果一律放行（用户反馈本来就不需要连接）。
     */
    fun effectiveResult(
        requested: VerifyResult,
        oracle: ConnectionOracle,
        method: Method,
        brand: Brand,
        model: TvModel?
    ): VerifyResult? = when {
        requested != VerifyResult.SUCCESS_APP -> requested
        successProblem(oracle, method, brand, model) == null -> requested
        else -> null
    }

    fun normalizeReason(text: String?): String =
        text?.trim()?.takeIf { it.isNotBlank() } ?: NO_REASON

    /**
     * 品牌比对：库里的 id、中文名、别名，任意一个能在电视上报的品牌字符串里找到就算。
     * 例：deviceBrand = "Xiaomi"，别名 "Xiaomi" 命中。
     */
    fun matchesBrand(deviceBrand: String, brand: Brand): Boolean {
        val d = normalizeKey(deviceBrand)
        if (d.isEmpty()) return false
        return (listOf(brand.id, brand.name) + brand.aliases).any { key ->
            val k = normalizeKey(key)
            k.isNotEmpty() && (d == k || d.contains(k) || k.contains(d))
        }
    }

    /**
     * 型号比对：电视报的型号通常带前缀（MiTV4A / L43M5-4A），所以用包含关系。
     * 比较前去掉分隔符，KD-55X8500G 与 55X8500G 能对上。
     */
    fun matchesModel(deviceModel: String, modelKey: String): Boolean {
        val d = normalizeKey(deviceModel)
        val k = normalizeKey(modelKey)
        if (d.isEmpty() || k.isEmpty()) return false
        if (d == k) return true
        if (k.length < 2) return false
        return d.contains(k) || k.contains(d)
    }

    private fun normalizeKey(s: String): String =
        s.lowercase().replace(Regex("[^a-z0-9\\u4e00-\\u9fa5]+"), "")

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
