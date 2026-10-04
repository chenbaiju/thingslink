package com.things.link.project.domain;

/**
 * 订单状态（S14-3a）。
 *
 * <p>状态机是单向的：{@code CREATED → PAID} 或 {@code CREATED → CANCELLED}，两者都是终态。
 * 已支付订单不得再支付、不得取消；已取消订单不得支付。数据库侧由
 * {@code sys_tenant_order_status_ck} 与 {@code sys_tenant_order_event_ck} /
 * {@code sys_tenant_order_paid_ck} 共同守卫：只有 PAID 才允许有支付事件与支付时刻。
 */
public enum TenantOrderStatus {

    /** 已下单未支付：可以支付，也可以取消。 */
    CREATED,

    /** 支付成功：幂等生效完成，携带渠道支付事件 ID 与支付时刻。 */
    PAID,

    /** 已取消：不可再支付；保留行以留下「这笔订单没有生效」的历史。 */
    CANCELLED
}
