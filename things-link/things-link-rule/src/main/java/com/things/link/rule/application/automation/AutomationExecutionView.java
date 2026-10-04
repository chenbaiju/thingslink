package com.things.link.rule.application.automation;
import java.time.Instant;
import java.util.UUID;
/** 只读运行投影；禁止原始输入、配置、异常及租约token进入HTTP。 */
public record AutomationExecutionView(UUID id,UUID automationId,UUID automationVersionId,String triggerType,
        UUID deviceId,String status,String reasonCode,int attemptCount,Instant occurredAt,Instant acceptedAt,
        Instant createdAt,Instant completedAt) {}
