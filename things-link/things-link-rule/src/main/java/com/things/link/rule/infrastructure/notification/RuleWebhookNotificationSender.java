package com.things.link.rule.infrastructure.notification;

import com.things.link.rule.application.RuleNotificationDelivery;
import com.things.link.rule.application.RuleNotificationSendException;
import com.things.link.rule.application.RuleNotificationSender;
import com.things.link.support.notification.delivery.ExternalNotificationException;
import com.things.link.support.notification.delivery.ExternalNotificationRequest;
import com.things.link.support.notification.delivery.ExternalNotificationSender;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Map;

/** 把规则冻结快照适配到 S6/S9 共用 Webhook 端口；本类不持有 HTTP 客户端或签名密钥。 */
@Component
public class RuleWebhookNotificationSender implements RuleNotificationSender {

    /** support 共用真实 Webhook 发送器。 */
    private final ExternalNotificationSender sender;

    /** @param sender 共享 Webhook 渠道发送器 */
    public RuleWebhookNotificationSender(
            @Qualifier(ExternalNotificationSender.WEBHOOK_BEAN) ExternalNotificationSender sender) {
        this.sender = sender;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public String channel() {
        return "WEBHOOK";
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public String send(RuleNotificationDelivery delivery) {
        try {
            return sender.send(new ExternalNotificationRequest(
                    delivery.id(), delivery.projectId(), delivery.recipient(),
                    delivery.subject(), delivery.body(), Map.of(
                            "traceId", delivery.traceId(),
                            "subject", delivery.subject(),
                            "content", delivery.body())));
        } catch (ExternalNotificationException exception) {
            throw mapped(exception);
        }
    }

    /** @return 保留共用低基数原因与可恢复性的规则域异常 */
    private static RuleNotificationSendException mapped(ExternalNotificationException exception) {
        return new RuleNotificationSendException(
                RuleNotificationSendException.Reason.valueOf(exception.reason().name()),
                exception.retryable(), exception);
    }
}
