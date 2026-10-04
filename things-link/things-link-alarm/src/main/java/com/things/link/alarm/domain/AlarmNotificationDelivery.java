package com.things.link.alarm.domain;

import java.time.Instant;
import java.util.UUID;

/** 告警激活时冻结的外部投递意图；它的失败和重试永远不改变 alarm_instance。 */
public record AlarmNotificationDelivery(
        UUID id,
        UUID tenantId,
        UUID projectId,
        UUID instanceId,
        UUID alarmEventId,
        UUID bindingId,
        UUID recipientId,
        UUID appUserId,
        UUID pushTokenId,
        NotificationChannel channel,
        String targetSnapshot,
        String subjectSnapshot,
        String bodySnapshot,
        int templateVersion,
        Status status,
        int attemptCount,
        int maxAttempts,
        Instant nextAttemptAt,
        UUID lastOutboxEventId,
        String providerMessageId,
        String lastErrorCode,
        Instant createdAt,
        Instant updatedAt,
        Instant terminalAt) {
    /** 投递工作流状态；QUEUED 是待投递初态，SUPPRESSED_QUOTA/TEMPLATE_INVALID 是保留审计事实且不创建 Outbox 的终态。 */
    public enum Status {
        QUEUED,
        SENDING,
        SUCCEEDED,
        RETRY_SCHEDULED,
        DEAD_LETTER,
        /** 发送前 enduser 权威复核失败；没有调用厂商且不得进入重试。 */
        SKIPPED_AUTHORIZATION,
        /** 租户严重超额时保留审计事实但不进入外部投递 Outbox。 */
        SUPPRESSED_QUOTA,
        /** 模板渲染结果非法（超长/空正文），保留审计事实但不进入外部投递（D-040）。 */
        TEMPLATE_INVALID
    }
}
