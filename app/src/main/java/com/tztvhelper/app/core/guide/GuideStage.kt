package com.tztvhelper.app.core.guide

/**
 * 引导流程的阶段。界面只根据它决定「现在该显示什么、该问用户什么」。
 * 完整状态机（按钮怎么转移、排查分支、进度保存）在 GuideFlow.kt。
 */
enum class GuideStage {
    IDLE,          // 还没开始
    STEP,          // 正在逐步引导
    TROUBLESHOOT,  // 用户说没找到 / 不一样，进入排查分支
    PORT_CHECK,    // 教程走完，正在检测 5555 端口
    CONNECT,       // 正在连接电视（等遥控器点允许）
    DONE           // 已连上
}

/** 排查分支：每个分支的文案是固定的，不允许现场编 */
enum class TroubleshootBranch {
    NO_DEVELOPER_OPTIONS, // 菜单里没有开发者选项
    OPTION_GREYED,        // 开发者选项是灰的
    NO_ADB_SWITCH,        // 电视没有 ADB 开关
    NO_AUTH_DIALOG        // 连接时电视没有弹授权窗口
}
