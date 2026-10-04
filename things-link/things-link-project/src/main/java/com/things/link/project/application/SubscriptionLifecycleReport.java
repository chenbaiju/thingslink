package com.things.link.project.application;

/**
 * 一轮订阅生命周期推进的结果计数（S14-3c）。
 *
 * <p>只承载「本轮实际改变了什么」的可观测事实，供 worker 日志、运维核对与真库用例断言使用；
 * 它不是事实来源，重跑同一时刻应当得到全 0（除数据本已处于下一状态外），这正是幂等的可观测证据。
 *
 * @param graceEntered 本轮 ACTIVE → GRACE 的订阅数
 * @param restrictedFreeEntered 本轮 GRACE → RESTRICTED_FREE 的订阅数
 * @param downgradesApplied 本轮套用的预约降级数
 * @param downgradesNotApplied 本轮因订阅不再 ACTIVE 等原因未套用并置 CANCELLED 的预约数
 * @param notificationIntentsCreated 本轮新落库的通知意图数
 * @param projectsRestricted 本轮转为商业只读的项目数
 * @param projectsRestored 本轮因续费/升级恢复为可写的项目数
 */
public record SubscriptionLifecycleReport(
        int graceEntered,
        int restrictedFreeEntered,
        int downgradesApplied,
        int downgradesNotApplied,
        int notificationIntentsCreated,
        int projectsRestricted,
        int projectsRestored) {
}
