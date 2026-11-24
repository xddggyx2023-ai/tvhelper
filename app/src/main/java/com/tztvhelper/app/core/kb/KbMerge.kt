package com.tztvhelper.app.core.kb

/**
 * 内置知识库的增量合并规则（纯函数，方便单测）。
 *
 * 底线：升级只允许「新增缺失条目」，绝不覆盖库里已有的方法。
 * 已有的状态（推荐/待复核/否决）、成功失败次数、验证记录、变更历史都属于用户数据，
 * 不能被一次版本升级冲掉。
 */
object KbMerge {

    fun newMethods(existingIds: Set<String>, incoming: List<Method>): List<Method> =
        incoming.filter { it.id !in existingIds }

    fun newModels(existingIds: Set<String>, incoming: List<TvModel>): List<TvModel> =
        incoming.filter { it.id !in existingIds }
}
