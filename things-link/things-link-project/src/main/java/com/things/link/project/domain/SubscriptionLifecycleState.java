package com.things.link.project.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 一条订阅在生命周期推进中需要读到的全部事实（S14-3c）。
 *
 * <p>{@link ActiveSubscription} 只够回答「首次购买还是续费」；本记录补上状态、宽限终点与受限时刻，
 * 让时间推进扫描、通知意图计算与「租户是否已续费」的判定都能从同一行读出，不产生第二个读取口径。
 *
 * @param id 订阅行 ID
 * @param tenantId 归属租户
 * @param planRevisionId 订阅当前锁定的产品修订版
 * @param status 订阅状态
 * @param startsAt 服务期起始时刻
 * @param endsAt 服务期结束时刻；{@code null} 仅零价长期 FREE
 * @param graceEndsAt 宽限结束时刻；仅 GRACE/RESTRICTED_FREE 非空
 * @param restrictedAt 切入 RESTRICTED_FREE 的时刻；仅 RESTRICTED_FREE 非空
 */
public record SubscriptionLifecycleState(
        UUID id,
        UUID tenantId,
        UUID planRevisionId,
        SubscriptionStatus status,
        Instant startsAt,
        Instant endsAt,
        Instant graceEndsAt,
        Instant restrictedAt) {

    /** 生命周期事实必须有身份与归属，长期 FREE 也必须给出服务期起点。 */
    public SubscriptionLifecycleState {
        Objects.requireNonNull(id, "订阅 ID 不得为空");
        Objects.requireNonNull(tenantId, "归属租户 ID 不得为空");
        Objects.requireNonNull(planRevisionId, "订阅产品修订版 ID 不得为空");
        Objects.requireNonNull(status, "订阅状态不得为空");
        Objects.requireNonNull(startsAt, "服务期起始时刻不得为空");
    }

    /**
     * 是否为「无终点」的长期 FREE 订阅（零价、无计费周期）。
     *
     * @return {@code endsAt == null} 时为 {@code true}；时间推进必须原样跳过它
     */
    public boolean perpetual() {
        return endsAt == null;
    }

    /**
     * 是否仍占用「当前生效订阅」这一身份（时间推进只处理这三种状态）。
     *
     * @return 状态为 ACTIVE/GRACE/RESTRICTED_FREE 时为 {@code true}
     */
    public boolean current() {
        return status == SubscriptionStatus.ACTIVE
                || status == SubscriptionStatus.GRACE
                || status == SubscriptionStatus.RESTRICTED_FREE;
    }
}
