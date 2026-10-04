package com.things.link.rule.domain;
import java.time.Instant;
import java.util.UUID;
/** 设备自动化只读投影，不含输入、配置或责任身份。 */
public record DeviceAutomationExecutionItem(UUID id, UUID automationId, UUID automationVersionId, String triggerType, String status, String reasonCode, int attemptCount, long deviceActionRecordCount, Instant occurredAt, Instant acceptedAt, Instant createdAt, Instant completedAt) { }
