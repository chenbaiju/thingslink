package com.things.link.rule.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * S8-1C 有界调试事件事实，不保存源码、完整输入输出、凭据或异常正文。
 *
 * @param id UUIDv7 主键 @param tenantId 项目所有者租户 @param projectId 项目隔离轴
 * @param ruleId 规则定义 @param versionId 实际执行的不可变版本 @param status 封闭执行状态
 * @param resultCode 成功为 SUCCESS，失败为沙箱固定分类 @param durationMillis 完整执行耗时毫秒
 * @param inputBytes 原始输入 UTF-8 字节数 @param outputBytes 成功输出 UTF-8 字节数
 * @param inputSummary 脱敏截断输入摘要 @param outputSummary 脱敏截断输出摘要
 * @param createdBy 发起账号 @param createdAt 发生时刻 @param expiresAt 保留截止时刻
 */
public record RuleDebugEvent(
        UUID id, UUID tenantId, UUID projectId, UUID ruleId, UUID versionId,
        String status, String resultCode, long durationMillis, int inputBytes, int outputBytes,
        String inputSummary, String outputSummary, UUID createdBy, Instant createdAt, Instant expiresAt) {
}
