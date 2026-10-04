package com.things.link.support.kafka;

import com.things.link.testing.AbstractKafkaIntegrationTest;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.CreateTopicsResult;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.Uuid;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.core.env.Environment;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.mock.env.MockEnvironment;

import java.util.ArrayList;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 使用真实 Kafka metadata 验证并发守卫不会把分区不足静默降级。 */
class KafkaConsumerConcurrencyGuardIntegrationTests extends AbstractKafkaIntegrationTest {

    /** raw 默认并发四而真实主题只有三分区时，应用启动守卫必须确定失败。 */
    @Test
    void failsWhenTopicHasFewerPartitionsThanConfiguredConcurrency() throws Exception {
        Map<String, Integer> topicPartitions = new LinkedHashMap<>();
        KafkaConsumerConcurrencyGuard.boundaries().forEach((property, boundary) ->
                boundary.topics().forEach(topic -> topicPartitions.merge(
                        topic,
                        topic.equals("tc.device.uplink.raw") ? 3 : boundary.defaultConcurrency(),
                        Math::max)));
        List<NewTopic> topics = new ArrayList<>();
        topicPartitions.forEach((topic, partitions) -> topics.add(new NewTopic(topic, partitions, (short) 1)));
        Map<String, Uuid> createdTopicIds = new LinkedHashMap<>();
        try (Admin admin = Admin.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            // 集成测试共享同一个临时 Broker；先重建固定生产主题，避免前序 Outbox 测试留下的主题
            // 让“raw 只有三分区”前置条件失真，或因 TopicExistsException 在真正断言前失败。
            java.util.Set<String> existing = admin.listTopics().names().get(20, TimeUnit.SECONDS);
            java.util.Set<String> owned = new java.util.HashSet<>(topicPartitions.keySet());
            owned.retainAll(existing);
            if (!owned.isEmpty()) {
                admin.deleteTopics(owned).all().get(20, TimeUnit.SECONDS);
                awaitTopicsAbsent(admin, owned);
            }
            CreateTopicsResult created = admin.createTopics(topics);
            created.all().get(20, TimeUnit.SECONDS);
            for (String topic : topicPartitions.keySet()) {
                createdTopicIds.put(topic, created.topicId(topic).get(20, TimeUnit.SECONDS));
            }
        }
        // 创建成功只说明控制器完成请求；用独立 Admin 验证本轮 Topic 身份与 leader，
        // 避免刚重建时的 metadata 传播窗口让守卫提前失败于 UnknownTopicOrPartition。
        try (Admin metadataAdmin = Admin.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            new KafkaTopicMetadataBarrier(Duration.ofSeconds(20)).awaitReady(
                    topicPartitions, createdTopicIds,
                    () -> readTopicDescriptions(metadataAdmin, topicPartitions.keySet()));
        }
        KafkaAdmin kafkaAdmin = new KafkaAdmin(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()));
        Environment environment = new MockEnvironment();
        KafkaConsumerConcurrencyGuard guard = new KafkaConsumerConcurrencyGuard(kafkaAdmin, environment);

        assertThatThrownBy(() -> guard.run(new DefaultApplicationArguments(new String[0])))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tc.device.uplink.raw")
                .hasMessageContaining("partitions=3")
                .hasMessageContaining("concurrency=4");
    }

    /**
     * 保留 Kafka 原始查询错误供前置屏障分类；查询超时与中断都不能伪装成创建传播。
     *
     * @param admin 创建请求结束后另建的 metadata 客户端
     * @param topics 本轮创建的全部主题
     * @return 单次查询得到的 Topic metadata
     */
    private static Map<String, TopicDescription> readTopicDescriptions(Admin admin, Set<String> topics) {
        try {
            return admin.describeTopics(topics).allTopicNames().get(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("查询临时 Kafka Topic metadata 被中断", interrupted);
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw new IllegalStateException("查询临时 Kafka Topic metadata 失败", failed);
        } catch (java.util.concurrent.TimeoutException timeout) {
            throw new IllegalStateException("查询临时 Kafka Topic metadata 超时", timeout);
        }
    }

    /**
     * Kafka 的删除 Future 只确认控制器接受请求，metadata 中主题可能短暂仍可见；必须等删除真正传播后再重建。
     *
     * @param admin 临时 Broker 管理客户端
     * @param topics 本测试拥有并要求消失的固定主题
     */
    private static void awaitTopicsAbsent(Admin admin, Set<String> topics) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Set<String> remaining = new java.util.HashSet<>(admin.listTopics().names().get(5, TimeUnit.SECONDS));
            remaining.retainAll(topics);
            if (remaining.isEmpty()) {
                return;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(100));
        }
        throw new IllegalStateException("临时 Kafka 固定主题未在期限内删除: " + topics);
    }
}
