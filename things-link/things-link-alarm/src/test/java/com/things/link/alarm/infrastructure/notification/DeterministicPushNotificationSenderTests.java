package com.things.link.alarm.infrastructure.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.things.link.alarm.application.AlarmPushDeliveryAuthorizationPort;
import com.things.link.alarm.application.NotificationSendException;
import com.things.link.alarm.domain.AlarmNotificationDelivery;
import com.things.link.alarm.domain.NotificationChannel;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

/** 确定性 PUSH 桩验证成功、可重试、永久拒绝与真实 provider 禁用边界。 */
class DeterministicPushNotificationSenderTests {

    /** 被测 sender。 */
    private final DeterministicPushNotificationSender sender =
            new DeterministicPushNotificationSender();

    /** 普通 MOCK token 返回不含敏感目标的稳定消息 ID。 */
    @Test
    void returnsStableProviderIdWithoutToken() {
        AlarmNotificationDelivery delivery = delivery();
        String providerId = sender.send(delivery, target("MOCK", "sensitive-device-token"));

        assertThat(providerId).isEqualTo("mock-push-" + delivery.id());
        assertThat(providerId).doesNotContain("sensitive-device-token");
    }

    /** 固定可恢复 token 只产生低基数可重试分类。 */
    @Test
    void classifiesRetryableFailure() {
        assertThatThrownBy(() -> sender.send(
                        delivery(), target("MOCK", DeterministicPushNotificationSender.RETRYABLE_TOKEN)))
                .isInstanceOfSatisfying(NotificationSendException.class, failure -> {
                    assertThat(failure.reason())
                            .isEqualTo(NotificationSendException.Reason.PUSH_PROVIDER_TRANSIENT);
                    assertThat(failure.retryable()).isTrue();
                });
    }

    /** 固定永久拒绝 token 不得进入重试。 */
    @Test
    void classifiesPermanentFailure() {
        assertThatThrownBy(() -> sender.send(
                        delivery(), target("MOCK", DeterministicPushNotificationSender.PERMANENT_TOKEN)))
                .isInstanceOfSatisfying(NotificationSendException.class, failure -> {
                    assertThat(failure.reason())
                            .isEqualTo(NotificationSendException.Reason.PUSH_PROVIDER_REJECTED);
                    assertThat(failure.retryable()).isFalse();
                });
    }

    /** 确定性桩不能把真实厂商 provider 冒充为已送达。 */
    @Test
    void rejectsRealProvider() {
        assertThatThrownBy(() -> sender.send(delivery(), target("HUAWEI", "real-token")))
                .isInstanceOfSatisfying(NotificationSendException.class, failure -> {
                    assertThat(failure.reason())
                            .isEqualTo(NotificationSendException.Reason.PUSH_PROVIDER_UNAVAILABLE);
                    assertThat(failure.retryable()).isFalse();
                });
    }

    /** @return 固定 PUSH 投递事实 */
    private static AlarmNotificationDelivery delivery() {
        Instant now = Instant.parse("2026-09-01T00:00:00Z");
        return new AlarmNotificationDelivery(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), null, UUID.randomUUID(), UUID.randomUUID(),
                NotificationChannel.PUSH, "PUSH", "subject", "body", 1,
                AlarmNotificationDelivery.Status.SENDING, 1, 3, now.plusSeconds(30),
                UUID.randomUUID(), null, null, now, now, null);
    }

    /** @return 不会通过 toString 泄露 token 的授权目标 */
    private static AlarmPushDeliveryAuthorizationPort.AuthorizedPushTarget target(
            String provider, String token) {
        return new AlarmPushDeliveryAuthorizationPort.AuthorizedPushTarget(provider, token);
    }
}
