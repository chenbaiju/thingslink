package com.things.link.project.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 一条预约降级事实（S14-3b，S14-0 P5）。
 *
 * <p>它是「下个周期要切到哪个修订版」的计划：{@code effectiveAt} 固定等于预约时当前 ACTIVE
 * 订阅的 {@code endsAt}，{@code subscriptionId} 记下这条意图针对的是哪个服务期。真正切换
 * （关旧、开新、绑策略、审计）属 S14-3c，本记录只保证 3c 有足够事实判断「这条预约是否仍然有效」。
 *
 * <p>{@code status} 只推进不删除：撤销过的预约行保留为 {@link SubscriptionPendingChangeStatus#CANCELLED}，
 * 套用过的保留为 {@link SubscriptionPendingChangeStatus#APPLIED}。
 *
 * @param id 预约行 ID
 * @param tenantId 归属租户
 * @param subscriptionId 预约针对的订阅行（预约时的 ACTIVE 订阅）
 * @param fromPlanRevisionId 预约时的来源修订版快照
 * @param targetPlanRevisionId 下个周期要生效的目标修订版
 * @param effectiveAt 生效时刻（UTC，含）= 预约时当前服务期终点
 * @param status 预约状态
 * @param createdAt 创建时刻
 * @param updatedAt 最后修改时刻
 * @param revision 预约行乐观锁版本
 */
public record SubscriptionPendingChange(
        UUID id,
        UUID tenantId,
        UUID subscriptionId,
        UUID fromPlanRevisionId,
        UUID targetPlanRevisionId,
        Instant effectiveAt,
        SubscriptionPendingChangeStatus status,
        Instant createdAt,
        Instant updatedAt,
        long revision) {

    /** 预约事实必须完整：ID、归属、针对的订阅、来源与目标、生效时刻与状态缺一不可。 */
    public SubscriptionPendingChange {
        Objects.requireNonNull(id, "预约 ID 不得为空");
        Objects.requireNonNull(tenantId, "预约租户 ID 不得为空");
        Objects.requireNonNull(subscriptionId, "预约订阅 ID 不得为空");
        Objects.requireNonNull(fromPlanRevisionId, "预约来源修订版 ID 不得为空");
        Objects.requireNonNull(targetPlanRevisionId, "预约目标修订版 ID 不得为空");
        Objects.requireNonNull(effectiveAt, "预约生效时刻不得为空");
        Objects.requireNonNull(status, "预约状态不得为空");
        Objects.requireNonNull(createdAt, "预约创建时刻不得为空");
        Objects.requireNonNull(updatedAt, "预约修改时刻不得为空");
        if (fromPlanRevisionId.equals(targetPlanRevisionId)) {
            throw new IllegalArgumentException("预约降级的目标修订版不得与来源相同");
        }
    }
}
