package com.things.link.alarm.infrastructure.notification;

import com.things.link.alarm.application.AlarmPushDeliveryAuthorizationPort;
import com.things.link.alarm.application.NotificationSendException;
import com.things.link.alarm.application.PushNotificationSender;
import com.things.link.alarm.domain.AlarmNotificationDelivery;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * S11-4 确定性 PUSH 厂商桩。
 *
 * <p>仅在 test/development profile 装配，且只接受 A2a 明确保留的 MOCK provider；生产环境和真实厂商
 * provider 绝不能由桩冒充送达。固定 token 用于稳定覆盖成功、可重试和永久拒绝，不记录明文。
 */
@Component
@Profile({"test", "development"})
public class DeterministicPushNotificationSender implements PushNotificationSender {

    /** 触发可恢复供应商失败的测试 token。 */
    public static final String RETRYABLE_TOKEN = "mock:retryable";
    /** 触发永久供应商拒绝的测试 token。 */
    public static final String PERMANENT_TOKEN = "mock:permanent";
    /** 本桩唯一允许的 provider。 */
    private static final String MOCK_PROVIDER = "MOCK";

    /** 沿用接口定义的契约。{@inheritDoc} */
    @Override
    public String send(
            AlarmNotificationDelivery delivery,
            AlarmPushDeliveryAuthorizationPort.AuthorizedPushTarget target) {
        if (delivery.channel() != com.things.link.alarm.domain.NotificationChannel.PUSH
                || !MOCK_PROVIDER.equals(target.provider())) {
            throw new NotificationSendException(
                    NotificationSendException.Reason.PUSH_PROVIDER_UNAVAILABLE, false, null);
        }
        if (RETRYABLE_TOKEN.equals(target.plainToken())) {
            throw new NotificationSendException(
                    NotificationSendException.Reason.PUSH_PROVIDER_TRANSIENT, true, null);
        }
        if (PERMANENT_TOKEN.equals(target.plainToken())) {
            throw new NotificationSendException(
                    NotificationSendException.Reason.PUSH_PROVIDER_REJECTED, false, null);
        }
        return "mock-push-" + delivery.id();
    }
}
