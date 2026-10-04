package com.things.link.ingestion.infrastructure.access;

import com.things.link.device.application.DeviceAccessAcceptancePort;
import com.things.link.ingestion.application.access.DeviceAccessUplinkIngressService;
import com.things.link.ingestion.application.access.DeviceAccessUplinkMessage;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.AuthenticatedDeviceIdentity;
import com.things.link.shared.message.RawUplinkMessage;
import com.things.link.shared.message.StandardUplinkMessage;
import com.things.link.shared.message.TransportProtocol;
import com.things.link.testing.AbstractKafkaIntegrationTest;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static com.things.link.ingestion.infrastructure.RawUplinkKafkaConsumer.NORMALIZED_UPLINK_TOPIC;
import static com.things.link.ingestion.infrastructure.RawUplinkKafkaConsumer.RAW_UPLINK_TOPIC;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

/** 使用真实 Kafka 验证新协议受理后与 MQTT 共用同一条标准上行管道。 */
@Import(DeviceAccessUplinkHandoffKafkaTests.AccessProbeConfiguration.class)
class DeviceAccessUplinkHandoffKafkaTests extends AbstractKafkaIntegrationTest {

    /** 真实装配的新协议受理用例。 */
    @Autowired
    private DeviceAccessUplinkIngressService ingressService;

    /** 发布原始信封以验证 MQTT 路径未回归的 Boot Kafka 模板。 */
    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    /** 标准主题观察探针。 */
    @Autowired
    private AccessProbe accessProbe;

    /** 幂等受理事实端口；本模块不加载设备迁移，用主选替身承接判定与写入。 */
    @Autowired
    private DeviceAccessAcceptancePort acceptancePort;

    /** 每例都声明新的尝试为未受理过，避免上一例的桩影响本节顺序无关性。 */
    @BeforeEach
    void acceptEveryAttemptAsFresh() {
        // 用 doReturn/doAnswer 形式重设桩：when(...) 形式会先调用既有桩，跨用例共享的替身上会以 null 入参触发它。
        doReturn(new DeviceAccessAcceptancePort.Decision.Fresh()).when(acceptancePort).decide(any());
        doAnswer(call -> {
            DeviceAccessAcceptancePort.Attempt attempt = call.getArgument(0);
            return new DeviceAccessAcceptancePort.Recording(true, new DeviceAccessAcceptancePort.Acceptance(
                    attempt.deviceId(), attempt.messageId(), attempt.payloadDigest(), attempt.protocol(),
                    attempt.receivedAt(), attempt.receivedAt().plusSeconds(1)));
        }).when(acceptancePort).record(any());
    }

    /** 新协议受理成功后才在标准主题出现协议保留、身份可信的信封。 */
    @Test
    void handsOffAcceptedAccessUplinkToSharedNormalizedTopic() throws Exception {
        UUID tenantId = Uuid7.generate();
        UUID projectId = Uuid7.generate();
        UUID deviceId = Uuid7.generate();
        UUID messageId = Uuid7.generate();
        byte[] payload = report(messageId, "{\"temperature\":26.5}");

        var accepted = ingressService.accept(new DeviceAccessUplinkMessage(
                tenantId, projectId, deviceId, TransportProtocol.COAP, payload,
                Instant.parse("2026-09-18T08:00:01Z"), TRACE,
                new AuthenticatedDeviceIdentity(tenantId, projectId, deviceId, 5)));

        assertThat(accepted.status())
                .isEqualTo(DeviceAccessUplinkIngressService.DeviceAccessUplinkAcceptance.Status.ACCEPTED);
        assertThat(accepted.messageId()).isEqualTo(messageId);
        assertThat(accepted.receivedAt()).isEqualTo(Instant.parse("2026-09-18T08:00:01Z"));
        ConsumerRecord<String, StandardUplinkMessage> record =
                accessProbe.poll(deviceId.toString(), 10, TimeUnit.SECONDS);
        assertThat(record).isNotNull();
        assertThat(record.key()).isEqualTo(deviceId.toString());
        assertThat(record.value().messageId()).isEqualTo(messageId);
        assertThat(record.value().tenantId()).isEqualTo(tenantId);
        assertThat(record.value().projectId()).isEqualTo(projectId);
        assertThat(record.value().deviceId()).isEqualTo(deviceId);
        assertThat(record.value().protocol()).isEqualTo(TransportProtocol.COAP);
        assertThat(record.value().type()).isEqualTo(StandardUplinkMessage.Type.PROPERTY_REPORT);
        assertThat(record.value().traceId()).isEqualTo(TRACE);
        assertThat(record.value().rawBytes()).isEqualTo(payload.length);
        assertThat(record.value().payload()).containsEntry("temperature", new BigDecimal("26.5"));
    }

    /** 重放不得再次上车：同一 messageId 的第二次受理只回首次结果。 */
    @Test
    void duplicateReplayDoesNotPublishSecondEnvelope() throws Exception {
        UUID tenantId = Uuid7.generate();
        UUID projectId = Uuid7.generate();
        UUID deviceId = Uuid7.generate();
        UUID messageId = Uuid7.generate();
        DeviceAccessUplinkMessage uplink = new DeviceAccessUplinkMessage(
                tenantId, projectId, deviceId, TransportProtocol.HTTP, report(messageId, "{\"temperature\":21}"),
                Instant.parse("2026-09-18T08:00:01Z"), TRACE,
                new AuthenticatedDeviceIdentity(tenantId, projectId, deviceId, 5));
        assertThat(ingressService.accept(uplink).status())
                .isEqualTo(DeviceAccessUplinkIngressService.DeviceAccessUplinkAcceptance.Status.ACCEPTED);
        assertThat(accessProbe.poll(deviceId.toString(), 10, TimeUnit.SECONDS)).isNotNull();

        when(acceptancePort.decide(any())).thenReturn(new DeviceAccessAcceptancePort.Decision.Duplicate(
                new DeviceAccessAcceptancePort.Acceptance(deviceId, messageId, "a".repeat(64),
                        TransportProtocol.HTTP, Instant.parse("2026-09-18T08:00:01Z"),
                        Instant.parse("2026-09-18T08:00:02Z"))));

        assertThat(ingressService.accept(uplink).status())
                .isEqualTo(DeviceAccessUplinkIngressService.DeviceAccessUplinkAcceptance.Status.DUPLICATE);
        assertThat(accessProbe.poll(deviceId.toString(), 2, TimeUnit.SECONDS))
                .as("重放不得再产生第二条标准上行").isNull();
    }

    /** MQTT 原始上行必须继续落到同一主题并保留 MQTT 协议，证明公共层改造未改变既有语义。 */
    @Test
    void keepsMqttRawPathOnSameNormalizedTopic() throws Exception {
        UUID deviceId = Uuid7.generate();
        UUID messageId = Uuid7.generate();
        RawUplinkMessage raw = new RawUplinkMessage(Uuid7.generate(), Uuid7.generate(), deviceId,
                "tc/v1/project/device/up/property/report", report(messageId, "{\"online\":true}"),
                1, false, "device-client", Instant.parse("2026-09-18T08:00:01Z"), TRACE);

        kafkaTemplate.send(RAW_UPLINK_TOPIC, deviceId.toString(), raw).get(10, TimeUnit.SECONDS);

        ConsumerRecord<String, StandardUplinkMessage> record =
                accessProbe.poll(deviceId.toString(), 10, TimeUnit.SECONDS);
        assertThat(record).isNotNull();
        assertThat(record.value().protocol()).isEqualTo(TransportProtocol.MQTT);
        assertThat(record.value().deviceId()).isEqualTo(deviceId);
        assertThat(record.value().messageId()).isEqualTo(messageId);
    }

    /**
     * 构造与 MQTT 同源的属性上报载荷。
     *
     * @param messageId 设备消息标识
     * @param payload 属性对象 JSON
     * @return 业务载荷字节
     */
    private static byte[] report(UUID messageId, String payload) {
        return ("{\"messageId\":\"" + messageId + "\",\"occurredAt\":\"2026-09-18T08:00:00Z\",\"payload\":"
                + payload + "}").getBytes(StandardCharsets.UTF_8);
    }

    /** 测试链路标识。 */
    private static final String TRACE = "0123456789abcdef0123456789abcdef";

    /** 测试专用标准主题观察装配。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class AccessProbeConfiguration {

        /**
         * 创建标准主题探针。
         *
         * @return 按设备 key 分邮箱的探针
         */
        @Bean
        AccessProbe accessProbe() {
            return new AccessProbe();
        }
    }

    /** 通过独立 consumer group 观察标准主题，避免共享 Topic 历史记录抢占断言。 */
    static final class AccessProbe {

        /** 按 Kafka key 分邮箱的标准上行记录。 */
        private final ConcurrentHashMap<String, LinkedBlockingQueue<ConsumerRecord<String, StandardUplinkMessage>>>
                records = new ConcurrentHashMap<>();

        /**
         * 等待当前测试设备对应的标准上行记录。
         *
         * @param key 本次发送使用的设备 key
         * @param timeout 最大等待值
         * @param unit 等待时间单位
         * @return 当前 key 的记录，超时返回 null
         * @throws InterruptedException 测试线程被中断
         */
        ConsumerRecord<String, StandardUplinkMessage> poll(String key, long timeout, TimeUnit unit)
                throws InterruptedException {
            return records.computeIfAbsent(key, ignored -> new LinkedBlockingQueue<>()).poll(timeout, unit);
        }

        /**
         * 记录标准上行消息。
         *
         * @param record 标准上行记录
         */
        @KafkaListener(topics = NORMALIZED_UPLINK_TOPIC, groupId = "ingestion-access-test-probe")
        void receive(ConsumerRecord<String, StandardUplinkMessage> record) {
            records.computeIfAbsent(record.key(), ignored -> new LinkedBlockingQueue<>()).add(record);
        }
    }
}
