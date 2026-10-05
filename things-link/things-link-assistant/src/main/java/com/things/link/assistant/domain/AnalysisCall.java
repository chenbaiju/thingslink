package com.things.link.assistant.domain;

import java.time.Instant;
import java.util.UUID;

/** 仅调用元数据；不承载凭据、设备属性值或模型正文。 */
public record AnalysisCall(UUID id, UUID tenantId, UUID projectId, UUID createdBy,
        UUID deviceId, UUID modelVersionId, String keyHash, String requestHash,
        long configurationRevision, Status status, Instant createdAt, Instant deadline,
        Instant expiresAt, Instant dispatchedAt, Instant finishedAt) {
    public enum Status {
        RESERVED, DISPATCHED, SUCCEEDED, FAILED, UNKNOWN;
        public boolean terminal() { return this != RESERVED && this != DISPATCHED; }
    }
    @Override public String toString() { return "AnalysisCall[id=" + id + ",status=" + status + "]"; }
}
