package com.things.link.rule.infrastructure.messaging;

import com.things.link.rule.application.queue.RuleExecutionEnvelope;
import com.things.link.rule.application.queue.RuleExecutionFailure;
import com.things.link.rule.application.queue.RuleRecoveryPublisher;
import com.things.link.rule.application.queue.RuleRetryPolicy;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/** 把有限退避决定映射到固定规则 Topic，并等待 broker 确认后返回。 */
@Component
public class KafkaRuleRecoveryPublisher implements RuleRecoveryPublisher {

    /** 第一次失败的固定一分钟退避 Topic。 */
    public static final String RETRY_ONE_MINUTE_TOPIC = "tc.rule.retry.1m";
    /** 第二次失败的固定五分钟退避 Topic。 */
    public static final String RETRY_FIVE_MINUTES_TOPIC = "tc.rule.retry.5m";
    /** 规则永久失败及重试耗尽专用 DLQ。 */
    public static final String DEAD_LETTER_TOPIC = "tc.rule.dlq";
    /** 继承 acks=all、幂等 producer 与 trace interceptor，但把 value 序列化换成 Jackson 3 的恢复专用模板。 */
    private final KafkaTemplate<String, Object> kafkaTemplate;

    /**
     * 用全局 producer 配置派生一个只在本恢复路径使用的 Jackson 3 模板。
     *
     * <p>不能注册第二个 {@code KafkaTemplate} bean：全仓有数十处裸 {@code KafkaTemplate<String,Object>}
     * 注入，新增同型 bean 会让它们全部歧义。这里在构造期从 Boot 默认模板的 producer 配置拷贝出连接、幂等与
     * trace interceptor，仅替换 value 序列化器为 {@link RuleExecutionEnvelopeJacksonSerializer}。</p>
     *
     * @param baseTemplate Boot 统一 Kafka 模板，仅用于读取已解析的 producer 配置
     * @param objectMapper 项目统一 Jackson 3 映射器
     */
    public KafkaRuleRecoveryPublisher(KafkaTemplate<String, Object> baseTemplate, ObjectMapper objectMapper) {
        Map<String, Object> producerConfig = baseTemplate.getProducerFactory().getConfigurationProperties();
        DefaultKafkaProducerFactory<String, Object> isolatedFactory =
                new DefaultKafkaProducerFactory<>(producerConfig, new StringSerializer(),
                        new RuleExecutionEnvelopeJacksonSerializer(objectMapper));
        this.kafkaTemplate = new KafkaTemplate<>(isolatedFactory);
    }

    /** {@inheritDoc} */
    @Override
    public void publishRetry(RuleExecutionEnvelope envelope, Duration delay) {
        String topic = topic(delay);
        kafkaTemplate.send(topic, envelope.key().messageId().toString(), envelope).join();
    }

    /** {@inheritDoc} */
    @Override
    public void publishDeadLetter(RuleExecutionEnvelope envelope, RuleExecutionFailure failure) {
        kafkaTemplate.send(DEAD_LETTER_TOPIC, envelope.key().messageId().toString(),
                new RuleDeadLetterMessage(envelope, failure)).join();
    }

    /** @param delay 仅允许架构冻结的两个档位 @return 对应固定 Topic */
    private static String topic(Duration delay) {
        Objects.requireNonNull(delay, "delay");
        if (delay.equals(RuleRetryPolicy.FIRST_RETRY_DELAY)) {
            return RETRY_ONE_MINUTE_TOPIC;
        }
        if (delay.equals(RuleRetryPolicy.SECOND_RETRY_DELAY)) {
            return RETRY_FIVE_MINUTES_TOPIC;
        }
        throw new IllegalArgumentException("不支持的规则重试退避档位");
    }

    /**
     * 规则 DLQ 只携带不可变原信封和封闭原因，不序列化异常正文。
     *
     * @param envelope 原规则执行信封
     * @param failure 固定失败分类
     */
    public record RuleDeadLetterMessage(RuleExecutionEnvelope envelope, RuleExecutionFailure failure) {
        /** 拒绝不完整死信，避免运维重放时丢失身份。 */
        public RuleDeadLetterMessage {
            Objects.requireNonNull(envelope, "envelope");
            Objects.requireNonNull(failure, "failure");
        }
    }
}
