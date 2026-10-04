package com.things.link.ota.domain;

import java.time.Instant;
import java.util.UUID;

/** 原恢复修订上的只读确定提交查询，固定字节、随机数和原截止，不延长作业期限。 */
public record OtaReconciliationQuery(UUID id, UUID tenantId, UUID projectId, UUID campaignId,
        UUID jobId, UUID deviceId, int attemptNo, long credentialVersion, long recoveryRevision,
        UUID authorizationId, UUID permitId, UUID queryNonce, UUID commitBootId,
        String manifestSha256, byte[] canonical, String payloadHash, Instant createdAt, Instant deadlineAt) {
    /** 隔离调用方对规范字节的修改。 */
    public OtaReconciliationQuery { canonical = canonical.clone(); }
    /** 返回独立的固定查询副本。 */
    @Override public byte[] canonical() { return canonical.clone(); }
}
