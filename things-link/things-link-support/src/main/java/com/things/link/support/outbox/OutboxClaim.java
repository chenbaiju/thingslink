package com.things.link.support.outbox;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 一个后台实例短时独占的一批 Outbox 事件。
 *
 * <p>{@code leaseToken} 不是业务标识，而是防止崩溃实例在租约过期后误确认新实例已接管的事件。
 * 所有确认和重试操作都必须携带它，不能只按事件 ID 更新。</p>
 *
 * @param leaseToken 本次领取生成的随机租约令牌
 * @param events 已领取且尚未得到 Kafka 确认的事件
 */
public record OutboxClaim(UUID leaseToken, List<OutboxEvent> events) {

    /** 将集合冻结，防止调度线程在投递期间被调用方修改。 */
    public OutboxClaim {
        Objects.requireNonNull(leaseToken, "Outbox 租约令牌不能为空");
        events = List.copyOf(Objects.requireNonNull(events, "Outbox 事件列表不能为空"));
    }
}
