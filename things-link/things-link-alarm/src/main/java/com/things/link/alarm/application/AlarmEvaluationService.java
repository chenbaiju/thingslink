package com.things.link.alarm.application;

import com.things.link.alarm.domain.AlarmEvent;
import com.things.link.alarm.domain.AlarmInstance;
import com.things.link.alarm.domain.AlarmInstanceRepository;
import com.things.link.alarm.domain.AlarmRule;
import com.things.link.alarm.domain.AlarmRuleRepository;
import com.things.link.shared.id.Uuid7;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationEventPublisherAware;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;

/** 已验证数值属性的告警评估公开端口；与 telemetry 事实写入加入同一事务。 */
@Service
public class AlarmEvaluationService implements ApplicationEventPublisherAware {
    /** Spring保证生产服务初始化时注入；保留原独立状态机测试构造器。 */
    private ApplicationEventPublisher eventPublisher;

    /** 接收事务内事件总线；订阅者必须在提交后才能发布网络提示。 */
    @Override
    public void setApplicationEventPublisher(ApplicationEventPublisher publisher) {
        this.eventPublisher = publisher;
    }

    /** 规则查询端口。 */
    private final AlarmRuleRepository ruleRepository;

    /** 当前事故和事件端口。 */
    private final AlarmInstanceRepository instanceRepository;

    /** 告警状态机低基数指标。 */
    private final AlarmMetrics metrics;

    /** 生产强制装配可靠公开来源，独立状态机单测可为空。 */
    private final AlarmWebhookSource webhook;

    /** 告警激活后生成投递意图；测试中的三参构造器可为空。 */
    private final AlarmNotificationDeliveryService notificationDeliveryService;

    /**
     * @param ruleRepository 规则端口 @param instanceRepository 实例端口 @param metrics 状态机指标
     */
    @Autowired
    public AlarmEvaluationService(
            AlarmRuleRepository ruleRepository,
            AlarmInstanceRepository instanceRepository,
            AlarmMetrics metrics,
            AlarmNotificationDeliveryService notificationDeliveryService, AlarmWebhookSource webhook) {
        this.webhook = webhook;
        this.ruleRepository = ruleRepository;
        this.instanceRepository = instanceRepository;
        this.metrics = metrics;
        this.notificationDeliveryService = notificationDeliveryService;
    }

    /** 保留旧独立测试装配；生产始终使用五参构造器。 */
    public AlarmEvaluationService(AlarmRuleRepository rules, AlarmInstanceRepository instances, AlarmMetrics metrics,
            AlarmNotificationDeliveryService notifications) {
        this(rules, instances, metrics, notifications, null);
    }

    /** 兼容独立状态机单测；生产组件扫描始终使用五参构造器。 */
    public AlarmEvaluationService(
            AlarmRuleRepository ruleRepository,
            AlarmInstanceRepository instanceRepository,
            AlarmMetrics metrics) {
        this(ruleRepository, instanceRepository, metrics, null);
    }

    /**
     * 对一条已由 inbox 抢占的数值属性执行全部匹配规则。
     *
     * <p>调用方必须先完成 messageId 去重、设备归属和物模型校验；这里因此不读取 telemetry/dev 表， 保持架构文档 10.4
     * 的表所有权。发生异常时会与时序点、影子、消息日志一起回滚。
     */
    @Transactional
    public void evaluate(AlarmEvaluationInput input) {
        try {
            validateInput(input);
            for (AlarmRule rule :
                    ruleRepository.findEnabledByProperty(
                            input.projectId(), input.deviceId(), input.propertyKey())) {
                if (!rule.tenantId().equals(input.tenantId())) throw new IllegalArgumentException("告警规则租户与可信输入不一致");
                var generation = webhook == null ? OptionalLong.empty() : webhook.capture(rule.tenantId(), rule.projectId());
                evaluateRule(rule, input, generation);
            }
            metrics.recordEvaluationSuccess();
        } catch (IllegalArgumentException exception) {
            metrics.recordEvaluationFailure(AlarmMetrics.EvaluationFailureReason.INVALID_INPUT);
            throw exception;
        } catch (DataAccessException exception) {
            metrics.recordEvaluationFailure(AlarmMetrics.EvaluationFailureReason.PERSISTENCE);
            throw exception;
        } catch (RuntimeException exception) {
            metrics.recordEvaluationFailure(AlarmMetrics.EvaluationFailureReason.UNEXPECTED);
            throw exception;
        }
    }

    /** 校验跨模块公开端口的最小事实输入，避免错误样本污染持续时间状态机。 */
    private static void validateInput(AlarmEvaluationInput input) {
        if (input == null
                || input.messageId() == null
                || input.tenantId() == null
                || input.projectId() == null
                || input.deviceId() == null
                || input.propertyKey() == null
                || input.receivedAt() == null
                || input.occurredAt() == null
                || input.traceId() == null
                || input.traceId().isBlank()
                || !Double.isFinite(input.value())) throw new IllegalArgumentException("告警评估输入不完整");
    }

    /** 按单规则状态机处理；一条上行最多为一个实例产生一次状态迁移事件。 */
    private void evaluateRule(AlarmRule rule, AlarmEvaluationInput input, OptionalLong generation) {
        Optional<AlarmInstance> existing =
                instanceRepository.findActive(
                        input.projectId(), rule.id(), input.deviceId(), rule.alarmType());
        if (existing.isEmpty()) {
            if (rule.triggerOperator().matches(input.value(), rule.triggerThreshold()))
                createPending(rule, input, generation);
            return;
        }
        AlarmInstance instance = existing.orElseThrow();
        // 平台 receivedAt 是持续时长的事实时间；旧消息不能把 PENDING/恢复计时倒拨。
        if (!instanceRepository.acceptsReceivedAt(instance, input.receivedAt())) return;
        if (instance.conditionState() == AlarmInstance.ConditionState.PENDING)
            evaluatePending(rule, instance, input, generation);
        else evaluateActive(rule, instance, input, generation);
    }

    /** 首次满足触发条件创建新的 PENDING 事故代；并发创建由部分唯一索引仲裁。 */
    private void createPending(AlarmRule rule, AlarmEvaluationInput input, OptionalLong generation) {
        Instant now = input.receivedAt();
        AlarmInstance instance =
                new AlarmInstance(
                        Uuid7.generate(),
                        input.tenantId(),
                        input.projectId(),
                        rule.id(),
                        rule.originatorType(),
                        input.deviceId(),
                        rule.alarmType(),
                        rule.severity(),
                        AlarmInstance.ConditionState.PENDING,
                        AlarmInstance.AckState.UNACKNOWLEDGED,
                        null,
                        now,
                        null,
                        null,
                        null,
                        null,
                        null,
                        now,
                        input.occurredAt(),
                        input.value(),
                        0,
                        now,
                        now);
        if (instanceRepository.create(instance)) {
            append(instance, AlarmEvent.EventType.PENDING, input, null, generation);
            // 0 秒是首个命中即触发的契约，不能等待下一条遥测消息才变为 ACTIVE。
            if (rule.triggerDurationSeconds() == 0) activate(instance, input, generation);
        }
    }

    /** PENDING 既可能达到触发时长，也可能在阈值恢复后结束这一代无通知事故。 */
    private void evaluatePending(
            AlarmRule rule, AlarmInstance instance, AlarmEvaluationInput input, OptionalLong generation) {
        boolean triggered = rule.triggerOperator().matches(input.value(), rule.triggerThreshold());
        // PENDING 表示一段连续触发区间；一旦 trigger 为假，即使落在回差空档而 clear 仍为假，
        // 该候选代也已结束，避免下一次命中错误继承此前持续时长。
        if (!triggered) {
            clearPending(instance, input, generation);
            return;
        }
        Instant activateAt = instance.firstConditionAt().plusSeconds(rule.triggerDurationSeconds());
        if (input.receivedAt().isBefore(activateAt)) {
            touch(instance, input, null);
            return;
        }
        activate(instance, input, generation);
    }

    /** ACTIVE 只由显式恢复条件终止；触发条件仍成立时清除候选被重置，防止阈值抖动。 */
    private void evaluateActive(
            AlarmRule rule, AlarmInstance instance, AlarmEvaluationInput input, OptionalLong generation) {
        if (!rule.clearOperator().matches(input.value(), rule.clearThreshold())) {
            touch(instance, input, null);
            return;
        }
        evaluateRecovery(rule, instance, input, generation);
    }

    /** 将刚创建或仍保持触发的 PENDING 候选提升为 ACTIVE。 */
    private void activate(AlarmInstance instance, AlarmEvaluationInput input, OptionalLong generation) {
        AlarmInstance next =
                replace(
                        instance,
                        AlarmInstance.ConditionState.ACTIVE,
                        instance.ackState(),
                        null,
                        null,
                        null,
                        input,
                        input.receivedAt());
        if (instanceRepository.update(next)) {
            AlarmEvent event = append(next, AlarmEvent.EventType.ACTIVATED, input, null, generation);
            if (event != null && notificationDeliveryService != null)
                notificationDeliveryService.createForActivatedEvent(next, event);
        }
    }

    /** 触发连续性中断即结束 PENDING 候选；表约束保证其尚未被人工确认。 */
    private void clearPending(AlarmInstance instance, AlarmEvaluationInput input, OptionalLong generation) {
        AlarmInstance next =
                replace(
                        instance,
                        AlarmInstance.ConditionState.CLEARED,
                        instance.ackState(),
                        AlarmInstance.ClearReason.AUTO_RECOVERY,
                        null,
                        input.receivedAt(),
                        input,
                        instance.activatedAt());
        if (instanceRepository.update(next))
            append(
                    next,
                    AlarmEvent.EventType.CLEARED,
                    input,
                    AlarmInstance.ClearReason.AUTO_RECOVERY, generation);
    }

    /** 恢复条件连续满足 clearDuration 后 CLEARED，开始计时的值不产生事件。 */
    private void evaluateRecovery(
            AlarmRule rule, AlarmInstance instance, AlarmEvaluationInput input, OptionalLong generation) {
        if (!rule.clearOperator().matches(input.value(), rule.clearThreshold())) {
            touch(instance, input, null);
            return;
        }
        Instant recoveryAt =
                instance.recoveryConditionAt() == null
                        ? input.receivedAt()
                        : instance.recoveryConditionAt();
        if (input.receivedAt().isBefore(recoveryAt.plusSeconds(rule.clearDurationSeconds()))) {
            touch(instance, input, recoveryAt);
            return;
        }
        AlarmInstance next =
                replace(
                        instance,
                        AlarmInstance.ConditionState.CLEARED,
                        instance.ackState(),
                        AlarmInstance.ClearReason.AUTO_RECOVERY,
                        recoveryAt,
                        input.receivedAt(),
                        input,
                        instance.activatedAt());
        if (instanceRepository.update(next))
            append(
                    next,
                    AlarmEvent.EventType.CLEARED,
                    input,
                    AlarmInstance.ClearReason.AUTO_RECOVERY, generation);
    }

    /** 仅更新评估观测字段；这不是状态迁移，因此不追加事件。 */
    private void touch(AlarmInstance instance, AlarmEvaluationInput input, Instant recoveryAt) {
        AlarmInstance next =
                replace(
                        instance,
                        instance.conditionState(),
                        instance.ackState(),
                        instance.clearReason(),
                        recoveryAt,
                        instance.clearedAt(),
                        input,
                        instance.activatedAt());
        instanceRepository.update(next);
    }

    /** 保持不可变 record 的未变化字段，并携带原 version 供 repository CAS。 */
    private static AlarmInstance replace(
            AlarmInstance value,
            AlarmInstance.ConditionState condition,
            AlarmInstance.AckState ack,
            AlarmInstance.ClearReason reason,
            Instant recoveryAt,
            Instant clearedAt,
            AlarmEvaluationInput input,
            Instant activatedAt) {
        return new AlarmInstance(
                value.id(),
                value.tenantId(),
                value.projectId(),
                value.ruleId(),
                value.originatorType(),
                value.originatorId(),
                value.alarmType(),
                value.severity(),
                condition,
                ack,
                reason,
                value.firstConditionAt(),
                recoveryAt,
                activatedAt,
                clearedAt,
                value.acknowledgedAt(),
                value.acknowledgedBy(),
                input.receivedAt(),
                input.occurredAt(),
                input.value(),
                value.version(),
                value.createdAt(),
                input.receivedAt());
    }

    /** 追加自动评估迁移事件；同一 source message/type 的唯一索引吸收至少一次重投。 */
    private AlarmEvent append(
            AlarmInstance instance,
            AlarmEvent.EventType type,
            AlarmEvaluationInput input,
            AlarmInstance.ClearReason clearReason, OptionalLong generation) {
        AlarmEvent event =
                new AlarmEvent(
                        Uuid7.generate(),
                        instance.tenantId(),
                        instance.projectId(),
                        instance.id(),
                        type,
                        input.messageId(),
                        input.traceId(),
                        input.value(),
                        input.occurredAt(),
                        input.receivedAt(),
                        null,
                        instance.conditionState(),
                        instance.ackState(),
                        clearReason);
        if (instanceRepository.appendEvent(event)) {
            if (webhook != null) webhook.append(instance, event, generation);
            AlarmStateInvalidationPublisher.publish(eventPublisher, instance);
            metrics.recordTransition(type);
            return event;
        }
        return null;
    }
}
