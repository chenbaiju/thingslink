package com.things.link.ota.domain;

import java.time.Instant;
import java.util.UUID;

/** 健康窗口后唯一原子提交许可；固定字节和原期限，重发不续期。 */
public record OtaCommitPermit(
        UUID id, UUID tenantId, UUID projectId, UUID campaignId, UUID jobId, UUID deviceId,
        int attemptNo, long credentialVersion, UUID authorizationId, String manifestSha256, UUID bootId,
        UUID healthReceiptId, byte[] canonical, String payloadHash, Instant createdAt, Instant deadlineAt) {
    /** 输入规范证据防御复制，避免持久化前后被调用方改写。 */
    public OtaCommitPermit { canonical = canonical.clone(); }
    /** 读取独立的原规范字节。 */
    @Override public byte[] canonical() { return canonical.clone(); }
}
