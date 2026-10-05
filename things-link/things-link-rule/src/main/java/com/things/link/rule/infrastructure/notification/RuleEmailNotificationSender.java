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

/** 把规则冻结快照适配到 S6/S9 共用邮件发送端口；本类不持有 SMTP 客户端。 */
@Component
public class RuleEmailNotificationSender implements RuleNotificationSender {

    /** support 共用真实邮件发送器。 */
    private final ExternalNotificationSender sender;

    /** @param sender 共享邮件渠道发送器 */
    public RuleEmailNotificationSender(
            @Qualifier(ExternalNotificationSender.EMAIL_BEAN) ExternalNotificationSender sender) {
        this.sender = sender;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public String channel() {
        return "EMAIL";
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public String send(RuleNotificationDelivery delivery) {
        try {
            return sender.send(new ExternalNotificationRequest(
                    delivery.id(), delivery.projectId(), delivery.recipient(),
                    delivery.subject(), delivery.body(), Map.of()));
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
