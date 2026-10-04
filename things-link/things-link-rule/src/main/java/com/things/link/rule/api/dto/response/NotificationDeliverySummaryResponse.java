package com.things.link.rule.api.dto.response;

import com.things.link.rule.domain.NotificationDeliverySummary;

import java.time.Instant;

/**
 * 场景执行详情里的一条通知投递状态摘要响应。
 *
 * @param channel 通知渠道
 * @param recipient 已渲染占位符的收件人
 * @param status 投递状态
 * @param attemptCount 已经 CAS 领取的外部发送尝试次数
 * @param maxAttempts 创建时冻结的最大外部发送次数
 * @param lastErrorCode 最近一次失败的固定低基数原因，成功时为空
 * @param deliveredAt 真实外部渠道成功返回的 UTC 时刻，未送达时为空
 * @param createdAt 投递事实落库的 UTC 时刻
 */
public record NotificationDeliverySummaryResponse(
        String channel,
        String recipient,
        String status,
        int attemptCount,
        int maxAttempts,
        String lastErrorCode,
        Instant deliveredAt,
        Instant createdAt) {

    /** @return 领域投递摘要到 API 响应 */
    public static NotificationDeliverySummaryResponse from(NotificationDeliverySummary summary) {
        return new NotificationDeliverySummaryResponse(
                summary.channel(), summary.recipient(), summary.status(), summary.attemptCount(),
                summary.maxAttempts(), summary.lastErrorCode(), summary.deliveredAt(), summary.createdAt());
    }
}
