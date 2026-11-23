package com.tztvhelper.app.core.kb

/**
 * 方法生命周期规则。全部是纯函数，方便单测——
 * "新搜索结果不能覆盖已推荐方法"这条底线就靠这里守住。
 */
object MethodRules {

    /** 用户口头反馈需要连续成功几次才够升为推荐 */
    const val USER_SUCCESSES_TO_PROMOTE = 2

    /**
     * 记录一次验证后的新状态。
     * 规则：
     * - APP 实测成功：直接升为推荐（最强证据），成功次数 +1
     * - 用户反馈成功：累计到阈值才升为推荐，否则留在候选
     * - 失败：失败次数 +1；如果原本是推荐，降为"待复核"，但**成功次数不清零**
     */
    fun afterVerification(method: Method, result: VerifyResult, now: Long): Method {
        val newSuccess = method.successCount + if (result == VerifyResult.FAILURE) 0 else 1
        val newFail = method.failCount + if (result == VerifyResult.FAILURE) 1 else 0

        val status = when (result) {
            VerifyResult.SUCCESS_APP -> MethodStatus.RECOMMENDED
            VerifyResult.SUCCESS_USER ->
                if (newSuccess >= USER_SUCCESSES_TO_PROMOTE) MethodStatus.RECOMMENDED else method.status
            VerifyResult.FAILURE ->
                if (method.status == MethodStatus.RECOMMENDED) MethodStatus.NEEDS_REVIEW else method.status
        }

        return method.copy(
            status = status,
            successCount = newSuccess,
            failCount = newFail,
            lastVerifiedAt = now
        )
    }

    /**
     * 新搜到的方法入库时的状态：永远是候选。
     * 即使它来自官网，也只是"可信度高的候选"，不能顶掉已推荐的方法。
     */
    fun newCandidate(method: Method): Method = method.copy(
        status = MethodStatus.CANDIDATE,
        successCount = 0,
        failCount = 0,
        lastVerifiedAt = null
    )

    /**
     * 同一(品牌,型号)下选出要展示的方法：
     * 推荐 > 待复核 > 候选；同档按成功次数降序。
     */
    fun pickBest(candidates: List<Method>): Method? =
        candidates.maxWithOrNull(
            compareBy<Method> { rank(it.status) }
                .thenBy { it.successCount }
                .thenBy { -it.failCount }
        )

    private fun rank(s: MethodStatus): Int = when (s) {
        MethodStatus.RECOMMENDED -> 4
        MethodStatus.NEEDS_REVIEW -> 3
        MethodStatus.CANDIDATE -> 2
        MethodStatus.REJECTED -> 1
    }

    /**
     * 匹配评分：型号+系统版本 > 仅型号 > 品牌通用 > 不匹配。
     * 版本比较用"包含"而不是精确相等，因为用户填的常是 8.1、9、10 这种。
     */
    fun score(method: Method, modelId: String?, osVersion: String?): Int = when {
        method.modelId != null && modelId != null && method.modelId == modelId -> {
            if (osVersion != null && matchesOs(method, osVersion)) 100 else 80
        }
        method.modelId == null && method.scope == "brand" -> 40
        else -> 0
    }

    private fun matchesOs(m: Method, osVersion: String): Boolean {
        val min = m.osMin?.toDoubleOrNull()
        val max = m.osMax?.toDoubleOrNull()
        val v = osVersion.toDoubleOrNull() ?: return true // 解析不了就不筛，避免误杀
        if (min != null && v < min) return false
        if (max != null && v > max) return false
        return true
    }
}
