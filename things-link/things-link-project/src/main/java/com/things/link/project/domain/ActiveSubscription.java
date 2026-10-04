package com.things.link.project.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 一条生效中的基础订阅（S14-3a）。
 *
 * <p>{@code endsAt} 为 {@code null} 表示零价长期有效的 FREE（服务期没有终点，不是「不限额度」）。
 * 订阅生效事务据此判定「首次购买」还是「续费」：没有 {@code endsAt} 的当前订阅只能被首次购买取代，
 * 有 {@code endsAt} 的当前订阅则让新服务期从该时刻**延长**，绝不从支付日重算（S14-0 P5）。
 *
 * @param id 订阅行 ID
 * @param planRevisionId 订阅锁定的产品修订版
 * @param startsAt 服务期起始时刻
 * @param endsAt 服务期结束时刻；{@code null} 仅零价长期 FREE
 */
public record ActiveSubscription(UUID id, UUID planRevisionId, Instant startsAt, Instant endsAt) {

    /** 生效订阅行必须有 ID、修订版与起始时刻。 */
    public ActiveSubscription {
        Objects.requireNonNull(id, "订阅 ID 不得为空");
        Objects.requireNonNull(planRevisionId, "订阅产品修订版 ID 不得为空");
        Objects.requireNonNull(startsAt, "服务期起始时刻不得为空");
    }
}
