package com.things.link.alarm.application;

import com.things.link.alarm.domain.AlarmEvent;
import com.things.link.alarm.domain.AlarmInstanceRepository;
import com.things.link.alarm.domain.AlarmRule;
import com.things.link.alarm.domain.AlarmRuleRepository;
import com.things.link.shared.message.AlarmStateInvalidated;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** ADR0109自动评估只有真实新增不可变事件才发布事务内提示；提交边界由真实事务集成验证。 */
class AlarmEvaluationInvalidationTests {
    /** 同一身份与设备的合法上行事实。 */
    private final AlarmEvaluationInput input = new AlarmEvaluationInput(UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID(), "temperature", 100, Instant.EPOCH, Instant.EPOCH, "alarm-test");
    /** 告警规则权威替身。 */
    private final AlarmRuleRepository rules = mock(AlarmRuleRepository.class);
    /** 不可变事件与状态机CAS替身。 */
    private final AlarmInstanceRepository instances = mock(AlarmInstanceRepository.class);
    /** 捕获事务内事件，不能冒充Redis已经发布。 */
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    /** 状态机最低层服务。 */
    private final AlarmEvaluationService service = new AlarmEvaluationService(rules, instances, mock(AlarmMetrics.class));

    /** PENDING和紧随其后的零延迟ACTIVATED分别追加成功才发提示，不能只监听ACTIVATED通知服务。 */
    @Test
    void pendingAndActivatedPublishOnlyAfterAppend() {
        prepare(0);
        when(instances.appendEvent(any())).thenReturn(true);
        when(instances.update(any())).thenReturn(true);
        service.evaluate(input);
        var order = inOrder(instances, events);
        order.verify(instances).findActive(any(), any(), any(), any());
        order.verify(instances).create(any());
        order.verify(instances).appendEvent(any(AlarmEvent.class));
        order.verify(events).publishEvent(invalidation());
        order.verify(instances).update(any());
        order.verify(instances).appendEvent(any(AlarmEvent.class));
        order.verify(events).publishEvent(invalidation());
        verify(events, times(2)).publishEvent(invalidation());
    }

    /** 数据库去重no-op没有新的前台提示，也不把重复输入虚构成新迁移。 */
    @Test
    void duplicateAppendProducesNoInvalidation() {
        prepare(10);
        when(instances.appendEvent(any())).thenReturn(false);
        service.evaluate(input);
        verifyNoInteractions(events);
    }

    /** 设置真实字段形状，单测不注册生产Host或外部消息总线。 */
    private void prepare(int duration) {
        service.setApplicationEventPublisher(events);
        var rule = new AlarmRule(UUID.randomUUID(), input.tenantId(), input.projectId(), "高温", "HighTemperature",
                AlarmRule.OriginatorType.DEVICE, input.deviceId(), "temperature", AlarmRule.ComparisonOperator.GT,
                80, duration, AlarmRule.ComparisonOperator.LTE, 70, 0, AlarmRule.Severity.MAJOR, true, 0,
                Instant.EPOCH, Instant.EPOCH, null);
        when(rules.findEnabledByProperty(input.projectId(), input.deviceId(), input.propertyKey())).thenReturn(List.of(rule));
        when(instances.findActive(any(), any(), any(), any())).thenReturn(Optional.empty());
        when(instances.create(any())).thenReturn(true);
    }

    /** 对外事件只有三项权威范围，任何正文变化不进入提示。 */
    private AlarmStateInvalidated invalidation() {
        return new AlarmStateInvalidated(input.tenantId(), input.projectId(), input.deviceId());
    }
}
