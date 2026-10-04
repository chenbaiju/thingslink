package com.things.link.rule.application.outbox;

import com.things.link.alarm.application.RuleAlarmActionService;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.rule.application.queue.RuleExecutionException;
import com.things.link.rule.application.queue.RuleExecutionFailure;
import com.things.link.rule.application.engine.RuleSideEffectIntent;
import com.things.link.rule.application.queue.RuleExecutionEnvelope;
import com.things.link.rule.application.queue.RuleExecutionReceiptStore;
import com.things.link.telemetry.application.DeviceCommandService;
import com.things.link.support.outbox.TransactionalOutboxRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.List;
import java.util.Objects;

/**
 * 消息规则执行成功后的副作用收口：把信封中的受信身份冻结为 {@link RuleActionProvenance}，交给中性的
 * {@link RuleActionDispatcher} 派发动作意图，再在同一事务内推进 {@code rule_execution_receipt} 终态。
 *
 * <p>ADR 0030 把动作意图派发从回执推进中拆出：派发层不关心回执，手动场景复用同一派发层并推进自己的
 * {@code rule_scene_execution}，而本桥接只负责消息规则的「派发 + 回执终态」闭合。事务内绝不同步调用外部系统。</p>
 */
@Component
public class RuleSideEffectOutboxBridge {

    /** 中性动作意图派发层（告警/设备操作/通知 Outbox），不绑定任何执行回执。 */
    private final RuleActionDispatcher dispatcher;

    /** 持久幂等回执，与 Outbox 行同事务推进终态。 */
    private final RuleExecutionReceiptStore receiptStore;

    /** ADR0068：在动作与回执原事务第一步取得项目持续许可。 */
    private final ProjectLifecycleAccessService lifecycleAccessService;

    /**
     * @param dispatcher 中性动作意图派发层
     * @param receiptStore 消息规则执行回执
     * @param lifecycleAccessService 原动作事务的持续项目许可，测试构造也不得省略
     */
    @Autowired
    public RuleSideEffectOutboxBridge(
            RuleActionDispatcher dispatcher,
            RuleExecutionReceiptStore receiptStore,
            ProjectLifecycleAccessService lifecycleAccessService) {
        this.dispatcher = dispatcher;
        this.receiptStore = receiptStore;
        this.lifecycleAccessService = Objects.requireNonNull(lifecycleAccessService, "lifecycleAccessService");
    }

    /** S9-1 单元测试兼容构造；只允许通知意图，设备动作会因端口未装配而显式失败。 */
    public RuleSideEffectOutboxBridge(TransactionalOutboxRepository outboxRepository,
                                      RuleExecutionReceiptStore receiptStore,
                                      ObjectMapper objectMapper, Clock clock,
                                      ProjectLifecycleAccessService lifecycleAccessService) {
        this(new RuleActionDispatcher(outboxRepository, objectMapper, clock, null, null, null), receiptStore,
                lifecycleAccessService);
    }

    /** S9-2 设备动作单测兼容构造；告警意图会因端口未装配而显式失败。 */
    public RuleSideEffectOutboxBridge(
            TransactionalOutboxRepository outboxRepository,
            RuleExecutionReceiptStore receiptStore,
            ObjectMapper objectMapper,
            Clock clock,
            DeviceCommandService commandService,
            RuleDeviceActionDeliveryStore deliveryStore,
            ProjectLifecycleAccessService lifecycleAccessService) {
        this(new RuleActionDispatcher(outboxRepository, objectMapper, clock, commandService, deliveryStore, null),
                receiptStore, lifecycleAccessService);
    }

    /** S9-3 告警动作单测兼容构造；装配 alarm application 权威端口，设备动作端口可为空。 */
    public RuleSideEffectOutboxBridge(
            TransactionalOutboxRepository outboxRepository,
            RuleExecutionReceiptStore receiptStore,
            ObjectMapper objectMapper,
            Clock clock,
            DeviceCommandService commandService,
            RuleDeviceActionDeliveryStore deliveryStore,
            RuleAlarmActionService alarmActionService,
            ProjectLifecycleAccessService lifecycleAccessService) {
        this(new RuleActionDispatcher(outboxRepository, objectMapper, clock, commandService, deliveryStore, alarmActionService),
                receiptStore, lifecycleAccessService);
    }

    /**
     * 把全部副作用意图与回执终态放进同一事务。
     *
     * <p>{@code dispatcher.dispatch} 内的 {@code outboxRepository.append} 为 MANDATORY 传播，会加入本方法开启的
     * 事务；任一意图非法或序列化失败都会整体回滚，回执保持未完成，at-least-once 重投会重跑脚本并重发意图。</p>
     *
     * @param envelope 已成功执行的规则信封
     * @param intents 动作节点产出的副作用意图，可为空
     */
    @Transactional
    public void completeWithSideEffects(RuleExecutionEnvelope envelope, List<RuleSideEffectIntent> intents) {
        // ADR0068决策1：空动作也必须取得资格；SQL错误原样回滚，只有false属于确定安全拒绝。
        if (!lifecycleAccessService.lockActiveForWrite(envelope.tenantId(), envelope.key().projectId())) {
            throw new RuleExecutionException(RuleExecutionFailure.SECURITY_REJECTED, "项目拒绝新的规则动作");
        }
        RuleActionProvenance provenance = RuleActionProvenance.messageRule(
                envelope.tenantId(),
                envelope.key().projectId(),
                envelope.message().deviceId(),
                envelope.message().traceId(),
                envelope.message().occurredAt(),
                envelope.enqueuedAt(),
                envelope.key().messageId(),
                envelope.plan().steps().getFirst().createdBy(),
                envelope.key().ruleId(),
                envelope.key().ruleVersionId());
        dispatcher.dispatch(provenance, intents);
        receiptStore.complete(envelope);
    }
}
