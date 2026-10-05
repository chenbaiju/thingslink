package com.things.link.alarm.infrastructure.notification;

import com.things.link.alarm.application.NotificationChannelSender;
import com.things.link.alarm.application.NotificationSendException;
import com.things.link.alarm.domain.AlarmNotificationDelivery;
import com.things.link.alarm.domain.NotificationChannel;
import com.things.link.support.notification.delivery.ExternalNotificationException;
import com.things.link.support.notification.delivery.ExternalNotificationRequest;
import com.things.link.support.notification.delivery.ExternalNotificationSender;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Map;

/** 把告警冻结快照适配到共用真实邮件端口；SMTP 设施由 support 唯一实现。 */
@Component
public class EmailNotificationSender implements NotificationChannelSender {

    /** 告警与规则共用的邮件发送器。 */
    private final ExternalNotificationSender sender;

    /** @param sender support 共用真实邮件发送器 */
    public EmailNotificationSender(
            @Qualifier(ExternalNotificationSender.EMAIL_BEAN) ExternalNotificationSender sender) {
        this.sender = sender;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public NotificationChannel channel() {
        return NotificationChannel.EMAIL;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public String send(AlarmNotificationDelivery delivery) {
        try {
            return sender.send(new ExternalNotificationRequest(
                    delivery.id(), delivery.projectId(), delivery.targetSnapshot(),
                    delivery.subjectSnapshot(), delivery.bodySnapshot(), Map.of()));
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
