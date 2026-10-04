package com.things.link.alarm.application;

import com.things.link.alarm.domain.AlarmEvent;
import com.things.link.shared.message.AlarmStateInvalidated;
import org.springframework.context.ApplicationEventPublisher;
import com.things.link.alarm.domain.AlarmInstance;
import com.things.link.alarm.domain.AlarmInstanceRepository;
import com.things.link.alarm.domain.AlarmRule;
import com.things.link.alarm.domain.AlarmRuleRepository;
import com.things.link.shared.error.BusinessException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** S9-3 规则告警后台公开端口的权威规则、幂等、CAS 与通知边界单元测试。 */
class RuleAlarmActionServiceTests {

    /** 只捕获事务内类型化事件，AFTER_COMMIT与回滚由真实集成覆盖。 */
    private final ApplicationEventPublisher eventPublisher = org.mockito.Mockito.mock(ApplicationEventPublisher.class);

    /** 告警规则仓储替身。 */
    private AlarmRuleRepository rules;

    /** 告警实例和事件仓储替身。 */
    private AlarmInstanceRepository instances;

    /** 首次激活后既有通知服务替身。 */
    private AlarmNotificationDeliveryService notifications;

    /** 被测可信后台公开服务。 */
    private RuleAlarmActionService service;

    /** 每个用例使用独立替身，避免调用历史影响幂等断言。 */
    @BeforeEach
    void setUp() {
        rules = mock(AlarmRuleRepository.class);
        instances = mock(AlarmInstanceRepository.class);
        notifications = mock(AlarmNotificationDeliveryService.class);
        service = new RuleAlarmActionService(rules, instances, notifications,
                new AlarmMetrics(new SimpleMeterRegistry()));
        service.setApplicationEventPublisher(eventPublisher);
    }

    /** 创建动作直接进入 ACTIVE，使用规则权威类型/级别，并只在事件首次插入后触发通知。 */
    @Test
    void createActivatesFromAuthoritativeRuleAndNotifiesOnce() {
        RuleAlarmActionInput input = input();
        AlarmRule rule = rule(input, AlarmRule.Severity.CRITICAL);
        when(rules.findById(input.projectId(), input.alarmRuleId())).thenReturn(Optional.of(rule));
        when(instances.findActive(input.projectId(), rule.id(), input.deviceId(), rule.alarmType()))
                .thenReturn(Optional.empty());
        when(instances.create(any())).thenReturn(true);
        when(instances.appendEvent(any())).thenReturn(true);

        RuleAlarmActionResult result = service.create(input);

        ArgumentCaptor<AlarmInstance> instanceCaptor = ArgumentCaptor.forClass(AlarmInstance.class);
        ArgumentCaptor<AlarmEvent> eventCaptor = ArgumentCaptor.forClass(AlarmEvent.class);
        verify(instances).create(instanceCaptor.capture());
        verify(instances).appendEvent(eventCaptor.capture());
        AlarmInstance created = instanceCaptor.getValue();
        AlarmEvent activated = eventCaptor.getValue();
        verify(eventPublisher).publishEvent(new AlarmStateInvalidated(input.tenantId(), input.projectId(), input.deviceId()));
        assertThat(result.changed()).isTrue();
        assertThat(result.alarmInstanceId()).isEqualTo(created.id());
        assertThat(created.conditionState()).isEqualTo(AlarmInstance.ConditionState.ACTIVE);
        assertThat(created.alarmType()).isEqualTo(rule.alarmType());
        assertThat(created.severity()).isEqualTo(rule.severity());
        assertThat(created.originatorId()).isEqualTo(rule.originatorId());
        assertThat(created.activatedAt()).isEqualTo(input.receivedAt());
        assertThat(activated.eventType()).isEqualTo(AlarmEvent.EventType.ACTIVATED);
        assertThat(activated.sourceMessageId()).isEqualTo(input.messageId());
        assertThat(activated.actorId()).isNull();
        verify(notifications).createForActivatedEvent(created, activated);
    }

    /** 已有活动代时创建保持 no-op，不追加重复事件或通知意图。 */
    @Test
    void createIsNoOpWhenActiveGenerationExists() {
        RuleAlarmActionInput input = input();
        AlarmRule rule = rule(input, AlarmRule.Severity.MAJOR);
        AlarmInstance active = active(rule, input, AlarmInstance.AckState.UNACKNOWLEDGED);
        when(rules.findById(input.projectId(), input.alarmRuleId())).thenReturn(Optional.of(rule));
        when(instances.findActive(input.projectId(), rule.id(), input.deviceId(), rule.alarmType()))
                .thenReturn(Optional.of(active));

        RuleAlarmActionResult result = service.create(input);

        assertThat(result).isEqualTo(new RuleAlarmActionResult(active.id(), false));
        verify(instances, never()).create(any());
        verify(instances, never()).appendEvent(any());
        org.mockito.Mockito.verifyNoInteractions(eventPublisher);
        verify(notifications, never()).createForActivatedEvent(any(), any());
    }

    /** 即便消息来自可信队列，规则绑定设备不匹配也必须在写状态前 fail-closed。 */
    @Test
    void rejectsRuleBoundToAnotherDevice() {
        RuleAlarmActionInput input = input();
        AlarmRule mismatched = new AlarmRule(
                input.alarmRuleId(), input.tenantId(), input.projectId(), "高温", "HighTemperature",
                AlarmRule.OriginatorType.DEVICE, UUID.randomUUID(), "temperature",
                AlarmRule.ComparisonOperator.GT, 80, 0, AlarmRule.ComparisonOperator.LTE, 70, 0,
                AlarmRule.Severity.CRITICAL, true, 0, input.receivedAt(), input.receivedAt(), null);
        when(rules.findById(input.projectId(), input.alarmRuleId())).thenReturn(Optional.of(mismatched));

        assertThatThrownBy(() -> service.create(input)).isInstanceOf(BusinessException.class);
        verify(instances, never()).create(any());
        verify(instances, never()).update(any());
    }

    /** 清除动作保留 ACK，使用无人工 actor 的 AUTO_RECOVERY，并让仓储以原 version 做 CAS。 */
    @Test
    void clearPreservesAcknowledgementAndAppendsSourceEvent() {
        RuleAlarmActionInput input = input();
        AlarmRule rule = rule(input, AlarmRule.Severity.WARNING);
        AlarmInstance active = active(rule, input, AlarmInstance.AckState.ACKNOWLEDGED);
        when(rules.findById(input.projectId(), input.alarmRuleId())).thenReturn(Optional.of(rule));
        when(instances.findActive(input.projectId(), rule.id(), input.deviceId(), rule.alarmType()))
                .thenReturn(Optional.of(active));
        when(instances.update(any())).thenReturn(true);
        when(instances.appendEvent(any())).thenReturn(true);

        RuleAlarmActionResult result = service.clear(input);

        ArgumentCaptor<AlarmInstance> instanceCaptor = ArgumentCaptor.forClass(AlarmInstance.class);
        ArgumentCaptor<AlarmEvent> eventCaptor = ArgumentCaptor.forClass(AlarmEvent.class);
        verify(instances).update(instanceCaptor.capture());
        verify(instances).appendEvent(eventCaptor.capture());
        verify(eventPublisher).publishEvent(new AlarmStateInvalidated(input.tenantId(), input.projectId(), input.deviceId()));
        AlarmInstance cleared = instanceCaptor.getValue();
        AlarmEvent event = eventCaptor.getValue();
        assertThat(result).isEqualTo(new RuleAlarmActionResult(active.id(), true));
        assertThat(cleared.version()).isEqualTo(active.version());
        assertThat(cleared.conditionState()).isEqualTo(AlarmInstance.ConditionState.CLEARED);
        assertThat(cleared.ackState()).isEqualTo(AlarmInstance.AckState.ACKNOWLEDGED);
        assertThat(cleared.clearReason()).isEqualTo(AlarmInstance.ClearReason.AUTO_RECOVERY);
        assertThat(event.sourceMessageId()).isEqualTo(input.messageId());
        assertThat(event.actorId()).isNull();
        assertThat(event.clearReason()).isEqualTo(AlarmInstance.ClearReason.AUTO_RECOVERY);
        verify(notifications, never()).createForActivatedEvent(any(), any());
    }

    /** 无活动代与并发 CAS 失败都保持 no-op，不伪造清除事件。 */
    @Test
    void clearIsNoOpWhenAbsentOrCasLoses() {
        RuleAlarmActionInput input = input();
        AlarmRule rule = rule(input, AlarmRule.Severity.INFO);
        when(rules.findById(input.projectId(), input.alarmRuleId())).thenReturn(Optional.of(rule));
        when(instances.findActive(input.projectId(), rule.id(), input.deviceId(), rule.alarmType()))
                .thenReturn(Optional.empty());

        assertThat(service.clear(input)).isEqualTo(new RuleAlarmActionResult(null, false));
        verify(instances, never()).update(any());
        verify(instances, never()).appendEvent(any());
        org.mockito.Mockito.verifyNoInteractions(eventPublisher);

        AlarmInstance active = active(rule, input, AlarmInstance.AckState.UNACKNOWLEDGED);
        when(instances.findActive(input.projectId(), rule.id(), input.deviceId(), rule.alarmType()))
                .thenReturn(Optional.of(active));
        when(instances.update(any())).thenReturn(false);

        assertThat(service.clear(input)).isEqualTo(new RuleAlarmActionResult(active.id(), false));
        verify(instances, never()).appendEvent(any());
        org.mockito.Mockito.verifyNoInteractions(eventPublisher);
    }

    /** 构造字段完整且双时间不同的可信输入，便于断言不会混淆设备时间与平台时间。 */
    private static RuleAlarmActionInput input() {
        return new RuleAlarmActionInput(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                Instant.parse("2026-08-14T01:59:59Z"), Instant.parse("2026-08-14T02:00:00Z"), "trace-rule-alarm");
    }

    /** 按输入身份构造权威 S6 告警规则。 */
    private static AlarmRule rule(RuleAlarmActionInput input, AlarmRule.Severity severity) {
        return new AlarmRule(
                input.alarmRuleId(), input.tenantId(), input.projectId(), "高温", "HighTemperature",
                AlarmRule.OriginatorType.DEVICE, input.deviceId(), "temperature",
                AlarmRule.ComparisonOperator.GT, 80, 0, AlarmRule.ComparisonOperator.LTE, 70, 0,
                severity, true, 0, input.receivedAt(), input.receivedAt(), null);
    }

    /** 构造可清除的 ACTIVE 实例；ACK 时间与 actor 同步满足 S6 正交状态约束。 */
    private static AlarmInstance active(
            AlarmRule rule,
            RuleAlarmActionInput input,
            AlarmInstance.AckState ackState) {
        boolean acknowledged = ackState == AlarmInstance.AckState.ACKNOWLEDGED;
        return new AlarmInstance(
                UUID.randomUUID(), rule.tenantId(), rule.projectId(), rule.id(), rule.originatorType(),
                rule.originatorId(), rule.alarmType(), rule.severity(), AlarmInstance.ConditionState.ACTIVE, ackState,
                null, input.receivedAt().minusSeconds(10), null, input.receivedAt().minusSeconds(10), null,
                acknowledged ? input.receivedAt().minusSeconds(5) : null,
                acknowledged ? UUID.randomUUID() : null, input.receivedAt().minusSeconds(1), input.occurredAt(),
                90, 3, input.receivedAt().minusSeconds(10), input.receivedAt().minusSeconds(1));
    }
}
