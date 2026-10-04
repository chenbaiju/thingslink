package com.things.link.ota.domain;

import java.time.Instant;
import java.util.UUID;

/** 不可变的派发时真实报告来源，不由后续当前报告回填。 */
public record OtaJobExecutionOrigin(
        UUID tenantId, UUID projectId, UUID campaignId, UUID jobId, UUID deviceId,
        int attemptNo, long credentialVersion, long reportRevision, long reportSequence, String reportHash,
        byte[] canonical, Instant brokerReceivedAt, Instant acceptedAt, Instant capturedAt, long dispatchedRevision) {
    /** 复制原始证据，避免调用方修改已冻结内容。 */
    public OtaJobExecutionOrigin {
        canonical = canonical.clone();
    }
    /** 返回防御副本。 */
    @Override public byte[] canonical() { return canonical.clone(); }
}
