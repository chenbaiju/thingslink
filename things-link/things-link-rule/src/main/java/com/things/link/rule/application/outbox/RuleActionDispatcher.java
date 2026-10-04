package com.things.link.rule.application.outbox;

import com.things.link.alarm.application.RuleAlarmActionInput;
import com.things.link.alarm.application.RuleAlarmActionService;
import com.things.link.rule.application.engine.RuleSideEffectIntent;
import com.things.link.shared.message.RuleNotificationDeliveryRequest;
import com.things.link.shared.message.DeviceCommandDispatch;
import com.things.link.telemetry.application.DeviceCommandService;
import com.things.link.telemetry.application.RuleDeviceActionRequest;
import com.things.link.telemetry.application.RuleDeviceActionResult;
import com.things.link.support.outbox.OutboxEvent;
import com.things.link.support.outbox.TransactionalOutboxRepository;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 中性动作意图派发层：把动作节点产出的 {@link RuleSideEffectIntent} 映射为告警/设备操作/通知的 Outbox 与
 * 动作关联事实，不绑定任何执行回执。ADR 0030 要求消息规则与手动场景共享同一套派发能力，各自在派发后推进
 * 自己的执行事实（{@code rule_execution_receipt} 或 {@code rule_scene_execution}）。
 *
 * <p>本层绝不同步调用外部系统；通知 Outbox 由 support 发布器异步投递，设备操作由 telemetry 端口追加下行 Outbox，
 * 告警由 S6 状态机追加自身 Outbox。未知意图类型 fail-closed，防止通用 Outbox 变成任意对象入口。</p>
 */
@Component
public class RuleActionDispatcher {

    /** S9-1～S9-3 已冻结的副作用白名单；新增类型必须同步节点、派发与可靠投递证据。 */
    private static final Set<String> SUPPORTED_INTENTS =
            Set.of("notification", "device-command", "device-property-set", "alarm-create", "alarm-clear");

    /** 通知投递事实的聚合类型。 */
    private static final String AGGREGATE_TYPE = "RULE_NOTIFICATION";

    /** 跨领域可靠 Outbox 端口。 */
    private final TransactionalOutboxRepository outboxRepository;
    /** 意图载荷到冻结消息契约的序列化器。 */
    private final ObjectMapper objectMapper;
    /** 可测试的 UTC 时钟，固定 Outbox 可投递时刻。 */
    private final Clock clock;
    /** telemetry application 公开的可信设备操作端口；不读取 telemetry/device 表。 */
    private final DeviceCommandService commandService;
    /** rule 自有动作投递事实。 */
    private final RuleDeviceActionDeliveryStore deliveryStore;
    /** alarm application 公开的可信状态机端口；rule 不读取 alarm_ 表。 */
    private final RuleAlarmActionService alarmActionService;
    private final com.things.link.rule.application.RuleNotificationDeliveryStore notifications;

    /**
     * @param outboxRepository 事务 Outbox 端口
     * @param objectMapper JSON 映射器
     * @param clock UTC 时钟
     * @param commandService 设备操作端口
     * @param deliveryStore 设备动作投递事实
     * @param alarmActionService 告警动作端口
     */
    /** 兼容旧规则/场景测试与桥接构造；自动化必须使用完整装配。 */
    public RuleActionDispatcher(TransactionalOutboxRepository outboxRepository,ObjectMapper objectMapper,Clock clock,
            DeviceCommandService commandService,RuleDeviceActionDeliveryStore deliveryStore,RuleAlarmActionService alarmActionService){
        this(outboxRepository,objectMapper,clock,commandService,deliveryStore,alarmActionService,null);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public RuleActionDispatcher(TransactionalOutboxRepository outboxRepository,ObjectMapper objectMapper,Clock clock,
            DeviceCommandService commandService,RuleDeviceActionDeliveryStore deliveryStore,RuleAlarmActionService alarmActionService,
            com.things.link.rule.application.RuleNotificationDeliveryStore notifications) {
        this.notifications=notifications;
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.commandService = commandService;
        this.deliveryStore = deliveryStore;
        this.alarmActionService = alarmActionService;
    }

    /**
     * 在调用方已开启的事务内把全部副作用意图映射为 Outbox 与动作关联事实。
     *
     * <p>{@code outboxRepository.append} 为 MANDATORY 传播，必须由上层事务包裹；任一意图非法或序列化失败
     * 都会整体回滚，上层（消息规则回执或场景执行事实）也一并回滚。</p>
     *
     * @param provenance 受信执行身份与来源归属
     * @param intents 动作节点产出的副作用意图，可为空
     */
    public void dispatch(RuleActionProvenance provenance, List<RuleSideEffectIntent> intents) {
        for (int position = 0; position < intents.size(); position++) {
            RuleSideEffectIntent intent = intents.get(position);
            String type = intent.type();
            if (!SUPPORTED_INTENTS.contains(type)) {
                throw new IllegalArgumentException("不支持的规则副作用意图类型: " + type);
            }
            if ("notification".equals(type)) {
                outboxRepository.append(toNotificationOutboxEvent(provenance, intent, position));
            } else if ("alarm-create".equals(type) || "alarm-clear".equals(type)) {
                submitAlarmAction(provenance, intent);
            } else {
                submitDeviceAction(provenance, intent, position);
            }
        }
    }

    /**
     * 交给 S6 权威状态机创建或清除告警；该内部数据库副作用加入当前事务，激活通知仍由 S6 Outbox 异步外发。
     */
    private void submitAlarmAction(RuleActionProvenance provenance, RuleSideEffectIntent intent) {
        if (alarmActionService == null) {
            throw new IllegalStateException("告警动作端口未装配");
        }
        RuleAlarmActionInput input = new RuleAlarmActionInput(
                provenance.messageId(), provenance.tenantId(), provenance.projectId(),
                UUID.fromString(text(intent.payload(), "alarmRuleId")), provenance.deviceId(),
                provenance.occurredAt(), provenance.receivedAt(), provenance.traceId());
        try {
            if ("alarm-create".equals(intent.type())) {
                alarmActionService.create(input);
            } else {
                alarmActionService.clear(input);
            }
        } catch (com.things.link.shared.error.BusinessException rejected) {
            if (provenance.automationId() != null) throw new AutomationActionRejectedException();
            throw rejected;
        }
    }

    /** 通知意图映射为通知投递 Outbox 事件；来源组与意图类型都已在调用处白名单校验。 */
    private OutboxEvent toNotificationOutboxEvent(
            RuleActionProvenance provenance, RuleSideEffectIntent intent, int position) {
        // S9-3 / D-024：消息重放必须得到同一投递 ID；位置不可省略，否则同版本的两个同类动作会互相吞并。
        UUID eventId = deterministicActionId(provenance, intent.type(), position);
        java.time.Instant enqueuedAt = clock.instant();
        RuleNotificationDeliveryRequest request = provenance.isMessageRule()
                ? RuleNotificationDeliveryRequest.rule(
                        eventId, provenance.tenantId(), provenance.projectId(), provenance.ruleId(),
                        provenance.ruleVersionId(), provenance.messageId(), provenance.deviceId(),
                        text(intent.payload(), "channel"), text(intent.payload(), "recipient"),
                        text(intent.payload(), "subject"), text(intent.payload(), "body"),
                        provenance.traceId(), 1, enqueuedAt)
                : provenance.automationId() != null ? RuleNotificationDeliveryRequest.automation(
                        eventId, provenance.tenantId(), provenance.projectId(), provenance.automationId(),
                        provenance.automationVersionId(), provenance.automationExecutionId(), provenance.deviceId(),
                        text(intent.payload(), "channel"), text(intent.payload(), "recipient"),
                        text(intent.payload(), "subject"), text(intent.payload(), "body"),
                        provenance.traceId(), 1, enqueuedAt)
                : RuleNotificationDeliveryRequest.scene(
                        eventId, provenance.tenantId(), provenance.projectId(), provenance.sceneId(),
                        provenance.sceneVersionId(), provenance.sceneExecutionId(), provenance.deviceId(),
                        text(intent.payload(), "channel"), text(intent.payload(), "recipient"),
                        text(intent.payload(), "subject"), text(intent.payload(), "body"),
                        provenance.traceId(), 1, enqueuedAt);
        if(provenance.automationId()!=null){
            if(notifications==null)throw new IllegalStateException("自动化通知受理端口未装配");
            if(notifications.accept(request)==com.things.link.rule.application.RuleNotificationDeliveryStore.Acceptance.REJECTED)
                throw new AutomationActionRejectedException();
        }
        return new OutboxEvent(
                eventId,
                provenance.tenantId(),
                provenance.projectId(),
                AGGREGATE_TYPE,
                eventId,
                RuleNotificationDeliveryRequest.EVENT_TYPE,
                eventId.toString(),
                objectMapper.writeValueAsString(request),
                provenance.traceId(),
                enqueuedAt);
    }

    /**
     * 根据冻结执行身份和动作位置生成稳定 ID；项目轴一并进入名称，避免跨项目相同 UUID 组合发生碰撞。
     * 规则版本 ID 与场景版本 ID 都是全局唯一 UUID，来源切换不会产生碰撞。
     */
    private static UUID deterministicActionId(
            RuleActionProvenance provenance, String intentType, int position) {
        UUID versionId = provenance.isMessageRule() ? provenance.ruleVersionId()
                : provenance.automationId() != null ? provenance.automationVersionId() : provenance.sceneVersionId();
        String name = provenance.projectId() + ":" + versionId + ":"
                + provenance.messageId() + ":" + intentType + ":" + position;
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
    }

    /** 规则/场景设备命令与属性设置事实加入当前事务；DeviceCommandService 自己追加 tc.device.downlink Outbox。 */
    private void submitDeviceAction(RuleActionProvenance provenance, RuleSideEffectIntent intent, int position) {
        if (commandService == null || deliveryStore == null) {
            throw new IllegalStateException("设备动作端口未装配");
        }
        UUID actionId = deterministicActionId(provenance, intent.type(), position);
        DeviceCommandDispatch.OperationType operationType = "device-command".equals(intent.type())
                ? DeviceCommandDispatch.OperationType.COMMAND : DeviceCommandDispatch.OperationType.PROPERTY_SET;
        JsonNode input = operationType == DeviceCommandDispatch.OperationType.COMMAND
                ? intent.payload().path("input") : intent.payload().path("properties");
        RuleDeviceActionResult result = commandService.submitRuleAction(new RuleDeviceActionRequest(
                actionId, provenance.projectId(), provenance.requestedBy(), provenance.deviceId(), operationType,
                operationType == DeviceCommandDispatch.OperationType.COMMAND
                        ? text(intent.payload(), "commandKey") : null,
                input));
        if (provenance.automationId() != null && !result.accepted()) {
            // ADR0155：让整个自动化动作事务回滚，失败事实由租约运行器另行落库。
            throw new AutomationActionRejectedException();
        }
        String status = result.accepted() ? "ACCEPTED" : "REJECTED";
        if (provenance.isMessageRule()) {
            deliveryStore.record(actionId, provenance.tenantId(), provenance.projectId(), provenance.ruleId(),
                    provenance.ruleVersionId(), provenance.messageId(), provenance.deviceId(), operationType,
                    result.commandId(), status, result.failureCode(), provenance.traceId(), clock.instant());
        } else if (provenance.automationId() != null) {
            deliveryStore.recordAutomation(actionId, provenance.tenantId(), provenance.projectId(), provenance.automationId(),
                    provenance.automationVersionId(), provenance.automationExecutionId(), provenance.deviceId(), operationType,
                    result.commandId(), status, result.failureCode(), provenance.traceId(), clock.instant());
        } else {
            deliveryStore.recordScene(actionId, provenance.tenantId(), provenance.projectId(), provenance.sceneId(),
                    provenance.sceneVersionId(), provenance.sceneExecutionId(), provenance.deviceId(), operationType,
                    result.commandId(), status, result.failureCode(), provenance.traceId(), clock.instant());
        }
    }

    /** 读取动作节点已渲染的文本字段；动作节点未提供的可选字段按空串处理。 */
    private static String text(JsonNode payload, String key) {
        JsonNode value = payload.get(key);
        return value == null ? "" : value.asText("");
    }
}
