package com.things.link.support.kafka;

import com.things.link.support.observability.DataPlaneMetrics;
import com.things.link.support.observability.KafkaConsumerProcessingMetrics;
import com.things.link.shared.error.RetryableLeaseConflictException;
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
import org.springframework.util.backoff.FixedBackOff;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 验证 F15 normalized 持久接管只为明确数据库基础设施异常保留来源 offset。 */
class NormalizedIngressKafkaErrorHandlerTests {

    /** 目标记录固定 partition/offset，重复调用才能命中同一失败跟踪状态。 */
    private static final ConsumerRecord<String, String> RECORD =
            new ConsumerRecord<>("tc.device.uplink.normalized", 3, 11L, "device", "payload");

    /** 数据库连接失败在预算内不得提前恢复到 DLQ。 */
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

    /** 有效租约冲突在预算内必须保留来源记录，等待同一稳定身份于租约到期后接管。 */
    @Test
    void retriesActiveLeaseConflictBeforeRecovery() {
        AtomicInteger recovered = new AtomicInteger();
        DefaultErrorHandler handler = handler(recovered, new FixedBackOff(0L, 1L));

        assertThat(handle(handler, new RetryableLeaseConflictException("lease active"))).isFalse();
        assertThat(recovered).hasValue(0);
        assertThat(handle(handler, new RetryableLeaseConflictException("lease active"))).isTrue();
        assertThat(recovered).hasValue(1);
    }

    /** 数据约束错误不会随数据库恢复而自愈，必须立即进入已确认恢复路径。 */
    @Test
    void sendsPermanentConstraintFailureDirectlyToRecovery() {
        AtomicInteger recovered = new AtomicInteger();
        DefaultErrorHandler handler = handler(recovered, new FixedBackOff(0L, 10L));

        assertThat(handle(handler, new DataIntegrityViolationException("invalid row"))).isTrue();
        assertThat(recovered).hasValue(1);
    }

    /** 未分类代码错误不得占住分区 210 秒。 */
    @Test
    void sendsUnknownFailureDirectlyToRecovery() {
        AtomicInteger recovered = new AtomicInteger();
        DefaultErrorHandler handler = handler(recovered, new FixedBackOff(0L, 10L));

        assertThat(handle(handler, new IllegalStateException("unexpected state"))).isTrue();
        assertThat(recovered).hasValue(1);
    }

    /** normalized 工厂必须覆盖全局短重试并扩大 poll 窗口，同时保留 trace interceptor。 */
    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void normalizedFactoryOverridesGlobalErrorHandler() {
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
                configuration.normalizedIngressKafkaListenerContainerFactory(
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

    /** @return 使用可计数恢复器和测试退避建立与生产相同分类的处理器 */
    private static DefaultErrorHandler handler(AtomicInteger recovered, FixedBackOff backOff) {
        ConsumerRecordRecoverer recoverer = (record, exception) -> recovered.incrementAndGet();
        return KafkaTraceConfiguration.durableDatabaseIngressErrorHandler(recoverer, backOff);
    }

    /** @return 单次处理是否已完成 recoverer，可由容器提交来源 offset */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static boolean handle(DefaultErrorHandler handler, Exception failure) {
        return handler.handleOne(
                failure,
                RECORD,
                mock(Consumer.class),
                mock(MessageListenerContainer.class));
    }
}
