package com.things.link.rule.infrastructure.messaging;

import com.things.link.rule.application.RuleNotificationDeliveryService;
import com.things.link.shared.message.RuleNotificationDeliveryRequest;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * {@code tc.rule.notification} 的规则通知消费者；只校验 Kafka 身份并交给 S9-3 投递状态机。
 *
 * <p>渠道预期失败由服务落 RETRY_SCHEDULED/DEAD_LETTER 后正常提交 offset，不能依赖 Kafka 无界重放。
 * 只有信封或分区键被篡改才抛异常，避免为错误项目创建投递事实。</p>
 */
@Component
public class RuleNotificationDeliveryKafkaConsumer {

    /** 投递事实应用服务。 */
    private final RuleNotificationDeliveryService service;

    /** @param service 投递事实应用服务 */
    public RuleNotificationDeliveryKafkaConsumer(RuleNotificationDeliveryService service) {
        this.service = service;
    }

    /**
     * Kafka key 必须是 eventId，避免错误分区键破坏同一投递事实的幂等去重。
     *
     * @param request 规则 Outbox 冻结的投递信封
     * @param key Kafka 实际分区键
     */
    @KafkaListener(topics = "tc.rule.notification", groupId = "${things-link.kafka.group-prefix:things-link}-rule-notification",
            containerFactory = "notificationIngressKafkaListenerContainerFactory",
            concurrency = "${things-link.kafka.concurrency.rule-notification:3}")
    public void consume(
            RuleNotificationDeliveryRequest request,
            @Header(KafkaHeaders.RECEIVED_KEY) String key) {
        if (request == null || request.eventId() == null
                || !request.eventId().toString().equals(key)) {
            throw new IllegalArgumentException("规则通知消息分区键必须等于 eventId");
        }
        service.accept(request);
    }
}
