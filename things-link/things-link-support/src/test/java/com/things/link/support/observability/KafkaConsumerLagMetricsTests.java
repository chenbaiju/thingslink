package com.things.link.support.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.common.Metric;
import org.apache.kafka.common.MetricName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Kafka 原生客户端指标到稳定消费组 lag Gauge 的适配测试。 */
class KafkaConsumerLagMetricsTests {

    /** 多客户端、多分区指标必须聚合为消费组最坏 lag，且标签使用明确 groupId。 */
    @Test
    void exposesMaximumNativeLagByConsumerGroup() {
        KafkaListenerEndpointRegistry endpoints = mock(KafkaListenerEndpointRegistry.class);
        MessageListenerContainer container = mock(MessageListenerContainer.class);
        Metric lowLag = metric(3D);
        Metric highLag = metric(27D);
        Metric unrelatedMetric = metric(999D);
        MetricName lagName = new MetricName("records-lag-max", "consumer-fetch-manager-metrics", "", Map.of());
        MetricName unrelatedName = new MetricName("records-consumed-total", "consumer-fetch-manager-metrics", "", Map.of());

        when(container.getGroupId()).thenReturn("things-link-ingestion-raw");
        when(container.metrics()).thenReturn(Map.of(
                "consumer-a", Map.of(lagName, lowLag, unrelatedName, unrelatedMetric),
                "consumer-b", Map.of(lagName, highLag)));
        when(endpoints.getListenerContainers()).thenReturn(List.of(container));

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        KafkaConsumerLagMetrics metrics = new KafkaConsumerLagMetrics(endpoints, registry);
        metrics.onApplicationEvent(mock(ApplicationReadyEvent.class));

        assertThat(registry.get(KafkaConsumerLagMetrics.CONSUMER_LAG)
                .tag("group", "things-link-ingestion-raw").gauge().value()).isEqualTo(27D);
    }

    /**
     * @param value Kafka 原生指标值
     * @return 测试指标替身
     */
    private static Metric metric(double value) {
        Metric metric = mock(Metric.class);
        when(metric.metricValue()).thenReturn(value);
        return metric;
    }
}
