package com.things.link.ingestion.infrastructure;

import com.things.link.device.application.DeviceAccessScopeService;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.RawUplinkMessage;
import com.things.link.shared.message.EventUplinkMessage;
import com.things.link.shared.message.StandardUplinkMessage;
import com.things.link.testing.AbstractKafkaIntegrationTest;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.common.config.ConfigResource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.Map;
import java.util.List;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static com.things.link.ingestion.infrastructure.RawUplinkKafkaConsumer.NORMALIZED_UPLINK_TOPIC;
import static com.things.link.ingestion.infrastructure.RawUplinkKafkaConsumer.EVENT_NORMALIZED_TOPIC;
import static com.things.link.ingestion.infrastructure.RawUplinkKafkaConsumer.RAW_UPLINK_TOPIC;
import static com.things.link.ingestion.infrastructure.UplinkKafkaConfiguration.DEAD_LETTER_TOPIC;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** 使用真实 Kafka 验证 raw 消费、标准信封发布和不可重试错误进入 DLQ 的闭环。 */
@Import(RawUplinkKafkaConsumerTests.KafkaConsumerTestConfiguration.class)
class RawUplinkKafkaConsumerTests extends AbstractKafkaIntegrationTest {

    /** 发布原始测试记录的 Boot Kafka 模板。 */
    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    /** 记录标准主题与 DLQ 结果的测试探针。 */
    @Autowired
    private ResultProbe resultProbe;

    /** 合法报文应以相同 deviceId key 发布标准信封并完整保留可信上下文。 */
    @Test
    void convertsRawPropertyReportToStandardEnvelope() throws Exception {
        UUID deviceId = Uuid7.generate();
        UUID messageId = Uuid7.generate();
        RawUplinkMessage raw = rawMessage(deviceId,
                ("{\"messageId\":\"" + messageId
                        + "\",\"occurredAt\":\"2026-08-05T08:00:00Z\",\"payload\":{\"temperature\":26.5}}")
                        .getBytes(StandardCharsets.UTF_8));

        kafkaTemplate.send(RAW_UPLINK_TOPIC, deviceId.toString(), raw).get(10, TimeUnit.SECONDS);

        ConsumerRecord<String, StandardUplinkMessage> result =
                resultProbe.pollNormalized(deviceId.toString(), 10, TimeUnit.SECONDS);
        assertThat(result).isNotNull();
        assertThat(result.key()).isEqualTo(deviceId.toString());
        assertThat(result.value().messageId()).isEqualTo(messageId);
        assertThat(result.value().tenantId()).isEqualTo(raw.tenantId());
        assertThat(result.value().projectId()).isEqualTo(raw.projectId());
        assertThat(result.value().deviceId()).isEqualTo(deviceId);
        assertThat(result.value().traceId()).isEqualTo(raw.traceId());
        assertThat(result.value().payload()).containsEntry("temperature", new java.math.BigDecimal("26.5"));
    }

    /** 非法设备 JSON 重放无意义，应直接保留原始记录到 DLQ 并携带失败诊断头。 */
    @Test
    void routesMalformedPropertyReportDirectlyToDeadLetterTopic() throws Exception {
        UUID deviceId = Uuid7.generate();
        RawUplinkMessage raw = rawMessage(deviceId, "not-json".getBytes(StandardCharsets.UTF_8));

        kafkaTemplate.send(RAW_UPLINK_TOPIC, deviceId.toString(), raw).get(10, TimeUnit.SECONDS);

        ConsumerRecord<String, RawUplinkMessage> deadLetter =
                resultProbe.pollDeadLetter(deviceId.toString(), 10, TimeUnit.SECONDS);
        assertThat(deadLetter).isNotNull();
        assertThat(deadLetter.key()).isEqualTo(deviceId.toString());
        assertThat(deadLetter.value().deviceId()).isEqualTo(deviceId);
        assertThat(deadLetter.headers())
                .anySatisfy(header -> assertThat(header.key()).containsIgnoringCase("exception"));
    }

    /** deviceId key 不一致会破坏设备内顺序，应作为不可重试协议错误进入 DLQ。 */
    @Test
    void routesMismatchedDeviceKeyToDeadLetterTopic() throws Exception {
        UUID deviceId = Uuid7.generate();
        UUID messageId = Uuid7.generate();
        RawUplinkMessage raw = rawMessage(deviceId,
                ("{\"messageId\":\"" + messageId
                        + "\",\"occurredAt\":\"2026-08-05T08:00:00Z\",\"payload\":{\"online\":true}}")
                        .getBytes(StandardCharsets.UTF_8));
        String wrongKey = Uuid7.generate().toString();

        kafkaTemplate.send(RAW_UPLINK_TOPIC, wrongKey, raw).get(10, TimeUnit.SECONDS);

        ConsumerRecord<String, RawUplinkMessage> deadLetter =
                resultProbe.pollDeadLetter(wrongKey, 10, TimeUnit.SECONDS);
        assertThat(deadLetter).isNotNull();
        assertThat(deadLetter.key()).isEqualTo(wrongKey);
        assertThat(deadLetter.value().deviceId()).isEqualTo(deviceId);
    }

    /** 事件已独立支持，非法闭集正文仍保留原始信封并只输出固定诊断。 */
    @Test
    void routesInvalidEventBodyToDeadLetterWithFixedDiagnostic() throws Exception {
        UUID deviceId = Uuid7.generate();
        RawUplinkMessage raw = new RawUplinkMessage(
                Uuid7.generate(), Uuid7.generate(), deviceId,
                "tc/v1/project/device/up/event/alarm", "{}".getBytes(StandardCharsets.UTF_8),
                1, false, "device-client", Instant.now(), "0123456789abcdef0123456789abcdef");

        kafkaTemplate.send(RAW_UPLINK_TOPIC, deviceId.toString(), raw).get(10, TimeUnit.SECONDS);

        ConsumerRecord<String, RawUplinkMessage> deadLetter =
                resultProbe.pollDeadLetter(deviceId.toString(), 10, TimeUnit.SECONDS);
        assertThat(deadLetter).isNotNull();
        assertThat(deadLetter.value().topic()).isEqualTo("tc/v1/project/device/up/event/alarm");
        assertThat(deadLetter.headers()).anySatisfy(header -> {
            assertThat(header.key()).containsIgnoringCase("exception-message");
            assertThat(new String(header.value(), StandardCharsets.UTF_8))
                    .contains("EVENT_PAYLOAD_INVALID");
        });
    }

    /** 真实Kafka验证事件独立分流、原始精度与可信时刻，不进入属性链。 */
    @Test
    void routesEventOnlyToDedicatedTwelvePartitionSevenDayTopic() throws Exception {
        UUID deviceId = Uuid7.generate(), messageId = Uuid7.generate();
        byte[] bytes = ("{\"messageId\":\"" + messageId + "\",\"modelVersion\":\"1.0.0\","
                + "\"occurredAt\":\"2026-10-06T00:00:00Z\",\"params\":{\"decimal\":9007199254740993.123456789,"
                + "\"integer\":9007199254740993123456789,\"scale\":1.0}}").getBytes(StandardCharsets.UTF_8);
        RawUplinkMessage raw = new RawUplinkMessage(Uuid7.generate(), Uuid7.generate(), deviceId,
                "tc/v1/project/device/up/event/alarm", bytes, 1, false, "device-client",
                Instant.parse("2026-10-06T00:00:01Z"), "0123456789abcdef0123456789abcdef");
        kafkaTemplate.send(RAW_UPLINK_TOPIC, deviceId.toString(), raw).get(10, TimeUnit.SECONDS);
        var result = resultProbe.pollEvent(deviceId.toString(), 10, TimeUnit.SECONDS);
        assertThat(result).isNotNull();
        assertThat(result.key()).isEqualTo(deviceId.toString());
        assertThat(result.value().messageId()).isEqualTo(messageId);
        assertThat(result.value().tenantId()).isEqualTo(raw.tenantId());
        assertThat(result.value().projectId()).isEqualTo(raw.projectId());
        assertThat(result.value().deviceId()).isEqualTo(deviceId);
        assertThat(result.value().eventKey()).isEqualTo("alarm");
        assertThat(result.value().receivedAt()).isEqualTo(raw.receivedAt());
        assertThat(result.value().traceId()).isEqualTo(raw.traceId());
        assertThat(result.value().rawBytes()).isEqualTo(bytes.length);
        assertThat(result.value().params()).containsEntry("decimal", new BigDecimal("9007199254740993.123456789"))
                .containsEntry("integer", new BigInteger("9007199254740993123456789"))
                .containsEntry("scale", new BigDecimal("1.0"));
        assertThat(resultProbe.pollNormalized(deviceId.toString(), 250, TimeUnit.MILLISECONDS)).isNull();
        try (var admin = AdminClient.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            var topic = admin.describeTopics(List.of(EVENT_NORMALIZED_TOPIC)).allTopicNames().get(10, TimeUnit.SECONDS);
            assertThat(topic.get(EVENT_NORMALIZED_TOPIC).partitions()).hasSize(12);
            var resource = new ConfigResource(ConfigResource.Type.TOPIC, EVENT_NORMALIZED_TOPIC);
            var config = admin.describeConfigs(List.of(resource)).all().get(10, TimeUnit.SECONDS);
            assertThat(config.get(resource).get("retention.ms").value()).isEqualTo("604800000");
        }
    }

    /**
     * 创建满足接入层冻结约束的原始消息。
     *
     * @param deviceId 设备标识
     * @param payload 原始设备报文字节
     * @return 原始上行信封
     */
    private static RawUplinkMessage rawMessage(UUID deviceId, byte[] payload) {
        return new RawUplinkMessage(
                Uuid7.generate(), Uuid7.generate(), deviceId,
                "tc/v1/project/device/up/property/report", payload, 1, false,
                "device-client", Instant.now(), "0123456789abcdef0123456789abcdef");
    }

    /** 测试专用 listener 与接入依赖替身装配。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class KafkaConsumerTestConfiguration {

        /** @return 标准主题与 DLQ 记录探针 */
        @Bean
        ResultProbe resultProbe() {
            return new ResultProbe();
        }

        /** @return 本测试不经过 HTTP/EMQX 接入，提供隔离的设备身份解析替身 */
        @Bean
        DeviceAccessScopeService deviceAccessScopeService() {
            return mock(DeviceAccessScopeService.class);
        }
    }

    /** 通过独立 consumer group 观察生产 listener 的发布结果。 */
    static final class ResultProbe {

        /**
         * 标准主题按本次唯一 Kafka key 分邮箱；共享 Testcontainers Topic 的历史记录不能抢占当前断言。
         */
        private final ConcurrentHashMap<String,
                LinkedBlockingQueue<ConsumerRecord<String, StandardUplinkMessage>>> normalized =
                new ConcurrentHashMap<>();

        /** 事件主题独立邮箱，禁止以属性探针代替实际事件分流。 */
        private final ConcurrentHashMap<String,
                LinkedBlockingQueue<ConsumerRecord<String, EventUplinkMessage>>> events = new ConcurrentHashMap<>();

        /** DLQ 同样按 key 隔离，避免完整 reactor 中其他消费者测试残留记录制造顺序相关失败。 */
        private final ConcurrentHashMap<String,
                LinkedBlockingQueue<ConsumerRecord<String, RawUplinkMessage>>> deadLetters =
                new ConcurrentHashMap<>();

        /**
         * 等待当前测试设备对应的 normalized 结果，忽略共享 Topic 中其他设备的历史事实。
         *
         * @param key 本次发送使用的设备 key
         * @param timeout 最大等待值
         * @param unit 等待时间单位
         * @return 当前 key 的记录，超时返回 null
         * @throws InterruptedException 测试线程被中断
         */
        ConsumerRecord<String, StandardUplinkMessage> pollNormalized(
                String key, long timeout, TimeUnit unit) throws InterruptedException {
            return normalized.computeIfAbsent(key, ignored -> new LinkedBlockingQueue<>())
                    .poll(timeout, unit);
        }

        ConsumerRecord<String, EventUplinkMessage> pollEvent(String key, long timeout, TimeUnit unit)
                throws InterruptedException {
            return events.computeIfAbsent(key, ignored -> new LinkedBlockingQueue<>()).poll(timeout, unit);
        }

        @KafkaListener(topics = EVENT_NORMALIZED_TOPIC, groupId = "ingestion-event-test-probe")
        void receiveEvent(ConsumerRecord<String, EventUplinkMessage> record) {
            events.computeIfAbsent(record.key(), ignored -> new LinkedBlockingQueue<>()).add(record);
        }

        /**
         * 等待当前测试 key 对应的 DLQ 结果；历史记录保留在各自邮箱，不会被错误断言消费。
         *
         * @param key 本次发送使用的原始 Kafka key
         * @param timeout 最大等待值
         * @param unit 等待时间单位
         * @return 当前 key 的死信记录，超时返回 null
         * @throws InterruptedException 测试线程被中断
         */
        ConsumerRecord<String, RawUplinkMessage> pollDeadLetter(
                String key, long timeout, TimeUnit unit) throws InterruptedException {
            return deadLetters.computeIfAbsent(key, ignored -> new LinkedBlockingQueue<>())
                    .poll(timeout, unit);
        }

        /**
         * 记录标准上行消息。
         *
         * @param record 标准消息记录
         */
        @KafkaListener(topics = NORMALIZED_UPLINK_TOPIC, groupId = "ingestion-normalized-test-probe")
        void receiveNormalized(ConsumerRecord<String, StandardUplinkMessage> record) {
            normalized.computeIfAbsent(record.key(), ignored -> new LinkedBlockingQueue<>()).add(record);
        }

        /**
         * 记录死信中的原始消息与恢复头。
         *
         * @param record DLQ 记录
         */
        @KafkaListener(topics = DEAD_LETTER_TOPIC, groupId = "ingestion-dlq-test-probe")
        void receiveDeadLetter(ConsumerRecord<String, RawUplinkMessage> record) {
            deadLetters.computeIfAbsent(record.key(), ignored -> new LinkedBlockingQueue<>()).add(record);
        }
    }
}
