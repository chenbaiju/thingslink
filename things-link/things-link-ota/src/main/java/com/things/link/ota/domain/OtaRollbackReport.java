package com.things.link.ota.domain;

import java.time.Instant;
import java.util.UUID;

/** 原认证主动或查询结果，序号与已观察计数跨查询永久保留。 */
public record OtaRollbackReport(UUID id, UUID tenantId, UUID projectId, UUID campaignId, UUID operationId, UUID jobId, UUID deviceId,
        int attemptNo, long credentialVersion, UUID queryId, UUID reportId, long reportSeq, UUID bootId,
        String status, long committedSecurityVersion, byte[] canonical, String payloadHash,
        Instant brokerReceivedAt, Instant acceptedAt) {
    /** 冻结原规范字节，避免调用方修改事实。 */
    public OtaRollbackReport { canonical = canonical.clone(); }
    /** 输出独立的原规范字节。 */
    @Override public byte[] canonical() { return canonical.clone(); }
}
