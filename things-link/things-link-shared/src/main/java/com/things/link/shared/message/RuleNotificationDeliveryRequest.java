package com.things.link.shared.message;

import java.time.Instant;
import java.util.UUID;

/**
 * 规则、手动场景或自动化动作产生的通用通知投递请求，从 {@code tc.rule.notification} 交给规则投递消费者。
 *
 * <p>与告警域的 {@link NotificationDeliveryRequest} 解耦：规则/场景通知不依赖告警实例/事件外键，只携带动作节点
 * 已经渲染完成的 channel、recipient、subject、body 与触发它的来源身份。来源是互斥且完整的规则、场景或自动化组（见ADR0152），
 * S9-1 消费者只落投递事实，真实发送在 S9-3 接入。</p>
 *
 * @param eventId 稳定投递 ID 与 Kafka 分区键；重试 Outbox 有独立行 ID，但始终携带本 ID 做尝试 CAS
 * @param tenantId 来源所属项目的 owner tenant ID
 * @param projectId 项目隔离轴
 * @param ruleId 规则来源：触发该动作的规则定义 ID；场景来源为空
 * @param ruleVersionId 规则来源：触发该动作的不可变规则版本 ID；场景来源为空
 * @param messageId 规则来源：触发规则的上行消息 ID；场景来源为空
 * @param sceneId 场景来源：触发该动作的场景定义 ID；规则来源为空
 * @param sceneVersionId 场景来源：触发该动作的不可变场景版本 ID；规则来源为空
 * @param sceneExecutionId 场景来源：一次手动执行的执行事实 ID；规则来源为空
 * @param deviceId 触发动作的目标设备 ID
 * @param channel 通知渠道（email、webhook 等，动作节点已校验长度）
 * @param recipient 收件人，动作节点已渲染占位符
 * @param subject 通知标题，可为空串
 * @param body 通知正文，可为空串
 * @param traceId 全链路追踪 ID
 * @param attemptNo 本条 Outbox 请求指定的正整数尝试序号
 * @param enqueuedAt 本条 Outbox 请求进入队列的 UTC 时刻
 * @param automationId 自动化定义ID；其他来源为空
 * @param automationVersionId 自动化冻结版本ID；其他来源为空
 * @param automationExecutionId 自动化执行事实ID；其他来源为空
 */
public record RuleNotificationDeliveryRequest(
        UUID eventId,
        UUID tenantId,
        UUID projectId,
        UUID ruleId,
        UUID ruleVersionId,
        UUID messageId,
        UUID sceneId,
        UUID sceneVersionId,
        UUID sceneExecutionId,
        UUID deviceId,
        String channel,
        String recipient,
        String subject,
        String body,
        String traceId,
        int attemptNo,
        Instant enqueuedAt,
        UUID automationId, UUID automationVersionId, UUID automationExecutionId) {

    /** 保留旧规则/场景调用入口，新来源始终显式传入。 */
    public RuleNotificationDeliveryRequest(UUID eventId, UUID tenantId, UUID projectId,
            UUID ruleId, UUID ruleVersionId, UUID messageId, UUID sceneId, UUID sceneVersionId,
            UUID sceneExecutionId, UUID deviceId, String channel, String recipient, String subject,
            String body, String traceId, int attemptNo, Instant enqueuedAt) {
        this(eventId, tenantId, projectId, ruleId, ruleVersionId, messageId, sceneId, sceneVersionId,
                sceneExecutionId, deviceId, channel, recipient, subject, body, traceId, attemptNo,
                enqueuedAt, null, null, null);
    }

    /** 三组来源必须恰有一组完整，其余字段全部为空；拒绝残缺混合来源。 */
    public boolean validSource() {
        int rules = present(ruleId, ruleVersionId, messageId);
        int scenes = present(sceneId, sceneVersionId, sceneExecutionId);
        int automations = present(automationId, automationVersionId, automationExecutionId);
        return (rules == 3 && scenes == 0 && automations == 0)
                || (rules == 0 && scenes == 3 && automations == 0)
                || (rules == 0 && scenes == 0 && automations == 3);
    }

    /** 来源完整性计数，不接受非空但不完整的第二组。 */
    private static int present(UUID a, UUID b, UUID c) {
        return (a == null ? 0 : 1) + (b == null ? 0 : 1) + (c == null ? 0 : 1);
    }

    /** 自动化工厂，与消息规则及手动场景来源互斥。 */
    public static RuleNotificationDeliveryRequest automation(UUID eventId, UUID tenantId, UUID projectId,
            UUID automationId, UUID automationVersionId, UUID automationExecutionId, UUID deviceId,
            String channel, String recipient, String subject, String body, String traceId,
            int attemptNo, Instant enqueuedAt) {
        return new RuleNotificationDeliveryRequest(eventId, tenantId, projectId,
                null, null, null, null, null, null, deviceId, channel, recipient, subject, body,
                traceId, attemptNo, enqueuedAt, automationId, automationVersionId, automationExecutionId);
    }

    /** Outbox 路由使用的稳定事件类型；规则/场景写侧与 support 发布器必须引用同一常量。 */
    public static final String EVENT_TYPE = "RULE_NOTIFICATION_DELIVERY_REQUEST";

    /** 滚动升级期间旧 Kafka JSON 缺少新增字段时，Jackson 会传入 {@code 0/null}；只对这一组合恢复首次尝试语义。 */
    public RuleNotificationDeliveryRequest {
        if (attemptNo == 0 && enqueuedAt == null) {
            attemptNo = 1;
            enqueuedAt = Instant.EPOCH;
        }
    }

    /**
     * 规则来源工厂；填充规则组并显式携带尝试序号与入队时刻，避免与场景来源同名构造发生参数位置混淆。
     */
    public static RuleNotificationDeliveryRequest rule(
            UUID eventId,
            UUID tenantId,
            UUID projectId,
            UUID ruleId,
            UUID ruleVersionId,
            UUID messageId,
            UUID deviceId,
            String channel,
            String recipient,
            String subject,
            String body,
            String traceId,
            int attemptNo,
            Instant enqueuedAt) {
        return new RuleNotificationDeliveryRequest(eventId, tenantId, projectId,
                ruleId, ruleVersionId, messageId, null, null, null,
                deviceId, channel, recipient, subject, body, traceId, attemptNo, enqueuedAt);
    }

    /**
     * 场景来源工厂；填充场景组并显式携带尝试序号与入队时刻，与规则来源工厂互斥。
     */
    public static RuleNotificationDeliveryRequest scene(
            UUID eventId,
            UUID tenantId,
            UUID projectId,
            UUID sceneId,
            UUID sceneVersionId,
            UUID sceneExecutionId,
            UUID deviceId,
            String channel,
            String recipient,
            String subject,
            String body,
            String traceId,
            int attemptNo,
            Instant enqueuedAt) {
        return new RuleNotificationDeliveryRequest(eventId, tenantId, projectId,
                null, null, null, sceneId, sceneVersionId, sceneExecutionId,
                deviceId, channel, recipient, subject, body, traceId, attemptNo, enqueuedAt);
    }
}
