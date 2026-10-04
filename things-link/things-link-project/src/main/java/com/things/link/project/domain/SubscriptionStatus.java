package com.things.link.project.domain;

/**
 * 订阅状态（S14-3c；取值与 {@code sys_tenant_subscription_status_ck} 逐字一致）。
 *
 * <p>状态机为 {@code ACTIVE --(endsAt)--> GRACE --(graceEndsAt)--> RESTRICTED_FREE}；
 * 续费/升级换行回到 {@code ACTIVE}，历史行以 {@code SUPERSEDED} 终态保留。
 * {@code EXPIRED}/{@code CANCELLED} 是保留历史的终态，本片的时间推进不会写它们。
 *
 * <p>P4 明确 {@code RESTRICTED_FREE} <b>不复用</b>租户 {@code SUSPENDED}：安全封禁与欠费受限
 * 是两件事，前者由租户状态表达，后者只表达「订阅已降级为受限免费」。
 */
public enum SubscriptionStatus {

    /** 生效中；{@code ends_at} 为 {@code null} 表示零价长期 FREE（时间推进永不触碰）。 */
    ACTIVE,
    /** 宽限期内：读/历史/设备连接照常，写允许但禁止新增扩大类动作。 */
    GRACE,
    /** 宽限结束自动切换到 FREE 的显式受限状态：超额项目只读，不物理删除。 */
    RESTRICTED_FREE,
    /** 服务期已结束且未续费的终态历史行（本片不写入）。 */
    EXPIRED,
    /** 人工取消的终态历史行（本片不写入）。 */
    CANCELLED,
    /** 被续费/升级取代的历史行（S14-3a 写入）。 */
    SUPERSEDED
}
