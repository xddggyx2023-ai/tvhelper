package com.tztvhelper.app.core.kb

/**
 * 方法匹配：型号+系统版本 → 型号 → 品牌通用 → 没有。
 *
 * 三条硬规则：
 * 1. REJECTED 永不参与匹配（历史记录仍在库里，但不展示给普通用户）。
 * 2. 型号不同的方法绝不互相顶替（小米 4A 的方法不能给 4C 用）。
 * 3. 方法声明了系统范围且用户版本不在范围内，直接判为不适用，而不是降级凑合。
 */
object MatchPicker {

    data class Pick(val method: Method, val level: MatchLevel, val score: Int)

    fun pick(candidates: List<Method>, requestedModelId: String?, osVersion: String?): Pick? {
        val scored = candidates
            .filter { it.status != MethodStatus.REJECTED }
            .mapNotNull { m -> score(m, requestedModelId, osVersion)?.let { m to it } }
        if (scored.isEmpty()) return null

        val bestScore = scored.maxOf { it.second }
        val tier = scored.filter { it.second == bestScore }.map { it.first }
        val chosen = MethodRules.pickBest(tier) ?: return null

        val level = when {
            bestScore >= SCORE_MODEL_OS -> MatchLevel.EXACT_MODEL_OS
            bestScore >= SCORE_MODEL -> MatchLevel.MODEL
            else -> MatchLevel.BRAND_GENERIC
        }
        return Pick(chosen, level, bestScore)
    }

    /** null = 不适用（不是 0 分候选） */
    fun score(m: Method, requestedModelId: String?, osVersion: String?): Int? {
        if (m.status == MethodStatus.REJECTED) return null

        if (m.modelId == null) {
            if (m.scope != "brand") return null
            if (osVersion != null && !osCompatible(m, osVersion)) return null
            return SCORE_BRAND
        }

        if (requestedModelId == null || m.modelId != requestedModelId) return null
        if (osVersion != null) {
            if (!osCompatible(m, osVersion)) return null
            return if (m.osMin != null || m.osMax != null) SCORE_MODEL_OS else SCORE_MODEL
        }
        return SCORE_MODEL
    }

    fun osCompatible(m: Method, osVersion: String): Boolean {
        val v = parseVersion(osVersion) ?: return true // 解析不了就不筛，避免误杀
        val min = m.osMin?.let { parseVersion(it) }
        val max = m.osMax?.let { parseVersion(it) }
        if (min != null && v < min) return false
        if (max != null && v > max) return false
        return true
    }

    /** 从 "8.1"、"Android 9"、"9.0.1" 里取第一个版本号 */
    fun parseVersion(text: String?): Double? {
        if (text.isNullOrBlank()) return null
        val m = Regex("\\d+(\\.\\d+)?").find(text) ?: return null
        return m.value.toDoubleOrNull()
    }

    const val SCORE_MODEL_OS = 100
    const val SCORE_MODEL = 80
    const val SCORE_BRAND = 40
}

/**
 * 用户手输的型号 → 知识库里的型号 ID。
 * 不做模糊猜测：只有唯一命中才返回，多义或没有就返回 null（界面据此说"品牌通用"）。
 */
object ModelMatcher {

    fun normalize(s: String?): String =
        (s ?: "").lowercase().replace(Regex("[\\s\\-_/()（）]+"), "")

    fun match(models: List<TvModel>, input: String?, brandName: String?): TvModel? {
        val q = normalize(input)
        if (q.isEmpty() || models.isEmpty()) return null

        models.firstOrNull { normalize(it.model) == q || normalize(it.displayName) == q }?.let { return it }

        // 用户常带品牌名写："小米4A" → 去掉品牌再比
        val brand = normalize(brandName)
        if (brand.isNotEmpty() && q.startsWith(brand)) {
            val rest = q.removePrefix(brand)
            models.firstOrNull { normalize(it.model) == rest || normalize(it.displayName) == rest }
                ?.let { return it }
        }

        // 最后才允许包含匹配，且必须唯一，避免 4A/4C 这类串台
        val loose = models.filter {
            val nm = normalize(it.model)
            val nd = normalize(it.displayName)
            nm.length >= 3 && (q.contains(nm) || nd.length >= 3 && q.contains(nd))
        }
        return loose.singleOrNull()
    }
}
