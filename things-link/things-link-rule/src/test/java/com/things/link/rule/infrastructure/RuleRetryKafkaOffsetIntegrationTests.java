package com.things.link.rule.infrastructure;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.KafkaBackoffException;
import org.springframework.kafka.listener.MessageListener;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** F35 用真实 Kafka 证明 retry backoff 不会让 RECORD ack 越过尚未成功的记录。 */
class RuleRetryKafkaOffsetIntegrationTests {

    /** 与部署基线保持 Kafka 4.1 协议代际，测试只启动 broker，不捆绑数据库或完整 Spring 上下文。 */
    private static final DockerImageName KAFKA_IMAGE = DockerImageName.parse("apache/kafka:4.1.0");

    /** 当前测试 JVM 复用一个真实 KRaft broker，降低冷启动噪声。 */
    private static final KafkaContainer KAFKA = new KafkaContainer(KAFKA_IMAGE)
            .withStartupTimeout(Duration.ofMinutes(2));

    static {
        KAFKA.start();
    }

    /**
     * 第一投递抛出 backoff 后，broker 位点必须仍未越过 offset 0；只有重投成功后才能提交 offset 1。
     *
     * @throws Exception broker 管理、生产或有界等待失败
     */
    @Test
    void commitsOffsetOnlyAfterBackoffRecordIsRedeliveredSuccessfully() throws Exception {
        String suffix = UUID.randomUUID().toString();
        String topic = "tc.test.rule.retry.offset." + suffix;
        String group = "tc-test-rule-retry-offset-" + suffix;
        TopicPartition partition = new TopicPartition(topic, 0);
        createTopic(topic);

        CountDownLatch firstFailure = new CountDownLatch(1);
        CountDownLatch secondDelivery = new CountDownLatch(1);
        CountDownLatch allowSuccess = new CountDownLatch(1);
        CountDownLatch successfulReturn = new CountDownLatch(1);
        AtomicInteger attempts = new AtomicInteger();
        List<Long> offsets = new CopyOnWriteArrayList<>();

        ContainerProperties containerProperties = new ContainerProperties(topic);
        containerProperties.setAckMode(ContainerProperties.AckMode.RECORD);
        containerProperties.setMessageListener((MessageListener<String, String>) record -> {
            offsets.add(record.offset());
            int currentAttempt = attempts.incrementAndGet();
            if (currentAttempt == 1) {
                firstFailure.countDown();
                throw new KafkaBackoffException("重试信封尚未到期", partition, group,
                        System.currentTimeMillis() + 1_000L);
            }
            secondDelivery.countDown();
            await(allowSuccess, "第二投递未获成功许可");
            successfulReturn.countDown();
        });
        ConcurrentMessageListenerContainer<String, String> container =
                new ConcurrentMessageListenerContainer<>(
                        new DefaultKafkaConsumerFactory<>(consumerProperties(group)), containerProperties);
        container.setCommonErrorHandler(new RuleRetryKafkaErrorHandler());
        container.start();
        try {
            awaitAssignment(container);
            send(topic, "message-1");
            assertThat(firstFailure.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(secondDelivery.await(10, TimeUnit.SECONDS)).isTrue();

            // 第二投递尚未返回时直读 broker：若错误处理器误报 recovered，这里会提前看到 offset 1。
            assertThat(committedOffset(group, partition)).isLessThanOrEqualTo(0L);
            allowSuccess.countDown();
            assertThat(successfulReturn.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(awaitCommittedOffset(group, partition, 1L)).isEqualTo(1L);
            assertThat(offsets).containsExactly(0L, 0L);
            assertThat(attempts).hasValue(2);
        } finally {
            allowSuccess.countDown();
            container.stop();
        }
    }

    /** @return 关闭自动提交并从最早位点消费的字符串 consumer 配置 */
    private static Map<String, Object> consumerProperties(String group) {
        return Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, group,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
                ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 1);
    }

    /** 创建单分区主题，使 offset 0/1 的裁决没有分区歧义。 */
    private static void createTopic(String topic) throws Exception {
        try (Admin admin = admin()) {
            admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1)))
                    .all().get(10, TimeUnit.SECONDS);
        }
    }

    /** 同步生产一条 offset 0 记录，排除生产端未确认造成的假等待。 */
    private static void send(String topic, String value) throws Exception {
        Map<String, Object> properties = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.ACKS_CONFIG, "all");
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(properties)) {
            producer.send(new ProducerRecord<>(topic, 0, "same-rule", value))
                    .get(10, TimeUnit.SECONDS);
        }
    }

    /** 等待 Spring consumer 获得目标分区，避免记录在再均衡窗口内产生时序噪声。 */
    private static void awaitAssignment(ConcurrentMessageListenerContainer<String, String> container) {
        Instant deadline = Instant.now().plusSeconds(15);
        while (Instant.now().isBefore(deadline)) {
            if (container.getAssignedPartitions() != null && !container.getAssignedPartitions().isEmpty()) {
                return;
            }
            sleep();
        }
        throw new AssertionError("规则 retry 测试 consumer 未在预算内获得分区");
    }

    /** @return broker 当前已提交位点；尚未创建提交事实时返回 0 */
    private static long committedOffset(String group, TopicPartition partition) throws Exception {
        try (Admin admin = admin()) {
            OffsetAndMetadata value = admin.listConsumerGroupOffsets(group)
                    .partitionsToOffsetAndMetadata().get(10, TimeUnit.SECONDS).get(partition);
            return value == null ? 0L : value.offset();
        }
    }

    /** 等待 RECORD ack 的异步提交抵达 broker，并返回最终位点。 */
    private static long awaitCommittedOffset(String group, TopicPartition partition, long expected)
            throws Exception {
        Instant deadline = Instant.now().plusSeconds(10);
        long actual = committedOffset(group, partition);
        while (actual != expected && Instant.now().isBefore(deadline)) {
            sleep();
            actual = committedOffset(group, partition);
        }
        return actual;
    }

    /** @return 只配置真实 broker 地址的管理客户端 */
    private static Admin admin() {
        return Admin.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()));
    }

    /** 有界轮询间隔；中断必须恢复线程标记并使测试失败。 */
    private static void sleep() {
        try {
            Thread.sleep(50L);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("等待 Kafka offset 时被中断", exception);
        }
    }

    /** 在 listener 线程内有界等待测试线程许可。 */
    private static void await(CountDownLatch latch, String message) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError(message);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(message, exception);
        }
    }
}
