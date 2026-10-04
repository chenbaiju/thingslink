package com.things.link.rule.domain;

import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** 私有执行快照，不能直接作为HTTP响应。 */
public record AutomationExecution(UUID id, UUID tenantId, UUID projectId, UUID automationId,
        UUID versionId, UUID deviceId, UUID responsibleAccountId, String traceId, JsonNode input,
        Instant occurredAt, Instant acceptedAt, String status, int attempt, long token,
        Instant leaseUntil, Instant nextAttemptAt, Instant deadline) {
    public AutomationExecution { input = input == null ? null : input.deepCopy(); }
    @Override public JsonNode input() { return input == null ? null : input.deepCopy(); }
}
