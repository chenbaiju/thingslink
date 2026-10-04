package com.things.link.support.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Map;

/** G1-C3b 固定 Kafka 边界的逐记录耗时与结果指标；未知 Topic 统一降维，禁止标签失控。 */
public class KafkaConsumerProcessingMetrics {

    /** 逐记录处理墙钟。 */
    static final String DURATION = "thingslink.kafka.consumer.processing";
    /** listener 返回成功或抛错结果。 */
    static final String RESULT = "thingslink.kafka.consumer.result";

    /** Topic 到固定 group 的冻结目录。 */
    private static final Map<String, String> GROUPS = Map.ofEntries(
            Map.entry("tc.device.uplink.raw", "ingestion-raw"),
            Map.entry("tc.device.uplink.normalized", "ingestion-normalized"),
            Map.entry("tc.device.uplink.processed", "ingestion-processed"),
            Map.entry("tc.device.batch", "ingestion-batch"),
            Map.entry("tc.device.topo", "ingestion-topology"),
            Map.entry("tc.device.config.reply", "ingestion-config-reply"),
            Map.entry("tc.device.modbus.response", "ingestion-modbus-response"),
            Map.entry("tc.device.realtime", "ingestion-realtime"),
            Map.entry("tc.device.downlink", "ingestion-downlink"),
            Map.entry("tc.device.config", "ingestion-config"),
            Map.entry("tc.device.topo.reply", "ingestion-topology-reply"),
            Map.entry("tc.device.modbus.request", "ingestion-modbus-request"),
            Map.entry("tc.notification", "notification-delivery"),
            Map.entry("tc.rule.notification", "rule-notification"),
            Map.entry("tc.rule.retry.1m", "rule-retry"),
            Map.entry("tc.rule.retry.5m", "rule-retry"),
            Map.entry("tc.device.command.terminal", "rule-device-terminal"));

    /** 应用注册表。 */
    private final MeterRegistry registry;

    /** Spring 装配入口。 */
    public KafkaConsumerProcessingMetrics(ObjectProvider<MeterRegistry> provider) {
        this(provider.getIfAvailable(SimpleMeterRegistry::new));
    }

    /** 显式注册表入口。 */
    public KafkaConsumerProcessingMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** @param topic 固定主题 @return 计时起点 */
    public long start(String topic) {
        return System.nanoTime();
    }

    /** @param topic 消费主题 @param startedNanos 起点 @param result success/failure */
    public void finish(String topic, long startedNanos, String result) {
        String safeTopic = GROUPS.containsKey(topic) ? topic : "unknown";
        String group = GROUPS.getOrDefault(topic, "unknown");
        Timer.builder(DURATION)
                .tags("group", group, "topic", safeTopic)
                .publishPercentileHistogram()
                .register(registry)
                .record(System.nanoTime() - startedNanos, java.util.concurrent.TimeUnit.NANOSECONDS);
        Counter.builder(RESULT)
                .tags("group", group, "topic", safeTopic, "result", result)
                .register(registry)
                .increment();
    }
}
