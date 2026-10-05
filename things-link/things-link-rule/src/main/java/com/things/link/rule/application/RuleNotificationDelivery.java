package com.things.link.rule.application;

import java.util.UUID;

/**
 * 已由数据库 CAS 为 SENDING 的规则通知冻结快照。
 *
 * @param id 投递事实与外部幂等 ID
 * @param tenantId 归属租户标识，仅用于同属校验
 * @param projectId 项目隔离与 Webhook 密钥派生轴
 * @param channel 固定 EMAIL 或 WEBHOOK 渠道
 * @param recipient 冻结邮箱或 HTTPS 地址
 * @param subject 冻结邮件主题
 * @param body 冻结正文
 * @param traceId 低敏链路追踪 ID
 * @param attemptNo 当前尝试序号
 * @param maxAttempts 冻结最大尝试次数
 */
public record RuleNotificationDelivery(
        UUID id,
        UUID tenantId,
        UUID projectId,
        String channel,
        String recipient,
        String subject,
        String body,
        String traceId,
        int attemptNo,
        int maxAttempts) {
}
