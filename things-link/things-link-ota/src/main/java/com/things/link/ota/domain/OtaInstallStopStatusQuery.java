package com.things.link.ota.domain;

import java.time.Instant;
import java.util.UUID;

/** 固定原操作的只读状态挑战，不刷新执行阶段预算。 */
public record OtaInstallStopStatusQuery(UUID id, UUID tenantId, UUID projectId, UUID campaignId, UUID operationId, UUID jobId, UUID deviceId,
        int attemptNo, long credentialVersion, byte[] canonical, String payloadHash, Instant createdAt, Instant deadlineAt) {
    /** 冻结原规范字节，避免调用方修改事实。 */
    public OtaInstallStopStatusQuery { canonical = canonical.clone(); }
    /** 输出独立的原规范字节。 */
    @Override public byte[] canonical() { return canonical.clone(); }
}
