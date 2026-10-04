package com.things.link.rule.infrastructure.messaging;

import com.things.link.rule.application.automation.AutomationEventIngress;
import com.things.link.shared.message.AutomationPropertyAccepted;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** 原始bytes直达持久拒绝边界；工作包整体验收前默认不开启。 */
@Component
public class AutomationPropertyKafkaConsumer {
    private final AutomationEventIngress ingress;
    public AutomationPropertyKafkaConsumer(AutomationEventIngress ingress){this.ingress=ingress;}
    @KafkaListener(id="automation-property",topics=AutomationPropertyAccepted.TOPIC,groupId="${things-link.kafka.group-prefix:things-link}-rule-automation-property",
            containerFactory="automationPropertyKafkaListenerContainerFactory",
            autoStartup="${things-link.automation.property.enabled:false}",concurrency="${things-link.kafka.concurrency.automation-property:3}")
    public void consume(ConsumerRecord<byte[],byte[]> record){
        ingress.accept(record.key(),record.value(),record.partition(),record.offset());
    }
}
