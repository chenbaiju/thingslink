package com.things.link.rule.infrastructure.messaging;

import com.things.link.rule.application.outbox.RuleDeviceActionDeliveryStore;
import com.things.link.shared.message.DeviceCommandTerminalEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;

/** 消费设备域终态事实，并按 commandId 回写规则动作；重复事件由数据库 CAS 吸收。 */
public final class RuleDeviceCommandTerminalConsumer {
    /** 固定低流量终态主题。 */ public static final String TOPIC = "tc.device.command.terminal";
    /** 规则动作投递事实端口。 */ private final RuleDeviceActionDeliveryStore store;
    /** @param store 动作投递事实端口 */
    public RuleDeviceCommandTerminalConsumer(RuleDeviceActionDeliveryStore store) { this.store = store; }
    /** @param record 终态事件，key 必须是 commandId */
    @KafkaListener(topics = TOPIC, groupId = "${things-link.kafka.group-prefix:things-link}-rule-device-terminal",
            containerFactory = "factKafkaListenerContainerFactory",
            concurrency = "${things-link.kafka.concurrency.rule-device-terminal:2}")
    public void consume(ConsumerRecord<String, DeviceCommandTerminalEvent> record) {
        DeviceCommandTerminalEvent event = record.value();
        if (event == null || !event.commandId().toString().equals(record.key())) {
            throw new IllegalArgumentException("设备终态事件及 commandId 分区键不合法");
        }
        store.complete(event);
    }
}
