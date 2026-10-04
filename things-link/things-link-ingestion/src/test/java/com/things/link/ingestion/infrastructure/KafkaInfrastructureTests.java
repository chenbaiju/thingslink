package com.things.link.ingestion.infrastructure;

import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.RawUplinkMessage;
import com.things.link.support.trace.TraceContext;
import com.things.link.testing.AbstractKafkaIntegrationTest;
import com.things.link.device.application.DeviceAccessScopeService;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** 使用真实 Kafka 验证 JSON 信封、deviceId key 与 traceId header 的端到端基础设施。 */
@Import(KafkaInfrastructureTests.KafkaTestConfiguration.class)
class KafkaInfrastructureTests extends AbstractKafkaIntegrationTest {

    /** 测试主题固定 3 分区，避免单分区环境掩盖 key 没有真正参与分区的问题。 */
    private static final String TOPIC = "tc.test.ingestion.infrastructure";

    /** Boot 自动装配并由 support 定制的 Kafka 模板。 */
    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    /** 测试 listener，用于观察消费线程里的 MDC。 */
    @Autowired
    private RecordingListener listener;

    /** 测试结束清理发起线程的 MDC。 */
    @AfterEach
    void clearTraceContext() {
        TraceContext.clear();
    }

    /** 原始上行信封应通过真实 broker 保持 deviceId key、JSON 类型和 traceId。 */
    @Test
    void carriesRawEnvelopeKeyAndTraceThroughRealKafka() throws Exception {
        UUID deviceId = Uuid7.generate();
        String traceId = "0123456789abcdef0123456789abcdef";
        RawUplinkMessage message = new RawUplinkMessage(
                Uuid7.generate(), Uuid7.generate(), deviceId,
                "tc/v1/project/device/up/property/report", "{}".getBytes(StandardCharsets.UTF_8),
                1, false, "client-1", Instant.now(), traceId);
        TraceContext.set(traceId);

        kafkaTemplate.send(TOPIC, message.partitionKey().toString(), message).get(10, TimeUnit.SECONDS);

        assertThat(listener.received.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(listener.key.get()).isEqualTo(deviceId.toString());
        assertThat(listener.message.get().deviceId()).isEqualTo(deviceId);
        assertThat(listener.traceId.get()).isEqualTo(traceId);
    }

    /** 测试专用 Topic 与 listener 装配。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class KafkaTestConfiguration {

        /**
         * 显式创建测试主题，验证工程配置不依赖 broker 自动建主题。
         *
         * @return 三分区测试主题
         */
        @Bean
        NewTopic ingestionInfrastructureTopic() {
            return TopicBuilder.name(TOPIC).partitions(3).replicas(1).build();
        }

        /** @return 记录消费结果的测试 listener */
        @Bean
        RecordingListener recordingListener() {
            return new RecordingListener();
        }

        /** @return 本测试不经过 EMQX 入口，提供隔离的身份解析替身以完成上下文装配 */
        @Bean
        DeviceAccessScopeService deviceAccessScopeService() {
            return mock(DeviceAccessScopeService.class);
        }
    }

    /** 在 listener 调用栈内记录反序列化结果与恢复后的 MDC。 */
    static final class RecordingListener {

        /** 消费完成信号。 */
        private final CountDownLatch received = new CountDownLatch(1);

        /** 收到的 Kafka key。 */
        private final AtomicReference<String> key = new AtomicReference<>();

        /** 反序列化后的原始上行信封。 */
        private final AtomicReference<RawUplinkMessage> message = new AtomicReference<>();

        /** listener 执行期间的 traceId。 */
        private final AtomicReference<String> traceId = new AtomicReference<>();

        /**
         * 接收测试消息。
         *
         * @param record Kafka 记录
         */
        @KafkaListener(topics = TOPIC)
        void receive(ConsumerRecord<String, RawUplinkMessage> record) {
            key.set(record.key());
            message.set(record.value());
            traceId.set(TraceContext.current());
            received.countDown();
        }
    }
}
