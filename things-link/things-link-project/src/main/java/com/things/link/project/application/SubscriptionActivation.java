package com.things.link.project.application;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 一次「支付成功事件」应用后的结果（S14-3a）。
 *
 * <p>{@code activated = true} 表示本次调用真正完成了订阅生效（关旧、开新、绑策略、写审计）；
 * {@code activated = false} 表示这是同一支付事件的幂等重放，返回的是该订单此前已经生效出来的
 * 订阅，本次没有产生任何新事实。调用方据此区分「生效」与「重放」，而不需要再查数据库。
 *
 * @param subscriptionId 订阅行 ID（本次新建，或重放时该订单既有订阅）
 * @param startsAt 服务期起始时刻
 * @param endsAt 服务期结束时刻
 * @param activated 本次调用是否真实生效
 */
public record SubscriptionActivation(UUID subscriptionId, Instant startsAt, Instant endsAt,
                                     boolean activated) {

    /** 生效结果必须能指向一条订阅与服务期。 */
    public SubscriptionActivation {
        Objects.requireNonNull(subscriptionId, "订阅 ID 不得为空");
        Objects.requireNonNull(startsAt, "服务期起始时刻不得为空");
        Objects.requireNonNull(endsAt, "服务期结束时刻不得为空");
    }
}
