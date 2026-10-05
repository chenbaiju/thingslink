package com.things.link.assistant.domain;

import java.time.Instant;
import java.util.UUID;

/** 独立合成探针元数据；永不携带凭据、设备事实或模型正文。 */
public final class ProbeLedger {
    private ProbeLedger() {}
    /** 持久机会状态；成功、失败或未知终态均不返还已消耗机会。 */
    public enum Status { CLAIMED, SUCCEEDED, FAILED, UNKNOWN }
    /**
     * 服务端冻结授权对应的持久批次。
     * @param id 批次标识
     * @param tenantId 归属租户
     * @param projectId 归属项目
     * @param authorizationId 唯一冻结授权标识，重启不创建新机会
     * @param environmentId 授权绑定的环境标识
     * @param configurationRevision 授权绑定的项目配置版本
     * @param manifestSha256 授权绑定的固定样本清单摘要
     * @param createdAt 批次首次创建时间
     */
    public record Batch(UUID id, UUID tenantId, UUID projectId, String authorizationId,
            String environmentId, long configurationRevision, String manifestSha256, Instant createdAt) {
        @Override public String toString() { return "ProbeBatch[REDACTED]"; }
    }
    /**
     * 批次内某个固定样本的一次持久认领。
     * @param id 机会标识
     * @param batchId 所属批次标识
     * @param tenantId 归属租户
     * @param projectId 归属项目
     * @param createdBy 发起者账号，仅本人可读取
     * @param sampleIndex 固定样本序号，同批次不可重复认领
     * @param status 当前持久状态
     * @param claimedAt 首次认领时间
     * @param deadline 原始截止时间，后续调用不得延长
     * @param finishedAt 终态写入时间，尚未完成时为空
     */
    public record Attempt(UUID id, UUID batchId, UUID tenantId, UUID projectId, UUID createdBy,
            int sampleIndex, Status status, Instant claimedAt, Instant deadline, Instant finishedAt) {
        @Override public String toString() { return "ProbeAttempt[REDACTED]"; }
    }
}
