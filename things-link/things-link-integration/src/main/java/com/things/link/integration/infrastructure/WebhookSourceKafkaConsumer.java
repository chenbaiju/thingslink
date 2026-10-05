package com.things.link.integration.infrastructure;

import com.things.link.integration.application.WebhookEventAdmission;
import com.things.link.shared.message.PublicWebhookSource;
import com.things.link.support.tenant.DataPlaneDatabase;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** 可信内部主题、原始字节及脱敏故障；返回前必须提交准入事务。 */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="things-link.integration.webhook.enabled",havingValue="true")
@DataPlaneDatabase
public class WebhookSourceKafkaConsumer {
    private final ObjectMapper json;
    private final WebhookEventAdmission admission;
    public WebhookSourceKafkaConsumer(ObjectMapper json,WebhookEventAdmission admission){this.json=json;this.admission=admission;}
    @KafkaListener(id="public-webhook-source",topics=PublicWebhookSource.TOPIC,groupId="${things-link.kafka.group-prefix:things-link}-integration-webhook-source",
        containerFactory="webhookSourceKafkaListenerContainerFactory",autoStartup="${spring.kafka.listener.auto-startup:true}",
        concurrency="${things-link.kafka.concurrency.webhook-source:3}")
    public void consume(ConsumerRecord<byte[],byte[]> record){
        try {
            if(!PublicWebhookSource.TOPIC.equals(record.topic())||record.value()==null||record.value().length>1_048_576)
                throw new IllegalArgumentException();
            var source=json.readValue(record.value(),PublicWebhookSource.class);
            if(!Arrays.equals(record.key(),source.aggregateId().toString().getBytes(StandardCharsets.UTF_8)))throw new IllegalArgumentException();
            admission.acceptSource(source);
        } catch(RuntimeException failure){
            // Jackson 或 SQL 异常可能携带输入；跨越 Kafka 日志边界时刻意省略原始异常原因。
            throw new IllegalStateException("WEBHOOK_SOURCE_NOT_ADMITTED");
        }
    }
}
