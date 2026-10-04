package com.things.link.ingestion.infrastructure;

import com.things.link.testing.AbstractKafkaIntegrationTest;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S7-5 使用真实 Kafka Broker 验证失联接管与至少一次重复投递。
 *
 * <p>测试直接使用原生 consumer，才能精确控制 poll 与 offset 提交；Spring listener 的便捷封装会隐藏
 * group assignment 和 commit 时点，无法证明恢复语义。</p>
 */
@Tag("s7-recovery")
class KafkaRecoveryAcceptanceTests extends AbstractKafkaIntegrationTest {

    /** 短 poll 让测试持续驱动 group coordinator，不依赖固定 sleep。 */
    private static final Duration POLL_INTERVAL = Duration.ofMillis(100);
    /** Docker Desktop 冷机上的重平衡完成上限。 */
    private static final Duration RECOVERY_TIMEOUT = Duration.ofSeconds(15);

    /**
     * 一个 consumer 停止 poll 超过 max.poll.interval 后，仍存活成员必须接管其分区和未处理记录。
     */
    @Test
    void remainingConsumerTakesOverPartitionAfterOwnerStopsPolling() throws Exception {
        String topic = uniqueName("rebalance");
        String group = uniqueName("group");
        createTopic(topic, 2);
        KafkaConsumer<String, String> failedConsumer = consumer(group, "failed");
        KafkaConsumer<String, String> survivingConsumer = consumer(group, "survivor");
        try {
            failedConsumer.subscribe(List.of(topic));
            survivingConsumer.subscribe(List.of(topic));
            awaitSplitAssignment(failedConsumer, survivingConsumer);
            TopicPartition abandonedPartition = failedConsumer.assignment().iterator().next();
            String messageId = UUID.randomUUID().toString();
            send(topic, abandonedPartition.partition(), messageId, "pending-before-rebalance");

            // 故障 consumer 从这里起不再 poll；heartbeat 线程会在 max.poll.interval 后主动离组。
            ConsumerRecord<String, String> recovered = awaitTakeoverAndRecord(
                    survivingConsumer, abandonedPartition, messageId);

            assertThat(survivingConsumer.assignment()).contains(abandonedPartition);
            assertThat(recovered.key()).isEqualTo(messageId);
            assertThat(recovered.value()).isEqualTo("pending-before-rebalance");
        } finally {
            failedConsumer.close(Duration.ofSeconds(1));
            survivingConsumer.close(Duration.ofSeconds(1));
        }
    }

    /**
     * 第一消费者处理后未提交 offset 即退出，新消费者必须收到同一 topic/partition/offset 的原记录。
     */
    @Test
    void uncommittedRecordIsDeliveredAgainToReplacementConsumer() throws Exception {
        String topic = uniqueName("duplicate");
        String group = uniqueName("group");
        createTopic(topic, 1);
        String messageId = UUID.randomUUID().toString();
        send(topic, 0, messageId, "same-business-message");

        KafkaConsumer<String, String> firstConsumer = consumer(group, "first");
        ConsumerRecord<String, String> first;
        try {
            firstConsumer.subscribe(List.of(topic));
            first = awaitRecord(firstConsumer, messageId, RECOVERY_TIMEOUT);
            // 刻意不调用 commitSync；这对应业务成功与 offset 提交之间进程退出的至少一次窗口。
        } finally {
            firstConsumer.close(Duration.ofSeconds(1));
        }

        KafkaConsumer<String, String> replacement = consumer(group, "replacement");
        try {
            replacement.subscribe(List.of(topic));
            ConsumerRecord<String, String> replayed = awaitRecord(replacement, messageId, RECOVERY_TIMEOUT);

            assertThat(replayed.topic()).isEqualTo(first.topic());
            assertThat(replayed.partition()).isEqualTo(first.partition());
            assertThat(replayed.offset()).isEqualTo(first.offset());
            assertThat(replayed.key()).isEqualTo(first.key());
        } finally {
            replacement.close(Duration.ofSeconds(1));
        }
    }

    /**
     * 创建唯一测试主题，避免并行或重复执行时继承旧 offset。
     *
     * @param topic 主题名
     * @param partitions 分区数
     */
    private static void createTopic(String topic, int partitions) throws Exception {
        try (AdminClient admin = AdminClient.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(topic, partitions, (short) 1)))
                    .all().get(10, TimeUnit.SECONDS);
        }
    }

    /**
     * 发布到显式分区，接管测试不能依赖客户端哈希恰好选中故障成员的分区。
     *
     * @param topic 主题
     * @param partition 分区
     * @param key 稳定消息 ID
     * @param value 测试载荷
     */
    private static void send(String topic, int partition, String key, String value) throws Exception {
        Map<String, Object> properties = new HashMap<>();
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        properties.put(ProducerConfig.ACKS_CONFIG, "all");
        properties.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(properties)) {
            producer.send(new ProducerRecord<>(topic, partition, key, value)).get(10, TimeUnit.SECONDS);
        }
    }

    /**
     * 创建关闭自动提交且能快速暴露停止 poll 的消费成员。
     *
     * @param group 消费组
     * @param suffix client ID 后缀
     * @return 原生消费者
     */
    private static KafkaConsumer<String, String> consumer(String group, String suffix) {
        Map<String, Object> properties = new HashMap<>();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, group);
        properties.put(ConsumerConfig.CLIENT_ID_CONFIG, group + "-" + suffix);
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        properties.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 2_000);
        properties.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 6_000);
        properties.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 1_000);
        return new KafkaConsumer<>(properties);
    }

    /**
     * 同时 poll 两个成员直到两分区各有一个所有者。
     *
     * @param first 首成员
     * @param second 次成员
     */
    private static void awaitSplitAssignment(KafkaConsumer<String, String> first,
                                             KafkaConsumer<String, String> second) {
        Instant deadline = Instant.now().plus(RECOVERY_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            first.poll(POLL_INTERVAL);
            second.poll(POLL_INTERVAL);
            if (first.assignment().size() == 1 && second.assignment().size() == 1
                    && disjoint(first.assignment(), second.assignment())) {
                return;
            }
        }
        throw new AssertionError("两个 Kafka consumer 未在期限内形成分区拆分");
    }

    /**
     * 只驱动存活成员，等待 coordinator 撤销失联成员所有权并交付遗留记录。
     *
     * @param survivor 存活成员
     * @param abandoned 原所有者分区
     * @param messageId 目标消息 ID
     * @return 接管后收到的遗留记录
     */
    private static ConsumerRecord<String, String> awaitTakeoverAndRecord(
            KafkaConsumer<String, String> survivor, TopicPartition abandoned, String messageId) {
        Instant deadline = Instant.now().plus(RECOVERY_TIMEOUT);
        while (Instant.now().isBefore(deadline)) {
            ConsumerRecords<String, String> records = survivor.poll(POLL_INTERVAL);
            if (survivor.assignment().contains(abandoned)) {
                for (ConsumerRecord<String, String> record : records.records(abandoned)) {
                    if (messageId.equals(record.key())) {
                        return record;
                    }
                }
            }
        }
        throw new AssertionError("存活 Kafka consumer 未接管分区及遗留记录");
    }

    /**
     * 等待指定消息，不以固定 sleep 猜测 broker 和 coordinator 速度。
     *
     * @param consumer 消费者
     * @param messageId 目标消息 ID
     * @param timeout 上限
     * @return 目标记录
     */
    private static ConsumerRecord<String, String> awaitRecord(KafkaConsumer<String, String> consumer,
                                                               String messageId, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            for (ConsumerRecord<String, String> record : consumer.poll(POLL_INTERVAL)) {
                if (messageId.equals(record.key())) {
                    return record;
                }
            }
        }
        throw new AssertionError("Kafka 未在期限内交付目标消息");
    }

    /** @return 两个成员当前分区集合是否没有重叠。 */
    private static boolean disjoint(Set<TopicPartition> first, Set<TopicPartition> second) {
        return first.stream().noneMatch(second::contains);
    }

    /** @param purpose 场景名 @return 不与历史测试运行冲突的主题或消费组名。 */
    private static String uniqueName(String purpose) {
        return "tc.test.s7." + purpose + "." + UUID.randomUUID();
    }
}
