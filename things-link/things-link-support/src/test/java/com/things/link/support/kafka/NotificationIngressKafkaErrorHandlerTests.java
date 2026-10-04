package com.things.link.support.kafka;

import com.things.link.support.observability.DataPlaneMetrics;
import com.things.link.support.observability.KafkaConsumerProcessingMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.boot.kafka.autoconfigure.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.backoff.BackOffExecution;
import org.springframework.util.backoff.ExponentialBackOff;
import org.springframework.util.backoff.FixedBackOff;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 验证 F11 通知入口只为明确数据库瞬时故障保留有界 Kafka 所有权。 */
class NotificationIngressKafkaErrorHandlerTests {

    /** 测试记录固定身份，避免不同用例共享失败跟踪键。 */
    private static final ConsumerRecord<String, String> RECORD =
            new ConsumerRecord<>("tc.rule.notification", 2, 7L, "event", "payload");

    /** 数据库连接失败首次不得恢复到 DLQ，重试预算耗尽后才允许调用 recoverer。 */
    @Test
    void retriesDatabaseConnectionFailureBeforeRecovery() {
        AtomicInteger recovered = new AtomicInteger();
        DefaultErrorHandler handler = handler(recovered, new FixedBackOff(0L, 1L));
        Exception failure = new RuntimeException(
                "listener wrapper", new CannotGetJdbcConnectionException("database unavailable"));

        assertThat(handle(handler, failure)).isFalse();
        assertThat(recovered).hasValue(0);
        assertThat(handle(handler, failure)).isTrue();
        assertThat(recovered).hasValue(1);
    }

    /** 信封/分区键错误重放不会自愈，必须第一次就交给可确认 DLQ。 */
    @Test
    void sendsPermanentProtocolFailureDirectlyToRecovery() {
        AtomicInteger recovered = new AtomicInteger();
        DefaultErrorHandler handler = handler(recovered, new FixedBackOff(0L, 10L));

        assertThat(handle(handler, new IllegalArgumentException("invalid event key"))).isTrue();
        assertThat(recovered).hasValue(1);
    }

    /** 数据约束错误虽来自 JDBC，也不属于可自行恢复的基础设施异常，必须立即形成失败事实。 */
    @Test
    void sendsPermanentDatabaseConstraintFailureDirectlyToRecovery() {
        AtomicInteger recovered = new AtomicInteger();
        DefaultErrorHandler handler = handler(recovered, new FixedBackOff(0L, 10L));

        assertThat(handle(handler, new DataIntegrityViolationException("duplicate delivery"))).isTrue();
        assertThat(recovered).hasValue(1);
    }

    /** 未分类代码错误不能占用 210 秒数据库恢复窗口，避免坏消息长期阻塞同分区。 */
    @Test
    void sendsUnknownRuntimeFailureDirectlyToRecovery() {
        AtomicInteger recovered = new AtomicInteger();
        DefaultErrorHandler handler = handler(recovered, new FixedBackOff(0L, 10L));

        assertThat(handle(handler, new IllegalStateException("unexpected state"))).isTrue();
        assertThat(recovered).hasValue(1);
    }

    /** 生产退避必须覆盖 180 秒门禁线，又不得漂移成无限重放。 */
    @Test
    void productionBackOffIsBoundedBeyondC4bRecoveryWindow() {
        ExponentialBackOff backOff = KafkaTraceConfiguration.notificationIngressBackOff();

        assertThat(backOff.getInitialInterval()).isEqualTo(1_000L);
        assertThat(backOff.getJitter()).isEqualTo(250L);
        assertThat(backOff.getMultiplier()).isEqualTo(1.5D);
        assertThat(backOff.getMaxInterval()).isEqualTo(5_000L);
        assertThat(backOff.getMaxElapsedTime()).isEqualTo(210_000L);

        BackOffExecution execution = backOff.start();
        long elapsed = 0L;
        long interval;
        while ((interval = execution.nextBackOff()) != BackOffExecution.STOP) {
            assertThat(interval).isBetween(1_000L, 5_000L);
            elapsed += interval;
        }
        assertThat(elapsed).isBetween(210_000L, 215_000L);
    }

    /** 专属工厂必须覆盖 Boot 注入的全局短重试，同时把 poll 窗口扩大到安全上限。 */
    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void notificationFactoryOverridesGlobalErrorHandler() {
        KafkaTraceConfiguration configuration = new KafkaTraceConfiguration();
        ConcurrentKafkaListenerContainerFactoryConfigurer configurer =
                mock(ConcurrentKafkaListenerContainerFactoryConfigurer.class);
        ConsumerFactory<Object, Object> consumerFactory = mock(ConsumerFactory.class);
        CommonErrorHandler globalHandler = mock(CommonErrorHandler.class);
        when(consumerFactory.getConfigurationProperties()).thenReturn(Map.of());
        doAnswer(invocation -> {
            ConcurrentKafkaListenerContainerFactory factory = invocation.getArgument(0);
            factory.setConsumerFactory(invocation.getArgument(1));
            factory.setCommonErrorHandler(globalHandler);
            return null;
        }).when(configurer).configure(
                org.mockito.ArgumentMatchers.any(ConcurrentKafkaListenerContainerFactory.class),
                org.mockito.ArgumentMatchers.any(ConsumerFactory.class));

        ConcurrentKafkaListenerContainerFactory<Object, Object> factory =
                configuration.notificationIngressKafkaListenerContainerFactory(
                        configurer,
                        consumerFactory,
                        mock(KafkaConsumerProcessingMetrics.class),
                        mock(KafkaTemplate.class),
                        new DataPlaneMetrics(new SimpleMeterRegistry()));

        assertThat(ReflectionTestUtils.getField(factory, "commonErrorHandler"))
                .isInstanceOf(DefaultErrorHandler.class)
                .isNotSameAs(globalHandler);
        assertThat(factory.getConsumerFactory().getConfigurationProperties())
                .containsEntry(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 50)
                .containsEntry(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 600_000);
        assertThat(factory.getRecordInterceptor()).isInstanceOf(KafkaTraceRecordInterceptor.class);
    }

    /** @return 使用可计数 recoverer 与指定测试退避创建的专属处理器 */
    private static DefaultErrorHandler handler(AtomicInteger recovered, FixedBackOff backOff) {
        ConsumerRecordRecoverer recoverer = (record, exception) -> recovered.incrementAndGet();
        return KafkaTraceConfiguration.notificationIngressErrorHandler(recoverer, backOff);
    }

    /** @return 单次调用是否已经成功恢复并可由容器提交 offset */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static boolean handle(DefaultErrorHandler handler, Exception failure) {
        return handler.handleOne(
                failure,
                RECORD,
                mock(Consumer.class),
                mock(MessageListenerContainer.class));
    }
}
