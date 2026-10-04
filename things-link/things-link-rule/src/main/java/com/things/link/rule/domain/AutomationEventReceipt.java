package com.things.link.rule.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 不可变首次事件计划；不包含原始输入，空计划同样是已处理事实。 */
public record AutomationEventReceipt(UUID id, UUID tenantId, UUID projectId, UUID sourceEventId,
        UUID deviceId, Instant acceptedAt, Instant admittedAt, String sourceDigest,
        List<UUID> plan, Result result, String reasonCode, Instant expiresAt) {
    public AutomationEventReceipt { plan = List.copyOf(plan); }
    /** 接受不等于存在匹配定义或动作送达。 */
    public enum Result { ACCEPTED, REJECTED }
}
