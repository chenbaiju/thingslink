package com.things.link.alarm.infrastructure.notification;

import com.things.link.alarm.application.NotificationSendException;
import com.things.link.alarm.domain.AlarmNotificationDelivery;
import com.things.link.alarm.domain.NotificationChannel;
import com.things.link.support.notification.delivery.ExternalNotificationException;
import com.things.link.support.notification.delivery.ExternalNotificationRequest;
import com.things.link.support.notification.delivery.ExternalNotificationSender;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 告警渠道薄适配器验证冻结快照转换与共用失败分类映射。 */
class NotificationChannelSenderTests {

    /** 邮件适配器只转换快照，并保留共用发送器的供应商 ID。 */
    @Test
    void delegatesFrozenMailSnapshot() {
        ExternalNotificationSender shared = mock(ExternalNotificationSender.class);
        AlarmNotificationDelivery delivery = delivery(NotificationChannel.EMAIL, "ops@example.com");
        when(shared.send(any())).thenReturn("email:" + delivery.id());

        String providerId = new EmailNotificationSender(shared).send(delivery);

        assertThat(providerId).isEqualTo("email:" + delivery.id());
        verify(shared).send(new ExternalNotificationRequest(
                delivery.id(), delivery.projectId(), "ops@example.com", "主题", "正文",
                java.util.Map.of()));
    }

    /** 共用 SMTP 未配置原因必须原样映射为告警域可恢复失败。 */
    @Test
    void mapsSharedMailFailure() {
        ExternalNotificationSender shared = mock(ExternalNotificationSender.class);
        when(shared.send(any())).thenThrow(new ExternalNotificationException(
                ExternalNotificationException.Reason.SMTP_UNCONFIGURED, true, null));

        assertThatThrownBy(() -> new EmailNotificationSender(shared)
                .send(delivery(NotificationChannel.EMAIL, "ops@example.com")))
                .isInstanceOf(NotificationSendException.class)
                .satisfies(error -> {
                    NotificationSendException failure = (NotificationSendException) error;
                    assertThat(failure.reason())
                            .isEqualTo(NotificationSendException.Reason.SMTP_UNCONFIGURED);
                    assertThat(failure.retryable()).isTrue();
                });
    }

    /** Webhook 429 的可恢复性跨共用端口映射时不得丢失。 */
    @Test
    void mapsSharedWebhookRateLimit() {
        ExternalNotificationSender shared = mock(ExternalNotificationSender.class);
        when(shared.send(any())).thenThrow(new ExternalNotificationException(
                ExternalNotificationException.Reason.WEBHOOK_RATE_LIMITED, true, null));

        assertThatThrownBy(() -> new WebhookNotificationSender(shared)
                .send(delivery(NotificationChannel.WEBHOOK, "https://example.com/hook")))
                .isInstanceOf(NotificationSendException.class)
                .satisfies(error -> {
                    NotificationSendException failure = (NotificationSendException) error;
                    assertThat(failure.reason())
                            .isEqualTo(NotificationSendException.Reason.WEBHOOK_RATE_LIMITED);
                    assertThat(failure.retryable()).isTrue();
                });
    }

    /** @return 具备完整冻结事实的投递 */
    private static AlarmNotificationDelivery delivery(NotificationChannel channel, String target) {
        Instant now = Instant.parse("2026-08-09T12:00:00Z");
        return new AlarmNotificationDelivery(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null, null,
                channel, target, "主题", "正文",
                1, AlarmNotificationDelivery.Status.SENDING, 1, 3, null, UUID.randomUUID(), null, null,
                now, now, null);
    }
}
