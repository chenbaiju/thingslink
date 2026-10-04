package com.things.link.alarm.infrastructure.notification;

import com.things.link.alarm.application.NotificationDeliveryExecutionService;
import com.things.link.shared.message.NotificationDeliveryRequest;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/** tc.notification 的告警投递消费者；业务失败已在 delivery 事实中收口。 */
@Component
public class NotificationDeliveryKafkaConsumer {
    /** 状态机和真实适配器编排。 */
    private final NotificationDeliveryExecutionService service;

    /** @param service 投递执行服务 */
    public NotificationDeliveryKafkaConsumer(NotificationDeliveryExecutionService service) {
        this.service = service;
    }

    /** Kafka key 必须是 deliveryId，避免错误分区键破坏同一投递的尝试顺序。 */
    @KafkaListener(topics = "tc.notification", groupId = "${things-link.kafka.group-prefix:things-link}-notification-delivery",
            containerFactory = "notificationIngressKafkaListenerContainerFactory",
            concurrency = "${things-link.kafka.concurrency.notification-delivery:3}")
    public void consume(
            NotificationDeliveryRequest request,
            @Header(KafkaHeaders.RECEIVED_KEY) String key) {
        if (request == null || request.deliveryId() == null
                || !request.deliveryId().toString().equals(key)) {
            throw new IllegalArgumentException("通知消息分区键必须等于 deliveryId");
        }
        service.accept(request);
    }
}
