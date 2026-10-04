package com.things.link.ota.domain;

import java.time.Instant;
import java.util.UUID;

/** 认证设备确定提交报告；保留原Broker时间，不覆盖原提交许可观察。 */
public record OtaReconciliationReceipt(UUID id, UUID tenantId, UUID projectId, UUID campaignId,
        UUID jobId, UUID deviceId, int attemptNo, long credentialVersion, UUID queryId,
        UUID reportId, UUID bootId, byte[] canonical, String payloadHash,
        Instant brokerReceivedAt, Instant acceptedAt) {
    /** 原报告字节防御复制。 */
    public OtaReconciliationReceipt { canonical = canonical.clone(); }
    /** 读取独立报告证据。 */
    @Override public byte[] canonical() { return canonical.clone(); }
}
