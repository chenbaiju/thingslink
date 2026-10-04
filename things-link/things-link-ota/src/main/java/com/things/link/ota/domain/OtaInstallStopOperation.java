package com.things.link.ota.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 原管理取消对应的唯一停止命令，固定基线和来源而不依赖下载授权存在。 */
public record OtaInstallStopOperation(UUID id, UUID tenantId, UUID projectId, UUID campaignId, UUID jobId, UUID deviceId,
        int attemptNo, long credentialVersion, String manifestSha256, String originHash,
        long cancellationRevision, long jobRevision, byte[] parentBaseline, byte[] stopBaseline,
        List<UUID> authorizationIds, byte[] canonical, String payloadHash, Instant createdAt, Instant deadlineAt) {
    /** 防御复制所有冻结证据和辅助授权身份。 */
    public OtaInstallStopOperation {
        parentBaseline=parentBaseline.clone();stopBaseline=stopBaseline.clone();
        authorizationIds=List.copyOf(authorizationIds);canonical=canonical.clone();
    }
    /** 返回独立父基线字节。 */
    @Override public byte[] parentBaseline() { return parentBaseline.clone(); }
    /** 返回独立停止扩展基线字节。 */
    @Override public byte[] stopBaseline() { return stopBaseline.clone(); }
    /** 返回独立原命令字节。 */
    @Override public byte[] canonical() { return canonical.clone(); }
}
