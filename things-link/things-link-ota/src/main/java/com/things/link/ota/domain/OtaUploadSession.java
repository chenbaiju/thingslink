package com.things.link.ota.domain;

import java.time.Instant;
import java.util.UUID;

/** 上传会话持久事实，不得直接序列化到API。
 * @param id 上传会话身份
 * @param tenantId 项目所有者租户
 * @param projectId 所属项目
 * @param firmwareId 固件身份
 * @param createdBy 创建账号
 * @param requestId 不可复用对象请求身份
 * @param projectGeneration 创建项目代次
 * @param expectedLength 预期字节数
 * @param revision 会话修订
 * @param expectedSha256 预期正文摘要
 * @param bucket 受控私桶
 * @param objectKey 独占对象键
 * @param status 持久阶段
 * @param versionId 已明确对象版本
 * @param failureCode 安全固定失败原因
 * @param keyDigest 创建幂等摘要
 * @param requestDigest 创建内容摘要
 * @param createdAt 创建时刻
 * @param expiresAt 接收截止
 * @param leaseUntil 租约截止
 * @param writeStartedAt 网络写入前登记时刻
 * @param writeSettledAt 写入已明确终结时刻
 * @param cancelRequestedAt 取消意图时刻
 * @param cleanupCompletedAt 收束证明时刻
 * @param nextAttemptAt 恢复调度时刻
 * @param leaseToken 完整领取能力
 */
public record OtaUploadSession(
        UUID id,
        UUID tenantId,
        UUID projectId,
        UUID firmwareId,
        UUID createdBy,
        UUID requestId,
        long projectGeneration,
        long expectedLength,
        long revision,
        String expectedSha256,
        String bucket,
        String objectKey,
        String status,
        String versionId,
        String failureCode,
        String keyDigest,
        String requestDigest,
        Instant createdAt,
        Instant expiresAt,
        Instant leaseUntil,
        Instant writeStartedAt,
        Instant writeSettledAt,
        Instant cancelRequestedAt,
        Instant cleanupCompletedAt,
        Instant nextAttemptAt,
        UUID leaseToken) { }
