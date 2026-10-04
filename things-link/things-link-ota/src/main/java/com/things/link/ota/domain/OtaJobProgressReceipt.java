package com.things.link.ota.domain;

import java.time.Instant;
import java.util.UUID;

/** 原认证进度的不可变接纳事实；设备证据不等于平台验证通过。 */
public record OtaJobProgressReceipt(
        UUID id, UUID tenantId, UUID projectId, UUID campaignId, UUID jobId, UUID deviceId,
        int attemptNo, long credentialVersion, long progressSeq, UUID authorizationId, String manifestSha256,
        String stage, UUID bootId, byte[] canonical, String payloadHash, Instant brokerReceivedAt, Instant acceptedAt) {
    /** 复制原始证据，避免调用方修改已冻结内容。 */
    public OtaJobProgressReceipt {
        canonical = canonical.clone();
    }
    /** 返回防御副本。 */
    @Override public byte[] canonical() { return canonical.clone(); }
}
