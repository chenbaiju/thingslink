package com.things.link.support.kafka;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.TopicDescription;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 在应用接收流量前校验冻结的 Kafka 并发范围与真实 Topic 分区数。
 *
 * <p>Kafka 会在并发数大于分区数时静默留下空闲 consumer，这会让错误配置看似启动成功；G1-C3b 要求 fail-fast，
 * 因此这里同时校验本地上限和 broker metadata。目录使用固定低基数名称，不能从租户输入动态扩展。</p>
 */
@Component
@ConditionalOnProperty(
        prefix = "things-link.kafka.concurrency-guard",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = false)
public class KafkaConsumerConcurrencyGuard implements ApplicationRunner {

    /** Broker metadata 获取上限；启动异常必须有界失败，不能永久挂住部署。 */
    private static final Duration METADATA_TIMEOUT = Duration.ofSeconds(10);

    /** Boot Kafka 管理连接配置。 */
    private final KafkaAdmin kafkaAdmin;

    /** 读取环境覆盖后的逐 group 并发值。 */
    private final Environment environment;

    /**
     * @param kafkaAdmin Boot Kafka 管理配置
     * @param environment 配置环境
     */
    public KafkaConsumerConcurrencyGuard(KafkaAdmin kafkaAdmin, Environment environment) {
        this.kafkaAdmin = kafkaAdmin;
        this.environment = environment;
    }

    /** 应用 ready 前完成全部校验；任一 Topic 不满足即中止启动。 */
    @Override
    public void run(ApplicationArguments arguments) {
        boolean accessRole = "device-access".equals(
                environment.getProperty("things-link.deployment.role"));
        if (accessRole && !environment.getProperty("things-link.access.tcp.enabled", Boolean.class, false)) {
            return; // 没有 TCP 消费者时，CoAP/HTTP 接入不依赖下行 Topic。
        }
        Map<String, ConsumerBoundary> boundaries = new LinkedHashMap<>(
                accessRole ? accessBoundaries() : boundaries());
        if (!accessRole && environment.getProperty("things-link.integration.webhook.enabled",Boolean.class,false))
            boundaries.put("things-link.kafka.concurrency.webhook-source",boundary(3,6,"tc.integration.webhook.source"));
        Map<String, Integer> configured = new LinkedHashMap<>();
        for (Map.Entry<String, ConsumerBoundary> entry : boundaries.entrySet()) {
            ConsumerBoundary boundary = entry.getValue();
            int concurrency = environment.getProperty(entry.getKey(), Integer.class, boundary.defaultConcurrency());
            if (concurrency < 1 || concurrency > boundary.maximumConcurrency()) {
                throw new IllegalStateException("Kafka consumer 并发超出冻结范围："
                        + entry.getKey() + "=" + concurrency + "，允许 1.." + boundary.maximumConcurrency());
            }
            configured.put(entry.getKey(), concurrency);
        }

        try (Admin admin = Admin.create(kafkaAdmin.getConfigurationProperties())) {
            List<String> topics = boundaries.values().stream()
                    .flatMap(boundary -> boundary.topics().stream())
                    .distinct()
                    .toList();
            Map<String, TopicDescription> descriptions = admin.describeTopics(topics)
                    .allTopicNames()
                    .get(METADATA_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            for (Map.Entry<String, ConsumerBoundary> entry : boundaries.entrySet()) {
                int concurrency = configured.get(entry.getKey());
                for (String topic : entry.getValue().topics()) {
                    TopicDescription description = descriptions.get(topic);
                    int partitions = description == null ? 0 : description.partitions().size();
                    if (partitions < concurrency) {
                        throw new IllegalStateException("Kafka Topic 分区少于 consumer 并发：topic="
                                + topic + "，partitions=" + partitions + "，concurrency=" + concurrency);
                    }
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Kafka consumer 并发启动校验被中断", exception);
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("Kafka consumer 并发启动校验无法读取 Topic metadata", exception);
        }
    }

    /** @return G1-C3b 冻结的配置键、默认值、上限与 Topic 目录 */
    static Map<String, ConsumerBoundary> boundaries() {
        Map<String, ConsumerBoundary> result = new LinkedHashMap<>();
        result.put("things-link.kafka.concurrency.ingestion-raw", boundary(4, 12, "tc.device.uplink.raw"));
        result.put("things-link.kafka.concurrency.ingestion-normalized", boundary(4, 12, "tc.device.uplink.normalized"));
        result.put("things-link.kafka.concurrency.ingestion-processed", boundary(4, 4, "tc.device.uplink.processed"));
        result.put("things-link.kafka.concurrency.ingestion-batch", boundary(2, 4, "tc.device.batch"));
        result.put("things-link.kafka.concurrency.ingestion-topology", boundary(2, 4, "tc.device.topo"));
        result.put("things-link.kafka.concurrency.ingestion-config-reply", boundary(2, 4, "tc.device.config.reply"));
        result.put("things-link.kafka.concurrency.ingestion-modbus-response", boundary(2, 4, "tc.device.modbus.response"));
        result.put("things-link.kafka.concurrency.ingestion-realtime", boundary(2, 4, "tc.device.realtime"));
        result.put("things-link.kafka.concurrency.ingestion-downlink", boundary(4, 8, "tc.device.downlink"));
        result.put("things-link.kafka.concurrency.ingestion-config", boundary(2, 4, "tc.device.config"));
        result.put("things-link.kafka.concurrency.ingestion-topology-reply", boundary(2, 4, "tc.device.topo.reply"));
        result.put("things-link.kafka.concurrency.ingestion-modbus-request", boundary(2, 4, "tc.device.modbus.request"));
        result.put("things-link.kafka.concurrency.notification-delivery", boundary(3, 6, "tc.notification"));
        result.put("things-link.kafka.concurrency.rule-notification", boundary(3, 6, "tc.rule.notification"));
        result.put("things-link.kafka.concurrency.rule-retry", boundary(
                2, 6, "tc.rule.retry.1m", "tc.rule.retry.5m"));
        result.put("things-link.kafka.concurrency.rule-device-terminal", boundary(
                2, 4, "tc.device.command.terminal"));
        return Map.copyOf(result);
    }

    /** 独立设备进程只有专属 TCP 广播消费；平台主题不能成为它的启动前置。 */
    static Map<String, ConsumerBoundary> accessBoundaries() {
        return Map.of("things-link.kafka.concurrency.tcp-downlink",
                boundary(1, 16, "tc.device.downlink"));
    }

    /** @return 一个不可变消费边界 */
    private static ConsumerBoundary boundary(int defaultConcurrency, int maximumConcurrency, String... topics) {
        return new ConsumerBoundary(defaultConcurrency, maximumConcurrency, List.of(topics));
    }

    /**
     * @param defaultConcurrency 参考规格默认并发
     * @param maximumConcurrency 本片允许上限
     * @param topics 该 group 消费的固定 Topic
     */
    record ConsumerBoundary(int defaultConcurrency, int maximumConcurrency, List<String> topics) {
        /** 拒绝不完整目录，避免 guard 自身静默漏检。 */
        ConsumerBoundary {
            topics = List.copyOf(topics);
            if (defaultConcurrency < 1 || maximumConcurrency < defaultConcurrency || topics.isEmpty()) {
                throw new IllegalArgumentException("Kafka consumer 并发目录不完整");
            }
        }
    }
}
