package com.things.link.project.domain;

/**
 * 预约降级的状态（S14-3b）。
 *
 * <p>状态机是单向的：{@code PENDING → CANCELLED}（周期结束前撤销，或被升级清除）或
 * {@code PENDING → APPLIED}（周期结束时由 S14-3c 套用），两者都是终态。
 * 撤销与套用都只推进状态、保留行，因此「客户曾经预约过、后来又撤销」是可追溯事实。
 */
public enum SubscriptionPendingChangeStatus {

    /** 待生效：下个周期开始时要切换的目标已记录。 */
    PENDING,

    /** 已撤销：客户主动撤销，或升级立即生效时被清除。 */
    CANCELLED,

    /** 已套用：S14-3c 在周期结束时完成了切换。 */
    APPLIED
}
