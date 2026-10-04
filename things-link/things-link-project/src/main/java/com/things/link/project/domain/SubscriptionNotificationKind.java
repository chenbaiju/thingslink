package com.things.link.project.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * P4 冻结的五个客户通知时间点（S14-3c）。
 *
 * <p>一律以 {@link Duration}（固定时长，UTC 精确时刻）从服务期事实推导，不使用带日历语义的
 * {@code Period}/时区换算：{@code graceEndsAt = endsAt + 14 × 24h}，因此「宽限第 7 天」是
 * {@code endsAt + 7 × 24h}、「宽限结束前 1 天」是 {@code endsAt + 13 × 24h}，与订阅表上的
 * {@code interval '336 hours'} 约束逐微秒一致。
 *
 * <p>每个时间点在 {@code (tenant_id, kind, period_ends_at)} 上至多一条意图；同一 kind 在续费后的
 * 新服务期上可以再发一次（period_ends_at 不同）。
 */
public enum SubscriptionNotificationKind {

    /** 到期前 7 天。 */
    EXPIRY_MINUS_7_DAYS("到期前 7 天"),
    /** 到期日（= 服务期终点）。 */
    EXPIRY_DAY("到期日"),
    /** 宽限第 7 天。 */
    GRACE_DAY_7("宽限第 7 天"),
    /** 宽限结束前 1 天。 */
    GRACE_END_MINUS_1_DAY("宽限结束前 1 天"),
    /** 切入 {@code RESTRICTED_FREE} 之后即时。 */
    RESTRICTED_SWITCH("切换受限免费后即时");

    /** 业务可读的中文时间点说明，只用于审计详情与运维核对。 */
    private final String description;

    SubscriptionNotificationKind(String description) {
        this.description = description;
    }

    /** @return 中文时间点说明 */
    public String description() {
        return description;
    }

    /**
     * 计算该时间点在给定订阅上的应发时刻。
     *
     * @param state 订阅生命周期事实
     * @return 应发时刻；该时间点在本订阅上尚不可推导时（例如 RESTRICTED_SWITCH 但还没切换）返回 {@code null}
     * @throws IllegalArgumentException 订阅没有服务期终点（长期 FREE 不应进入通知计算）
     */
    public Instant fireAt(SubscriptionLifecycleState state) {
        Instant endsAt = state.endsAt();
        if (endsAt == null) {
            throw new IllegalArgumentException("长期 FREE 没有服务期终点，不参与到期通知: " + state.id());
        }
        return switch (this) {
            case EXPIRY_MINUS_7_DAYS -> endsAt.minus(Duration.ofDays(7));
            case EXPIRY_DAY -> endsAt;
            case GRACE_DAY_7 -> endsAt.plus(Duration.ofDays(7));
            case GRACE_END_MINUS_1_DAY -> endsAt.plus(Duration.ofDays(13));
            case RESTRICTED_SWITCH -> state.restrictedAt();
        };
    }
}
