package com.things.link.ota.domain;

import java.time.Instant;
import java.util.UUID;

/** ADR0204原事务冻结的作业完成观察；不含授权、签名或设备报告正文。
 * @param jobId 稳定来源身份
 * @param tenantId 原租户
 * @param projectId 原项目
 * @param campaignId 原活动
 * @param deviceId 原设备
 * @param firmwareId 原固件
 * @param manifestSha256 原manifest摘要
 * @param fromStatus 原状态
 * @param status 封闭终态
 * @param stateVersion 原作业修订
 * @param attemptNo 原尝试号
 * @param failureCode 原失败码
 * @param completedAt 数据库完成观察时刻
 * @param projectGeneration 原项目代次
 * @param traceId 原事务追踪身份
 * @param originTransaction 原xid8十进制文本
 */
public record OtaJobCompletion(UUID jobId, UUID tenantId, UUID projectId, UUID campaignId, UUID deviceId,
        UUID firmwareId, String manifestSha256, String fromStatus, String status, long stateVersion,
        int attemptNo, String failureCode, Instant completedAt, long projectGeneration, String traceId,
        String originTransaction) { }
