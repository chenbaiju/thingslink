package com.things.link.rule.infrastructure;

import com.things.link.project.application.EffectiveQuotaPolicyProvider;
import com.things.link.rule.application.outbox.RuleSideEffectOutboxBridge;
import com.things.link.rule.application.queue.RuleExecutionCoordinator;
import com.things.link.rule.application.queue.RuleExecutionProcessor;
import com.things.link.rule.application.queue.RuleExecutionReceiptStore;
import com.things.link.rule.application.queue.RuleExecutionSuccessPublisher;
import com.things.link.rule.application.queue.RuleExecutionLogStore;
import com.things.link.rule.application.queue.RuleQueueMetrics;
import com.things.link.rule.application.queue.RuleQuotaGate;
import com.things.link.rule.application.queue.RuleRecoveryPublisher;
import com.things.link.rule.application.queue.RuleRetryPolicy;
import com.things.link.rule.application.queue.TwoLevelRuleFairScheduler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.ContainerPartitionPausingBackOffManager;
import org.springframework.kafka.listener.ContainerPausingBackOffHandler;
import org.springframework.kafka.listener.KafkaConsumerBackoffManager;
import org.springframework.kafka.listener.ListenerContainerPauseService;
import org.springframework.scheduling.TaskScheduler;
import com.things.link.rule.infrastructure.messaging.KafkaRuleRetryConsumer;
import com.things.link.rule.infrastructure.messaging.RuleExecutionEnvelopeJacksonDeserializer;
import com.things.link.rule.infrastructure.messaging.RuleDeviceCommandTerminalConsumer;
import com.things.link.rule.application.outbox.RuleDeviceActionDeliveryStore;
import com.things.link.support.fault.FaultInjectionCheckpoint;
import com.things.link.support.kafka.KafkaTraceRecordInterceptor;
import com.things.link.support.observability.KafkaConsumerProcessingMetrics;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.kafka.autoconfigure.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;

/** S8-2B 队列闭环装配；生产处理器与日额度门禁由 S8-2C 接入上行时提供。 */
@Configuration(proxyBeanMethods = false)
public class RuleQueueConfiguration {

    /** retry listener 与数据转换链相同的单 poll 上限，避免一次拉取过多延迟信封占用恢复窗口。 */
    private static final int RULE_RETRY_MAX_POLL_RECORDS = 50;

    /**
     * 为规则 retry Topic 建立 Jackson 3 专用反序列化边界。
     *
     * <p>全局消费者必须继续读取共享 Jackson 2 数据契约；只有规则信封包含 Jackson 3 {@code JsonNode}，因此不能
     * 通过替换全局 deserializer 修复。{@link ErrorHandlingDeserializer} 把畸形记录转换为容器可处理的失败记录，避免
     * 原生 {@code SerializationException} 在同一 offset 热循环。</p>
     *
     * @param configurer Boot listener 的 ack 与容器公共配置
     * @param baseConsumerFactory Boot 已解析的 Kafka 连接配置
     * @param objectMapper 项目统一 Jackson 3 映射器
     * @param metrics 逐记录低基数处理指标
     * @return 仅供两个规则 retry Topic 使用的隔离 listener 工厂
     */
    @Bean(name = "ruleRetryKafkaListenerContainerFactory")
    ConcurrentKafkaListenerContainerFactory<Object, Object> ruleRetryKafkaListenerContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<Object, Object> baseConsumerFactory,
            ObjectMapper objectMapper,
            KafkaConsumerProcessingMetrics metrics) {
        Map<String, Object> properties = new HashMap<>(baseConsumerFactory.getConfigurationProperties());
        properties.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, RULE_RETRY_MAX_POLL_RECORDS);
        Deserializer<Object> keyDeserializer = new Utf8KafkaKeyDeserializer();
        Deserializer<Object> valueDeserializer = new ErrorHandlingDeserializer<>(
                new RuleExecutionEnvelopeJacksonDeserializer(objectMapper));
        DefaultKafkaConsumerFactory<Object, Object> isolatedConsumerFactory =
                new DefaultKafkaConsumerFactory<>(properties, keyDeserializer, valueDeserializer);
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        configurer.configure(factory, isolatedConsumerFactory);
        factory.setRecordInterceptor(new KafkaTraceRecordInterceptor(metrics));
        // 未到期分区已由 backoff manager 暂停；专用处理器必须保留当前 offset，不能沿用默认“零重试即恢复”。
        factory.setCommonErrorHandler(new RuleRetryKafkaErrorHandler());
        return factory;
    }

    /**
     * @return 可测试的规则执行 UTC 时钟，动作节点用它固定本次执行开始时刻
     *
     * <p>标记 {@code @Primary}：alarm 模块已有 {@code notificationClock}（同为 {@code systemUTC()}），
     * 全量上下文里两个同型 {@link Clock} 会让裸 {@code Clock} 注入歧义。规则模块保持自带时钟不反向依赖
     * alarm，故以主 bean 身份收敛默认注入；两钟等值，不改变任何既有行为。</p>
     */
    @Bean
    @Primary
    Clock ruleClock() {
        return Clock.systemUTC();
    }

    /** @return 租户外层、项目内层的实例级有界调度器 */
    @Bean
    TwoLevelRuleFairScheduler twoLevelRuleFairScheduler() {
        return new TwoLevelRuleFairScheduler();
    }

    /** @return 冻结为三次尝试、1m/5m 两档的恢复策略 */
    @Bean
    RuleRetryPolicy ruleRetryPolicy() {
        return new RuleRetryPolicy();
    }

    /**
     * 只有 S8-2C 同时提供实际处理器和日额度门禁时才创建生产入口，避免本片提前接上行。
     *
     * @param policyProvider 有效套餐端口
     * @param quotaGate 两项脚本日额度门禁
     * @param scheduler 两级公平调度器
     * @param receiptStore 持久幂等回执
     * @param processor 实际规则处理器
     * @param recoveryPublisher Kafka 恢复发布器
     * @param retryPolicy 有限退避策略
     * @param metrics 队列指标
     * @param faultCheckpoint G1-C4b 一次性故障检查点，默认关闭
     * @return 唯一生产规则队列入口
     */
    @Bean
    @ConditionalOnBean({RuleQuotaGate.class, RuleExecutionProcessor.class})
    RuleExecutionCoordinator ruleExecutionCoordinator(
            EffectiveQuotaPolicyProvider policyProvider,
            RuleQuotaGate quotaGate,
            TwoLevelRuleFairScheduler scheduler,
            RuleExecutionReceiptStore receiptStore,
            RuleExecutionProcessor processor,
            RuleExecutionSuccessPublisher successPublisher,
            RuleExecutionLogStore logStore,
            RuleSideEffectOutboxBridge bridge,
            RuleRecoveryPublisher recoveryPublisher,
            RuleRetryPolicy retryPolicy,
            RuleQueueMetrics metrics,
            FaultInjectionCheckpoint faultCheckpoint) {
        return new RuleExecutionCoordinator(policyProvider, quotaGate, scheduler, receiptStore,
                processor, successPublisher, logStore, bridge, recoveryPublisher, retryPolicy, metrics,
                faultCheckpoint, Clock.systemUTC());
    }

    /** @return 按 partition 暂停并定时恢复的非阻塞退避管理器 */
    @Bean
    KafkaConsumerBackoffManager ruleKafkaConsumerBackoffManager(
            KafkaListenerEndpointRegistry registry,
            @Qualifier("maintenanceScheduler") TaskScheduler taskScheduler) {
        ListenerContainerPauseService pauseService = new ListenerContainerPauseService(registry, taskScheduler);
        return new ContainerPartitionPausingBackOffManager(registry,
                new ContainerPausingBackOffHandler(pauseService));
    }

    /** 只有生产协调器存在时才启动两个规则 retry Topic 的消费者。 */
    @Bean
    @ConditionalOnBean(RuleExecutionCoordinator.class)
    KafkaRuleRetryConsumer kafkaRuleRetryConsumer(
            RuleExecutionCoordinator coordinator, KafkaConsumerBackoffManager backoffManager) {
        return new KafkaRuleRetryConsumer(coordinator, backoffManager, Clock.systemUTC());
    }

    /** @return 设备操作终态回写消费者；只更新 rule 自有投递事实。 */
    @Bean
    RuleDeviceCommandTerminalConsumer ruleDeviceCommandTerminalConsumer(RuleDeviceActionDeliveryStore store) {
        return new RuleDeviceCommandTerminalConsumer(store);
    }

    /** 把标准 UTF-8 key deserializer 收窄适配为 Boot 工厂的 Object 泛型，不复制字符串解码逻辑。 */
    private static final class Utf8KafkaKeyDeserializer implements Deserializer<Object> {

        /** Kafka 官方字符串解码器；配置与 close 生命周期必须原样转发。 */
        private final StringDeserializer delegate = new StringDeserializer();

        /** 沿用接口定义的契约。{@inheritDoc} */
        @Override
        public void configure(Map<String, ?> configs, boolean isKey) {
            delegate.configure(configs, isKey);
        }

        /** 沿用接口定义的契约。{@inheritDoc} */
        @Override
        public Object deserialize(String topic, byte[] data) {
            return delegate.deserialize(topic, data);
        }

        /** 沿用接口定义的契约。{@inheritDoc} */
        @Override
        public void close() {
            delegate.close();
        }
    }
}
