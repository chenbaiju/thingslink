package com.things.link.support.notification.delivery;

import com.things.link.support.notification.mail.LoggingMailSender;
import com.things.link.support.notification.mail.MailConfiguration;
import com.things.link.support.notification.mail.MailDeliveryException;
import com.things.link.support.notification.mail.MailMessage;
import com.things.link.support.notification.mail.MailSender;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/** 告警与规则共用的平台 SMTP 发送器；开发日志实现明确失败，不能冒充真实送达。 */
@Component(ExternalNotificationSender.EMAIL_BEAN)
public class ExternalEmailNotificationSender implements ExternalNotificationSender {

    /** 发送边界拒绝空白、空格和明显缺失的邮箱结构。 */
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    /** support 既有统一出站邮件端口。 */
    private final MailSender mailSender;

    /** @param mailSender 根据 SMTP 配置选择的真实或开发发送器 */
    public ExternalEmailNotificationSender(
            @Qualifier(MailConfiguration.OUTBOUND_MAIL_SENDER) MailSender mailSender) {
        this.mailSender = mailSender;
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public String channel() {
        return "EMAIL";
    }

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public String send(ExternalNotificationRequest request) {
        if (request == null || request.deliveryId() == null
                || request.target() == null || !EMAIL.matcher(request.target()).matches()
                || request.subject() == null || request.subject().isBlank()
                || request.body() == null || request.body().isBlank()) {
            throw failure(ExternalNotificationException.Reason.INVALID_DELIVERY, false, null);
        }
        if (mailSender instanceof LoggingMailSender) {
            throw failure(ExternalNotificationException.Reason.SMTP_UNCONFIGURED, true, null);
        }
        try {
            mailSender.send(MailMessage.text(request.target(), request.subject(), request.body()));
            return "email:" + request.deliveryId();
        } catch (MailDeliveryException exception) {
            throw failure(ExternalNotificationException.Reason.SMTP_FAILURE, true, exception);
        }
    }

    /** @return 不携带目标或正文的固定失败 */
    private static ExternalNotificationException failure(
            ExternalNotificationException.Reason reason,
            boolean retryable,
            Throwable cause) {
        return new ExternalNotificationException(reason, retryable, cause);
    }
}
