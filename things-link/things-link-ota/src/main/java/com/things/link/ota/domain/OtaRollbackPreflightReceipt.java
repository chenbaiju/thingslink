package com.things.link.ota.domain;

import java.time.Instant;
import java.util.UUID;

/** 原认证双槽预检报告，不写设备提交事实或降低已观察安全下限。 */
public record OtaRollbackPreflightReceipt(UUID id, UUID tenantId, UUID projectId, UUID campaignId,
        UUID jobId, UUID deviceId, int attemptNo, long credentialVersion, UUID queryId, UUID reportId,
        UUID bootId, long committedSecurityVersion, byte[] canonical, String payloadHash,
        Instant brokerReceivedAt, Instant acceptedAt) {
    /** 保持报告规范字节不受调用方修改影响。 */
    public OtaRollbackPreflightReceipt { canonical = canonical.clone(); }
    /** 读取原证据独立副本。 */
    @Override public byte[] canonical() { return canonical.clone(); }
}
