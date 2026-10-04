package com.things.link.support.notification.delivery;

import java.util.Map;
import java.util.UUID;

/**
 * 告警与规则域共同交给渠道适配器的低耦合冻结请求。
 *
 * @param deliveryId 外部接收端幂等键
 * @param projectId Webhook 项目密钥派生轴
 * @param target 冻结邮箱或 HTTPS 地址
 * @param subject 冻结邮件主题
 * @param body 冻结邮件/通知正文
 * @param webhookPayload Webhook 业务字段，不得包含密钥或完整目标
 */
public record ExternalNotificationRequest(
        UUID deliveryId,
        UUID projectId,
        String target,
        String subject,
        String body,
        Map<String, Object> webhookPayload) {

    /** 防止调用方在发送期间修改载荷，破坏签名与重试一致性。 */
    public ExternalNotificationRequest {
        webhookPayload = webhookPayload == null ? Map.of() : Map.copyOf(webhookPayload);
    }
}
