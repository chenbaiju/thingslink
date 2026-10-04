package com.things.link.alarm.infrastructure.notification;

import com.things.link.alarm.application.NotificationChannelSender;
import com.things.link.alarm.application.NotificationSendException;
import com.things.link.alarm.domain.AlarmNotificationDelivery;
import com.things.link.alarm.domain.NotificationChannel;
import com.things.link.support.notification.delivery.ExternalNotificationException;
import com.things.link.support.notification.delivery.ExternalNotificationRequest;
import com.things.link.support.notification.delivery.ExternalNotificationSender;
import com.things.link.support.notification.delivery.ExternalWebhookNotificationSender;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Map;

/** 把告警冻结快照适配到共用真实 Webhook 端口；HTTP 与 HMAC 设施由 support 唯一实现。 */
@Component
public class WebhookNotificationSender implements NotificationChannelSender {

    /** 接收端外部效果幂等键；兼容 S6 已公开常量。 */
    public static final String DELIVERY_ID_HEADER = ExternalWebhookNotificationSender.DELIVERY_ID_HEADER;

    /** 接收端限制签名重放窗口的 UTC 秒；兼容 S6 已公开常量。 */
    public static final String TIMESTAMP_HEADER = ExternalWebhookNotificationSender.TIMESTAMP_HEADER;

    /** 每次 HTTP 尝试唯一随机数；兼容 S6 已公开常量。 */
    public static final String NONCE_HEADER = ExternalWebhookNotificationSender.NONCE_HEADER;

    /** 项目派生 HMAC-SHA256 签名；兼容 S6 已公开常量。 */
    public static final String SIGNATURE_HEADER = ExternalWebhookNotificationSender.SIGNATURE_HEADER;

    /** 告警与规则共用的 Webhook 发送器。 */
    private final ExternalNotificationSender sender;

    /** @param sender support 共用真实 Webhook 发送器 */
    public WebhookNotificationSender(
            @Qualifier(ExternalNotificationSender.WEBHOOK_BEAN) ExternalNotificationSender sender) {
        this.sender = sender;
    }

    /** {@inheritDoc} */
    @Override
    public NotificationChannel channel() {
        return NotificationChannel.WEBHOOK;
    }

    /** {@inheritDoc} */
    @Override
    public String send(AlarmNotificationDelivery delivery) {
        try {
            return sender.send(new ExternalNotificationRequest(
                    delivery.id(), delivery.projectId(), delivery.targetSnapshot(),
                    delivery.subjectSnapshot(), delivery.bodySnapshot(), Map.of(
                            "alarmInstanceId", delivery.instanceId(),
                            "alarmEventId", delivery.alarmEventId(),
                            "content", delivery.bodySnapshot())));
        } catch (ExternalNotificationException exception) {
            throw mapped(exception);
        }
    }

    /** @return 保留共用原因与可恢复性的告警域失败 */
    private static NotificationSendException mapped(ExternalNotificationException exception) {
        return new NotificationSendException(
                NotificationSendException.Reason.valueOf(exception.reason().name()),
                exception.retryable(), exception);
    }
}
