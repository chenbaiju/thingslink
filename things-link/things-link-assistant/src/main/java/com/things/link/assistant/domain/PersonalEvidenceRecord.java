package com.things.link.assistant.domain;
import java.time.Instant;
import java.util.UUID;
/** 自有不可变历史记录；正文与摘要不进入默认日志。 */
public record PersonalEvidenceRecord(UUID id, UUID tenantId, UUID projectId, UUID createdBy,
        UUID deviceId, UUID modelVersionId, Instant createdAt, Instant expiresAt, String contentSha256, String content) {
    @Override public String toString() { return "个人事实记录[历史快照]"; }
}
