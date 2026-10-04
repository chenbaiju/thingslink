package com.things.link.ingestion.infrastructure;

import com.things.link.testing.AbstractKafkaIntegrationTest;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
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
import org.springframework.kafka.listener.MessageListener;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** G1-C3b 用真实 Kafka 与 Spring 并发容器证明分区内有序、分区间并行和 record ack 提交点。 */
class KafkaConcurrentContainerSemanticsTests extends AbstractKafkaIntegrationTest {

    /** 阻塞一个分区时另一分区必须推进；解除后相同 key 的序号必须严格保持生产顺序。 */
    @Test
    void preservesPartitionOrderWhileAnotherPartitionProgresses() throws Exception {
        String suffix = UUID.randomUUID().toString();
        String topic = "tc.test.c3b.concurrent." + suffix;
        String group = "tc-test-c3b-" + suffix;
        createTopic(topic, 2);
        CountDownLatch blockedStarted = new CountDownLatch(1);
        CountDownLatch releaseBlocked = new CountDownLatch(1);
        CountDownLatch otherPartitionCompleted = new CountDownLatch(1);
        CountDownLatch orderedCompleted = new CountDownLatch(20);
        List<Integer> observedOrder = new CopyOnWriteArrayList<>();

        ContainerProperties containerProperties = new ContainerProperties(topic);
        containerProperties.setAckMode(ContainerProperties.AckMode.RECORD);
        containerProperties.setMessageListener((MessageListener<String, String>) record -> {
            if (record.partition() == 0 && "block".equals(record.value())) {
                blockedStarted.countDown();
                await(releaseBlocked, "阻塞分区未获释放");
                return;
            }
            if (record.partition() == 1) {
                otherPartitionCompleted.countDown();
                return;
            }
            observedOrder.add(Integer.parseInt(record.value()));
            orderedCompleted.countDown();
        });
        ConcurrentMessageListenerContainer<String, String> container =
                new ConcurrentMessageListenerContainer<>(
                        new DefaultKafkaConsumerFactory<>(consumerProperties(group)), containerProperties);
        container.setConcurrency(2);
        container.start();
        try {
            awaitOnePartitionPerConsumer(container, 2);
            send(topic, 0, "same-device", "block");
            for (int sequence = 0; sequence < 20; sequence++) {
                send(topic, 0, "same-device", Integer.toString(sequence));
            }
            send(topic, 1, "other-device", "parallel");

            assertThat(blockedStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(otherPartitionCompleted.await(5, TimeUnit.SECONDS))
                    .as("另一分区不应被慢分区饿死")
                    .isTrue();
            releaseBlocked.countDown();
            assertThat(orderedCompleted.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(observedOrder).containsExactlyElementsOf(sequence(20));
        } finally {
            releaseBlocked.countDown();
            container.stop();
        }
    }

    /** 创建两分区真实主题。 */
    private static void createTopic(String topic, int partitions) throws Exception {
        try (Admin admin = Admin.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(topic, partitions, (short) 1)))
                    .all().get(10, TimeUnit.SECONDS);
        }
    }

    /** @return 关闭自动提交、从头消费的字符串 consumer 配置 */
    private static Map<String, Object> consumerProperties(String group) {
        Map<String, Object> properties = new HashMap<>();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, group);
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        return properties;
    }

    /** 同步等待 broker 确认，测试生产侧不引入额外不确定性。 */
    private static void send(String topic, int partition, String key, String value) throws Exception {
        Map<String, Object> properties = new HashMap<>();
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        properties.put(ProducerConfig.ACKS_CONFIG, "all");
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(properties)) {
            producer.send(new ProducerRecord<>(topic, partition, key, value)).get(10, TimeUnit.SECONDS);
        }
    }

    /**
     * 等到每个子 consumer 各自持有一个不同分区，避免把再均衡前的临时总分配误当成并发已就绪。
     *
     * <p>并发容器启动时，第一个入组的 consumer 可能短暂独占全部分区；只检查父容器的分区总数会过早
     * 放行，使两个分区仍在同一消费线程上串行处理，从而把 Kafka 再均衡时序误报为生产并发缺陷。</p>
     *
     * @param container 待验证的 Spring Kafka 并发容器
     * @param expectedConsumers 期望参与消费的子 consumer 数量
    */
    private static void awaitOnePartitionPerConsumer(
            ConcurrentMessageListenerContainer<String, String> container, int expectedConsumers) {
        Instant deadline = Instant.now().plusSeconds(15);
        while (Instant.now().isBefore(deadline)) {
            if (hasOneDistinctPartitionPerConsumer(container, expectedConsumers)) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("等待 Kafka assignment 被中断", exception);
            }
        }
        throw new AssertionError("Spring 并发容器未在期限内完成每 consumer 一个分区的稳定分配");
    }

    /** @return 是否恰有 expectedConsumers 个子 consumer，且各持有一个互不重复的分区 */
    private static boolean hasOneDistinctPartitionPerConsumer(
            ConcurrentMessageListenerContainer<String, String> container, int expectedConsumers) {
        if (container.getContainers().size() != expectedConsumers) {
            return false;
        }
        var assignedPartitions = new HashSet<TopicPartition>();
        for (var child : container.getContainers()) {
            var childAssignment = child.getAssignedPartitions();
            if (childAssignment == null || childAssignment.size() != 1) {
                return false;
            }
            assignedPartitions.addAll(childAssignment);
        }
        return assignedPartitions.size() == expectedConsumers;
    }

    /** 门闩等待失败必须抛出，避免 listener 静默继续导致假绿。 */
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

    /** @return 0 到 size-1 的期望顺序 */
    private static List<Integer> sequence(int size) {
        List<Integer> values = new ArrayList<>(size);
        for (int value = 0; value < size; value++) {
            values.add(value);
        }
        return values;
    }
}
