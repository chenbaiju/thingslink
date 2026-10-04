package com.things.link.ota.domain;

import java.time.Instant;
import java.util.UUID;

/** 原认证主动或查询结果，序号与原子停止方向跨查询永久保留。 */
public record OtaInstallStopReport(UUID id, UUID tenantId, UUID projectId, UUID campaignId, UUID operationId, UUID jobId, UUID deviceId,
        int attemptNo, long credentialVersion, UUID queryId, UUID reportId, long reportSeq, UUID bootId,
        String status, byte[] canonical, String payloadHash,
        Instant brokerReceivedAt, Instant acceptedAt) {
    /** 冻结原规范字节，避免调用方修改事实。 */
    public OtaInstallStopReport { canonical = canonical.clone(); }
    /** 输出独立的原规范字节。 */
    @Override public byte[] canonical() { return canonical.clone(); }
}
