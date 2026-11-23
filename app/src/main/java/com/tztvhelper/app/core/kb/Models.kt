package com.tztvhelper.app.core.kb

/** 方法状态：新搜到的先当候选，验证过才升为推荐 */
enum class MethodStatus {
    CANDIDATE,     // 候选：搜索/反馈得到，尚未证实
    RECOMMENDED,   // 推荐：有成功记录，优先展示
    NEEDS_REVIEW,  // 曾有成功记录，但后来出现失败，需要复核
    REJECTED       // 人工判定不可用
}

/** 资料可信度，界面上要如实展示，不许含糊 */
enum class Credibility {
    OFFICIAL,     // 厂商官方文档/官网
    FORUM,        // 论坛、教程站
    USER_REPORT,   // 用户反馈
    UNVERIFIED    // 来源不明
}

enum class SourceType { OFFICIAL, FORUM, USER, UNKNOWN }

enum class VerifyResult {
    SUCCESS_APP,   // APP 实测连接成功（最强证据）
    SUCCESS_USER,  // 用户口头反馈成功
    FAILURE        // 失败
}

data class Brand(
    val id: String,
    val name: String,
    val aliases: List<String> = emptyList()
)

data class TvModel(
    val id: String,
    val brandId: String,
    val model: String,
    val displayName: String,
    val androidVersions: List<String> = emptyList()
)

data class TutorialStep(
    val n: Int,
    val title: String,
    val detail: String,
    val path: List<String> = emptyList(),
    val note: String = ""
)

data class SourceRef(
    val type: SourceType,
    val url: String,
    val title: String,
    val collectedAt: Long,
    val credibility: Credibility
)

data class Method(
    val id: String,
    val brandId: String,
    val modelId: String?,
    val title: String,
    val scope: String,              // "brand" | "model"
    val osMin: String? = null,
    val osMax: String? = null,
    val steps: List<TutorialStep> = emptyList(),
    val notes: String = "",
    val status: MethodStatus = MethodStatus.CANDIDATE,
    val confidence: Credibility = Credibility.UNVERIFIED,
    val successCount: Int = 0,
    val failCount: Int = 0,
    val lastVerifiedAt: Long? = null,
    val sources: List<SourceRef> = emptyList(),
    val updatedAt: Long = 0L
)

enum class MatchLevel { EXACT_MODEL_OS, MODEL, BRAND_GENERIC }

sealed interface MatchResult {
    data class Hit(val method: Method, val level: MatchLevel, val reason: String) : MatchResult
    data class NotFound(val brandId: String?, val modelText: String?) : MatchResult
}

/** 知识库里的一条历史动作，用于"方法变了"的追溯 */
data class MethodHistory(
    val methodId: String,
    val change: String,     // CREATED / PROMOTED / DEMOTED / FAILED / EDITED
    val detail: String,
    val createdAt: Long
)

data class Verification(
    val methodId: String,
    val result: VerifyResult,
    val osVersion: String?,
    val deviceModel: String?,
    val reason: String?,
    val createdAt: Long
)
