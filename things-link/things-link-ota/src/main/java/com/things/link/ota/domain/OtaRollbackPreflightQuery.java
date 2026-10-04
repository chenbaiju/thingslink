package com.things.link.ota.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 只读回退预检原查询及精确受控基线快照，不授予任何设备执行权。 */
public record OtaRollbackPreflightQuery(UUID id, UUID tenantId, UUID projectId, UUID campaignId,
        UUID jobId, UUID deviceId, int attemptNo, long credentialVersion, long recoveryRevision,
        UUID authorizationId, List<UUID> permitIds, String sourceSlot, String targetSlot, String manifestSha256,
        byte[] canonical, String payloadHash, byte[] baselineCanonical, byte[] typeBaselineCanonical,
        Instant createdAt, Instant deadlineAt) {
    /** 所有可变输入防御复制，查询窗口内不允许悄悄更换基线或许可列表。 */
    public OtaRollbackPreflightQuery {
        permitIds = List.copyOf(permitIds);
        canonical = canonical.clone();
        baselineCanonical = baselineCanonical.clone();
        typeBaselineCanonical = typeBaselineCanonical.clone();
    }
    /** 返回独立查询字节。 */
    @Override public byte[] canonical() { return canonical.clone(); }
    /** 返回独立受控扩展基线字节。 */
    @Override public byte[] baselineCanonical() { return baselineCanonical.clone(); }
    /** 返回独立原类型基线字节。 */
    @Override public byte[] typeBaselineCanonical() { return typeBaselineCanonical.clone(); }
}
