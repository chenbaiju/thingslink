package com.things.link.ota.domain;

import java.time.Instant;
import java.util.UUID;

/** 设备原子提交回执；成功还需同事务确认真实模型绑定。 */
public record OtaCommitReceipt(
        UUID id, UUID tenantId, UUID projectId, UUID campaignId, UUID jobId, UUID deviceId,
        int attemptNo, long credentialVersion, UUID receiptId, UUID permitId, String manifestSha256, UUID bootId,
        long committedSecurityVersion, byte[] canonical, String payloadHash, Instant brokerReceivedAt, Instant acceptedAt) {
    /** 输入规范证据防御复制，避免持久化前后被调用方改写。 */
    public OtaCommitReceipt { canonical = canonical.clone(); }
    /** 读取独立的原规范字节。 */
    @Override public byte[] canonical() { return canonical.clone(); }
}
