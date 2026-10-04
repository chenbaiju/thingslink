package com.things.link.ota.domain;

import java.time.Instant;
import java.util.UUID;

/** 原唯一原子回退操作，固定字节和两阶段期限，不能因未知生成新操作。 */
public record OtaRollbackOperation(UUID id, UUID tenantId, UUID projectId, UUID campaignId, UUID jobId, UUID deviceId,
        int attemptNo, long credentialVersion, UUID authorizationId, String manifestSha256,
        UUID preflightQueryId, UUID preflightReceiptId, long recoveryRevision, byte[] canonical, String payloadHash,
        Instant createdAt, Instant pendingDeadlineAt, Instant rollingDeadlineAt) {
    /** 冻结原规范字节，避免调用方修改事实。 */
    public OtaRollbackOperation { canonical = canonical.clone(); }
    /** 输出独立的原规范字节。 */
    @Override public byte[] canonical() { return canonical.clone(); }
}
