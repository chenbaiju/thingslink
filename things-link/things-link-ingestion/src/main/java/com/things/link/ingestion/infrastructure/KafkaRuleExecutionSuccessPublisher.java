package com.things.link.ingestion.infrastructure;

import com.things.link.rule.application.engine.RuleMessage;
import com.things.link.rule.application.queue.RuleExecutionEnvelope;
import com.things.link.rule.application.queue.RuleExecutionSuccessPublisher;
import com.things.link.shared.message.StandardUplinkMessage;
import org.springframework.kafka.core.KafkaTemplate;

/** 把最终规则 payload 恢复成完整标准信封并同步确认 processed Topic。 */
public final class KafkaRuleExecutionSuccessPublisher implements RuleExecutionSuccessPublisher {

    /** 继承 acks=all 与幂等 producer 的统一模板。 */
    private final KafkaTemplate<String, Object> kafkaTemplate;
    /** 保护 ADR 0017 可信字段的映射器。 */
    private final RuleUplinkMessageMapper messageMapper;

    /** @param kafkaTemplate Kafka 模板 @param messageMapper 标准信封映射器 */
    public KafkaRuleExecutionSuccessPublisher(
            KafkaTemplate<String, Object> kafkaTemplate,
            RuleUplinkMessageMapper messageMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.messageMapper = messageMapper;
    }

    /** {@inheritDoc} */
    @Override
    public void publish(RuleExecutionEnvelope envelope, RuleMessage transformedMessage) {
        StandardUplinkMessage processed = messageMapper.toStandardMessage(envelope.message(), transformedMessage);
        kafkaTemplate.send(ProcessedUplinkKafkaConsumer.PROCESSED_UPLINK_TOPIC,
                processed.deviceId().toString(), processed).join();
    }
}
