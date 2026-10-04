package com.things.link.alarm.application;

import com.things.link.alarm.domain.AlarmErrorCode;
import com.things.link.alarm.domain.AlarmEvent;
import com.things.link.alarm.domain.AlarmInstance;
import com.things.link.alarm.domain.AlarmInstanceRepository;
import com.things.link.alarm.domain.AlarmRule;
import com.things.link.alarm.domain.AlarmRuleRepository;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.id.Uuid7;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationEventPublisherAware;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.OptionalLong;
import org.springframework.transaction.annotation.Transactional;

/**
 * 规则动作调用 S6 告警状态机的可信后台公开服务。
 *
 * <p>该端口不读取控制台身份，也不接受调用方给出的告警类型或严重程度；规则 Outbox 已携带确权后的项目、设备与
 * owner tenant，本服务仍重新读取 {@link AlarmRule} 并逐项匹配，防止伪造跨项目或跨设备动作。调用方必须先从
 * 可信执行信封恢复 {@code TenantContext}：生产链由 {@code RuleExecutionCoordinator} 建立并在 finally 清理；
 * 本服务不重复建立上下文，缺失时让项目 RLS fail-closed，避免把任意方法参数升级为可信隔离范围。</p>
 */
@Service
public class RuleAlarmActionService implements ApplicationEventPublisherAware {
    /** Spring保证生产服务初始化时注入；保留原独立状态机测试构造器。 */
    private ApplicationEventPublisher eventPublisher;

    /** 接收事务内事件总线；订阅者必须在提交后才能发布网络提示。 */
    @Override
    public void setApplicationEventPublisher(ApplicationEventPublisher publisher) {
        this.eventPublisher = publisher;
    }


    /** S6 告警规则权威读取端口。 */
    private final AlarmRuleRepository ruleRepository;

    /** 活动实例与不可变事件持久化端口。 */
    private final AlarmInstanceRepository instanceRepository;

    /** 首次 ACTIVATED 事件触发的既有 S6 通知意图服务。 */
    private final AlarmNotificationDeliveryService notificationDeliveryService;

    /** 可靠公开来源，与原规则动作事务一致。 */
    private final AlarmWebhookSource webhook;

    /** 状态迁移只记录固定事件类型，避免项目/设备进入指标标签。 */
    private final AlarmMetrics metrics;

    /**
     * @param ruleRepository 告警规则读取端口
     * @param instanceRepository 告警实例与事件端口
     * @param notificationDeliveryService S6 通知意图服务
     * @param metrics 低基数状态迁移指标
     */
    @Autowired
    public RuleAlarmActionService(
            AlarmRuleRepository ruleRepository,
            AlarmInstanceRepository instanceRepository,
            AlarmNotificationDeliveryService notificationDeliveryService,
            AlarmMetrics metrics, AlarmWebhookSource webhook) {
        this.webhook = webhook;
        this.ruleRepository = ruleRepository;
        this.instanceRepository = instanceRepository;
        this.notificationDeliveryService = notificationDeliveryService;
        this.metrics = metrics;
    }

    /** 独立领域单测保留原构造器；生产强制五参装配。 */
    public RuleAlarmActionService(AlarmRuleRepository rules, AlarmInstanceRepository instances,
            AlarmNotificationDeliveryService notifications, AlarmMetrics metrics) {
        this(rules, instances, notifications, metrics, null);
    }

    /**
     * 按告警规则直接创建 ACTIVE 实例；已有活动代时幂等 no-op。
     *
     * <p>动作节点表达的是显式“创建告警”，不重复执行 S6 的阈值与持续时长判断。只有实例与带 source message 的
     * ACTIVATED 事件均首次写入后才展开通知，任一步异常都会回滚同一事务。</p>
     *
     * @param input 可信规则动作输入
     * @return 命中实例与是否真正激活
     */
    @Transactional
    public RuleAlarmActionResult create(RuleAlarmActionInput input) {
        AlarmRule rule = requireMatchingRule(input);
        var generation = webhook == null ? OptionalLong.empty() : webhook.capture(rule.tenantId(), rule.projectId());
        var existing = instanceRepository.findActive(
                input.projectId(), rule.id(), input.deviceId(), rule.alarmType());
        if (existing.isPresent()) {
            return new RuleAlarmActionResult(existing.orElseThrow().id(), false);
        }

        AlarmInstance instance = activeInstance(rule, input);
        if (!instanceRepository.create(instance)) {
            return new RuleAlarmActionResult(instanceRepository.findActive(
                    input.projectId(), rule.id(), input.deviceId(), rule.alarmType())
                    .map(AlarmInstance::id).orElse(null), false);
        }
        AlarmEvent event = event(instance, input, AlarmEvent.EventType.ACTIVATED, null);
        if (!instanceRepository.appendEvent(event)) {
            throw new IllegalStateException("新告警实例必须追加唯一 ACTIVATED 事件");
        }
        if (webhook != null) webhook.append(instance, event, generation);
        AlarmStateInvalidationPublisher.publish(eventPublisher, instance);
        metrics.recordTransition(AlarmEvent.EventType.ACTIVATED);
        notificationDeliveryService.createForActivatedEvent(instance, event);
        return new RuleAlarmActionResult(instance.id(), true);
    }

    /**
     * 按规则权威键清除活动实例；不存在、已清除或并发 CAS 失败时幂等 no-op。
     *
     * <p>后台规则动作没有人工 actor，不能伪造控制台人工维护；现有 S6 枚举下使用 {@code AUTO_RECOVERY}，并保留
     * 原实例 ACK 维度。事件携带 source message，以数据库唯一键吸收至少一次重投。</p>
     *
     * @param input 可信规则动作输入
     * @return 命中实例与是否真正清除
     */
    @Transactional
    public RuleAlarmActionResult clear(RuleAlarmActionInput input) {
        AlarmRule rule = requireMatchingRule(input);
        var generation = webhook == null ? OptionalLong.empty() : webhook.capture(rule.tenantId(), rule.projectId());
        var existing = instanceRepository.findActive(
                input.projectId(), rule.id(), input.deviceId(), rule.alarmType());
        if (existing.isEmpty()) {
            return new RuleAlarmActionResult(null, false);
        }
        AlarmInstance current = existing.orElseThrow();
        AlarmInstance cleared = clearedInstance(current, input);
        if (!instanceRepository.update(cleared)) {
            return new RuleAlarmActionResult(current.id(), false);
        }
        AlarmEvent event = event(cleared, input, AlarmEvent.EventType.CLEARED,
                AlarmInstance.ClearReason.AUTO_RECOVERY);
        if (!instanceRepository.appendEvent(event)) {
            throw new IllegalStateException("已清除告警必须追加唯一 CLEARED 事件");
        }
        if (webhook != null) webhook.append(cleared, event, generation);
        AlarmStateInvalidationPublisher.publish(eventPublisher, cleared);
        metrics.recordTransition(AlarmEvent.EventType.CLEARED);
        return new RuleAlarmActionResult(current.id(), true);
    }

    /**
     * 重新读取并匹配规则归属；公开端口不信任消息体中的跨领域身份。
     *
     * @param input 可信通道解码后的输入
     * @return 项目内匹配设备与租户的告警规则
     */
    private AlarmRule requireMatchingRule(RuleAlarmActionInput input) {
        if (input == null) {
            throw new IllegalArgumentException("规则告警动作输入不能为空");
        }
        AlarmRule rule = ruleRepository.findById(input.projectId(), input.alarmRuleId())
                .orElseThrow(() -> new BusinessException(AlarmErrorCode.RULE_NOT_FOUND));
        if (!rule.projectId().equals(input.projectId()) || !rule.tenantId().equals(input.tenantId())
                || rule.originatorType() != AlarmRule.OriginatorType.DEVICE
                || !rule.originatorId().equals(input.deviceId())) {
            throw new BusinessException(AlarmErrorCode.RULE_INVALID, "告警规则与项目、租户或设备不匹配");
        }
        return rule;
    }

    /** 按规则权威属性构造直接进入 ACTIVE 的一代实例。 */
    private static AlarmInstance activeInstance(AlarmRule rule, RuleAlarmActionInput input) {
        return new AlarmInstance(
                Uuid7.generate(), rule.tenantId(), rule.projectId(), rule.id(), rule.originatorType(),
                rule.originatorId(), rule.alarmType(), rule.severity(), AlarmInstance.ConditionState.ACTIVE,
                AlarmInstance.AckState.UNACKNOWLEDGED, null, input.receivedAt(), null, input.receivedAt(), null,
                null, null, input.receivedAt(), input.occurredAt(), rule.triggerThreshold(), 0,
                input.receivedAt(), input.receivedAt());
    }

    /** 保留规则、来源与 ACK 维度，只结束当前事故代并携带原 version 供仓储 CAS。 */
    private static AlarmInstance clearedInstance(AlarmInstance value, RuleAlarmActionInput input) {
        return new AlarmInstance(
                value.id(), value.tenantId(), value.projectId(), value.ruleId(), value.originatorType(),
                value.originatorId(), value.alarmType(), value.severity(), AlarmInstance.ConditionState.CLEARED,
                value.ackState(), AlarmInstance.ClearReason.AUTO_RECOVERY, value.firstConditionAt(),
                value.recoveryConditionAt(), value.activatedAt(), input.receivedAt(), value.acknowledgedAt(),
                value.acknowledgedBy(), input.receivedAt(), input.occurredAt(), value.lastValue(), value.version(),
                value.createdAt(), input.receivedAt());
    }

    /** 按 source message 生成不可变迁移事实；后台动作没有人工 actor。 */
    private static AlarmEvent event(
            AlarmInstance instance,
            RuleAlarmActionInput input,
            AlarmEvent.EventType type,
            AlarmInstance.ClearReason clearReason) {
        return new AlarmEvent(
                Uuid7.generate(), instance.tenantId(), instance.projectId(), instance.id(), type,
                input.messageId(), input.traceId(), instance.lastValue(), input.occurredAt(), input.receivedAt(),
                null, instance.conditionState(), instance.ackState(), clearReason);
    }
}
