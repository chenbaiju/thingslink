package com.things.link.ota.domain;

import java.time.Instant;
import java.util.UUID;

/** 活动持久快照，排程不代表设备资格或已派发。
 * @param id 活动身份
 * @param tenantId 租户身份
 * @param projectId 项目身份
 * @param firmwareId 固件身份
 * @param releaseId 精确发布身份
 * @param createdBy 真实创建账号
 * @param canonicalPlan 不可变规范计划
 * @param planSha256 计划摘要
 * @param canonicalManifest 不可变规范清单
 * @param manifestSha256 清单摘要
 * @param status 活动状态
 * @param stateVersion 状态修订
 * @param targetCount 冻结目标数量，草稿为零
 * @param batchCount 冻结批次数量，草稿为零
 * @param createdAt 创建时间
 * @param updatedAt 最近转移时间
 * @param scheduledAt 排程时间
 * @param cancelledAt 取消完成时间
 * @param cancellationReason 取消原因
 */
public record OtaCampaign(UUID id, UUID tenantId, UUID projectId, UUID firmwareId, UUID releaseId,
        UUID createdBy, byte[] canonicalPlan, String planSha256, byte[] canonicalManifest,
        String manifestSha256, String status, long stateVersion, int targetCount, int batchCount,
        Instant createdAt, Instant updatedAt, Instant scheduledAt, Instant cancelledAt,
        String cancellationReason) {
    /** 规范字节输入防御复制。 */
    public OtaCampaign {
        canonicalPlan = canonicalPlan.clone();
        canonicalManifest = canonicalManifest.clone();
    }
    /** 返回独立计划副本。 */
    @Override public byte[] canonicalPlan() { return canonicalPlan.clone(); }
    /** 返回独立清单副本。 */
    @Override public byte[] canonicalManifest() { return canonicalManifest.clone(); }
}
