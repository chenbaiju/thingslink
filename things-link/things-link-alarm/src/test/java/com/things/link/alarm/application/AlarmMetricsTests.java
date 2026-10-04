package com.things.link.alarm.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.things.link.alarm.domain.AlarmEvent;
import com.things.link.alarm.domain.AlarmInstanceRepository;
import com.things.link.alarm.domain.AlarmRuleRepository;
import com.things.link.alarm.domain.NotificationChannel;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.Test;

/** S6 告警指标必须只有固定状态/原因标签，且评估失败不能静默。 */
class AlarmMetricsTests {

    /** 成功迁移和输入契约失败分别写入可聚合的低基数 counter。 */
    @Test
    void recordsFixedTransitionAndEvaluationLabels() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AlarmMetrics metrics = new AlarmMetrics(registry);
        AlarmRuleRepository rules = mock(AlarmRuleRepository.class);
        AlarmEvaluationService service =
                new AlarmEvaluationService(rules, mock(AlarmInstanceRepository.class), metrics);

        metrics.recordTransition(AlarmEvent.EventType.ACTIVATED);
        when(rules.findEnabledByProperty(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.List.of());
        service.evaluate(
                new AlarmEvaluationInput(
                        java.util.UUID.randomUUID(),
                        java.util.UUID.randomUUID(),
                        java.util.UUID.randomUUID(),
                        java.util.UUID.randomUUID(),
                        "temperature",
                        20D,
                        java.time.Instant.now(),
                        java.time.Instant.now(),
                        "test"));
        assertThatThrownBy(() -> service.evaluate(null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(
                        registry.get(AlarmMetrics.TRANSITION)
                                .tag("event_type", "activated")
                                .counter()
                                .count())
                .isEqualTo(1D);
        assertThat(
                        registry.get(AlarmMetrics.EVALUATION)
                                .tags("result", "success", "reason", "none")
                                .counter()
                                .count())
                .isEqualTo(1D);
        assertThat(
                        registry.get(AlarmMetrics.EVALUATION)
                                .tags("result", "failure", "reason", "invalid_input")
                                .counter()
                                .count())
                .isEqualTo(1D);
        metrics.recordNotificationIntent(
                NotificationChannel.EMAIL, AlarmMetrics.NotificationIntentResult.CREATED);
        metrics.recordNotificationIntent(
                NotificationChannel.EMAIL, AlarmMetrics.NotificationIntentResult.DEDUPLICATED);
        assertThat(
                        registry.get(AlarmMetrics.NOTIFICATION_INTENT)
                                .tags("channel", "email", "result", "created")
                                .counter()
                                .count())
                .isEqualTo(1D);
        assertThat(
                        registry.get(AlarmMetrics.NOTIFICATION_INTENT)
                                .tags("channel", "email", "result", "deduplicated")
                                .counter()
                                .count())
                .isEqualTo(1D);
        assertThat(registry.find(AlarmMetrics.NOTIFICATION_INTENT).meters())
                .allSatisfy(
                        meter ->
                                assertThat(meter.getId().getTags())
                                        .extracting(tag -> tag.getKey())
                                        .containsExactlyInAnyOrder("channel", "result"));
        metrics.recordNotificationDelivery(
                NotificationChannel.WEBHOOK,
                AlarmMetrics.NotificationDeliveryResult.RETRY_SCHEDULED,
                1_000_000L);
        assertThat(
                        registry.get(AlarmMetrics.NOTIFICATION_DELIVERY)
                                .tags("channel", "webhook", "result", "failed", "reason", "retryable")
                                .counter()
                                .count())
                .isEqualTo(1D);
    }
}
