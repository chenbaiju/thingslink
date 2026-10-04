package com.things.link.ota.domain;

import java.time.Instant;
import java.util.UUID;

/** 原认证健康观察；单条样本不等于稳定窗口完成。 */
public record OtaHealthReceipt(
        UUID id, UUID tenantId, UUID projectId, UUID campaignId, UUID jobId, UUID deviceId,
        int attemptNo, long credentialVersion, long healthSeq, UUID authorizationId, String manifestSha256,
        UUID bootId, byte[] canonical, String payloadHash, Instant brokerReceivedAt, Instant acceptedAt) {
    /** 输入规范证据防御复制，避免持久化前后被调用方改写。 */
    public OtaHealthReceipt { canonical = canonical.clone(); }
    /** 读取独立的原规范字节。 */
    @Override public byte[] canonical() { return canonical.clone(); }
}
