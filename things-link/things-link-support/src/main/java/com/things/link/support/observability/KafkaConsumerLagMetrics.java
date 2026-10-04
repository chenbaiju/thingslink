package com.things.link.support.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.common.Metric;
import org.apache.kafka.common.MetricName;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 把 Kafka 客户端的本地 lag 观测按消费组暴露为稳定指标。
 *
 * <p>Spring Kafka 已持有每个 listener 的原生 consumer metrics；直接读取这些指标不会再创建
 * AdminClient，也不会为每次 Prometheus 抓取向 broker 发送 offset 查询。原生指标只带 client-id，
 * 本组件补上架构文档 13.1 要求的 group 维度，告警不必解析易变的客户端命名规则。</p>
 */
@Component
public class KafkaConsumerLagMetrics implements ApplicationListener<ApplicationReadyEvent> {

    /** Prometheus 中的消费组 lag 指标。 */
    static final String CONSUMER_LAG = "thingslink.kafka.consumer.lag";
    /** 当前容器配置的并发 consumer 数。 */
    static final String CONFIGURED_CONCURRENCY = "thingslink.kafka.consumer.configured";
    /** 当前实例实际获得的分区数。 */
    static final String ASSIGNED_PARTITIONS = "thingslink.kafka.consumer.assigned";
    /** Kafka 原生每个 consumer 的最大记录积压指标名。 */
    private static final String KAFKA_LAG_MAX = "records-lag-max";

    /** listener 注册中心，可覆盖当前与 S4 后续新增的容器。 */
    private final KafkaListenerEndpointRegistry endpointRegistry;
    /** 可选的应用统一指标注册表；不加载 Actuator 的模块隔离测试不会暴露 lag。 */
    private final MeterRegistry meterRegistry;

    /**
     * @param endpointRegistry Kafka listener 注册中心
     * @param meterRegistryProvider 可选的应用统一指标注册表
     */
    @Autowired
    public KafkaConsumerLagMetrics(
            KafkaListenerEndpointRegistry endpointRegistry,
            ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.endpointRegistry = endpointRegistry;
        this.meterRegistry = meterRegistryProvider.getIfAvailable();
    }

    /**
     * 显式注册表入口，供单元测试验证 Gauge 聚合。
     *
     * @param endpointRegistry Kafka listener 注册中心
     * @param meterRegistry 应用统一指标注册表
     */
    KafkaConsumerLagMetrics(KafkaListenerEndpointRegistry endpointRegistry, MeterRegistry meterRegistry) {
        this.endpointRegistry = endpointRegistry;
        this.meterRegistry = meterRegistry;
    }

    /**
     * 应用就绪时 listener 已经完成注册，此时为每个消费组建立一个抓取时求值的 Gauge。
     *
     * @param event 应用就绪事件
     */
    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (meterRegistry == null) {
            return;
        }
        Set<String> registeredGroups = new HashSet<>();
        for (MessageListenerContainer container : endpointRegistry.getListenerContainers()) {
            String groupId = container.getGroupId();
            if (groupId == null || !registeredGroups.add(groupId)) {
                continue;
            }
            Gauge.builder(CONSUMER_LAG, container, KafkaConsumerLagMetrics::maxLag)
                    .description("Kafka 消费组在本应用实例观察到的最大记录积压")
                    .tag("group", groupId)
                    .register(meterRegistry);
            Gauge.builder(CONFIGURED_CONCURRENCY, container, KafkaConsumerLagMetrics::configuredConcurrency)
                    .description("Kafka listener 当前配置并发数")
                    .tag("group", groupId)
                    .register(meterRegistry);
            Gauge.builder(ASSIGNED_PARTITIONS, container, KafkaConsumerLagMetrics::assignedPartitions)
                    .description("Kafka listener 当前实例实际分配分区数")
                    .tag("group", groupId)
                    .register(meterRegistry);
        }
    }

    /**
     * 聚合同一 listener 的所有 consumer 与分区，只保留最坏积压供一级告警使用。
     *
     * @param container listener 容器
     * @return 非负最大 lag；consumer 尚未完成首次 poll 时为零
     */
    private static double maxLag(MessageListenerContainer container) {
        double maximum = 0D;
        for (Map<MetricName, ? extends Metric> consumerMetrics : container.metrics().values()) {
            for (Map.Entry<MetricName, ? extends Metric> entry : consumerMetrics.entrySet()) {
                if (KAFKA_LAG_MAX.equals(entry.getKey().name())
                        && entry.getValue().metricValue() instanceof Number value) {
                    maximum = Math.max(maximum, value.doubleValue());
                }
            }
        }
        return maximum;
    }

    /** @return 并发容器配置值；非并发容器固定一 */
    private static double configuredConcurrency(MessageListenerContainer container) {
        return container instanceof ConcurrentMessageListenerContainer<?, ?> concurrent
                ? concurrent.getConcurrency()
                : 1D;
    }

    /** @return 当前父容器聚合的实际分区数；尚未完成 rebalance 时为零 */
    private static double assignedPartitions(MessageListenerContainer container) {
        var partitions = container.getAssignedPartitions();
        return partitions == null ? 0D : partitions.size();
    }
}
