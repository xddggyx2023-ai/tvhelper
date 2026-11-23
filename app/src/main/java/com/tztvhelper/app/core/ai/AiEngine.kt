package com.tztvhelper.app.core.ai

import com.tztvhelper.app.core.kb.MatchResult
import com.tztvhelper.app.core.kb.SourceRef
import com.tztvhelper.app.core.kb.TutorialStep

/** 用户电视档案。引导用户填/确认，不靠猜。 */
data class DeviceProfile(
    val brandId: String? = null,
    val brandName: String? = null,
    val model: String? = null,
    val osVersion: String? = null,
    val adbOpened: Boolean = false
) {
    val isComplete: Boolean get() = !brandId.isNullOrBlank()
}

enum class Intent {
    IDENTIFY_DEVICE,   // 我的电视是什么型号 / 在哪看
    FIND_TUTORIAL,     // 怎么开 ADB / 要教程
    STEP_HELP,         // 菜单里找不到这一步
    BEFORE_START,      // 开之前要准备什么
    AFTER_OPENED,      // 开好了，下一步
    CONNECT_FAILED,    // 连不上 / 搜不到
    RISK_CHECK,        // 安全吗 / 会不会坏
    WHAT_IS_ADB,       // ADB 是什么
    UNKNOWN
}

/** 回答依据的强度，界面必须显示，不能把"没查到"说成"已确认" */
enum class AnswerConfidence {
    KB_RECOMMENDED,   // 知识库里已验证成功的方法
    KB_CANDIDATE,     // 知识库里的候选方法（未验证）
    KB_NEEDS_REVIEW,  // 曾有成功、后来失败
    NO_KB,            // 知识库没有
    GENERAL           // 通用说明，不针对具体型号
}

enum class RiskLevel { LOW, MEDIUM, HIGH }

enum class AiAction { OPEN_GUIDE, OPEN_SEARCH, OPEN_SCAN, SAVE_PROFILE, NONE }

data class AiAnswer(
    val text: String,
    val steps: List<TutorialStep> = emptyList(),
    val sources: List<SourceRef> = emptyList(),
    val risk: RiskLevel = RiskLevel.LOW,
    val confidence: AnswerConfidence = AnswerConfidence.GENERAL,
    val actions: List<AiAction> = emptyList(),
    val intent: Intent = Intent.UNKNOWN,
    /** 回答里是否用到了模拟搜索数据（必须让用户看见） */
    val usedMockData: Boolean = false,
    val methodId: String? = null,
    val methodTitle: String? = null
)

/** 让规则引擎能查到知识库，又不用依赖 Android。测试里可以塞假数据。 */
interface TutorialLookup {
    fun find(brandId: String?, modelId: String?, osVersion: String?): MatchResult
}

interface AiEngine {
    /** 纯本地：规则 + 知识库，不联网 */
    fun answer(input: String, profile: DeviceProfile): AiAnswer
}

/** 预留：以后接云端大模型时实现这个接口，界面和规则引擎都不用改。 */
interface RemoteAiClient {
    suspend fun ask(input: String, profile: DeviceProfile, context: List<AiAnswer>): AiAnswer
}
