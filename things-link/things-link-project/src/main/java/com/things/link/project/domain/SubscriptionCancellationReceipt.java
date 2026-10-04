package com.things.link.project.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * ADR0166 不可变取消结果，重放不能改变后续重新购买的订阅。
 * @param operationId 原操作身份 @param tenantId 租户 @param subscriptionId 原付费订阅
 * @param freeSubscriptionId 本次创建的真实FREE @param cancelledAt 实际终止时刻
 * @param reason 规范化原因 @param assignmentVersionBefore 原绑定版本 @param assignmentVersionAfter 新绑定版本
 */
public record SubscriptionCancellationReceipt(UUID operationId, UUID tenantId, UUID subscriptionId,
        UUID freeSubscriptionId, Instant cancelledAt, String reason,
        long assignmentVersionBefore, long assignmentVersionAfter) { }
