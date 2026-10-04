package com.things.link.rule.application.outbox;

import com.things.link.alarm.application.RuleAlarmActionInput;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.rule.application.queue.RuleExecutionException;
import com.things.link.rule.application.RuleNotificationDeliveryStore;
import com.things.link.rule.application.queue.RuleExecutionFailure;
import com.things.link.alarm.application.RuleAlarmActionService;
import com.things.link.rule.application.engine.RuleMessage;
import com.things.link.rule.application.engine.RuleSideEffectIntent;
import com.things.link.rule.application.queue.RuleExecutionEnvelope;
import com.things.link.rule.application.queue.RuleExecutionKey;
import com.things.link.rule.application.queue.RuleExecutionReceiptClaim;
import com.things.link.rule.application.queue.RuleExecutionReceiptStore;
import com.things.link.shared.message.RuleNotificationDeliveryRequest;
import com.things.link.shared.message.DeviceCommandDispatch;
import com.things.link.telemetry.application.DeviceCommandService;
import com.things.link.telemetry.application.RuleDeviceActionRequest;
import com.things.link.telemetry.application.RuleDeviceActionResult;
import com.things.link.support.outbox.OutboxClaim;
import com.things.link.support.outbox.OutboxEvent;
import com.things.link.support.outbox.TransactionalOutboxRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** S9-1 副作用桥接单元测试：意图 → Outbox 事件映射、未知类型 fail-closed、空意图只收口。 */
class RuleSideEffectOutboxBridgeTests {

    /** 与生产相同的 JSON 映射器。 */
    private final ObjectMapper objectMapper = new ObjectMapper();
    /** 固定 UTC 时钟，断言可投递时刻时不受真实墙钟影响。 */
    private final Clock clock = Clock.fixed(Instant.parse("2026-08-13T06:00:00Z"), ZoneOffset.UTC);

    /** 所有兼容构造都显式提供项目许可；该替身只验证编排，真实事务在PG验收中证明。 */
    private ProjectLifecycleAccessService lifecycleAccessService;

    /** 正常映射用例显式允许许可，拒绝和故障用例独立覆盖对应返回。 */
    @BeforeEach
    void setUpAdmission() {
        lifecycleAccessService = mock(ProjectLifecycleAccessService.class);
        when(lifecycleAccessService.lockActiveForWrite(any(), any())).thenReturn(true);
    }

    /** 通知意图映射为一条可反序列化回冻结契约的 Outbox 事件，并与回执终态同事务收口。 */
    @Test
    void notificationIntentAppendsDeliveryRequestAndCompletesReceipt() {
        RecordingOutboxRepository outbox = new RecordingOutboxRepository();
        RecordingReceiptStore receipts = new RecordingReceiptStore();
        RuleSideEffectOutboxBridge bridge = new RuleSideEffectOutboxBridge(outbox, receipts, objectMapper, clock, lifecycleAccessService);
        Fixture fixture = fixture();
        ObjectNode payload = objectMapper.createObjectNode()
                .put("channel", "email")
                .put("recipient", "ops@example.com")
                .put("subject", "device offline")
                .put("body", "device 123 offline");

        bridge.completeWithSideEffects(fixture.envelope(),
                List.of(new RuleSideEffectIntent("notification", payload)));

        assertThat(receipts.completed).isEqualTo(1);
        assertThat(outbox.appended).hasSize(1);
        OutboxEvent event = outbox.appended.get(0);
        assertThat(event.eventType()).isEqualTo(RuleNotificationDeliveryRequest.EVENT_TYPE);
        assertThat(event.aggregateType()).isEqualTo("RULE_NOTIFICATION");
        assertThat(event.partitionKey()).isEqualTo(event.id().toString());
        assertThat(event.tenantId()).isEqualTo(fixture.tenant());
        assertThat(event.projectId()).isEqualTo(fixture.key().projectId());
        assertThat(event.traceId()).isEqualTo(fixture.message().traceId());
        assertThat(event.availableAt()).isEqualTo(clock.instant());

        RuleNotificationDeliveryRequest request =
                objectMapper.readValue(event.payload(), RuleNotificationDeliveryRequest.class);
        assertThat(request.eventId()).isEqualTo(event.id());
        assertThat(request.ruleId()).isEqualTo(fixture.key().ruleId());
        assertThat(request.ruleVersionId()).isEqualTo(fixture.key().ruleVersionId());
        assertThat(request.messageId()).isEqualTo(fixture.key().messageId());
        assertThat(request.deviceId()).isEqualTo(fixture.message().deviceId());
        assertThat(request.channel()).isEqualTo("email");
        assertThat(request.recipient()).isEqualTo("ops@example.com");
        assertThat(request.subject()).isEqualTo("device offline");
        assertThat(request.body()).isEqualTo("device 123 offline");
        assertThat(request.traceId()).isEqualTo(fixture.message().traceId());
    }

    /** 自动化来源必须经过实际派发器进入JSON；重放稳定且动作位置互不吞并。 */
    @Test
    void automationDispatchPreservesSourceAndDeterministicIds() {
        var outbox = new RecordingOutboxRepository();
        var notifications = mock(RuleNotificationDeliveryStore.class);
        when(notifications.accept(any())).thenReturn(RuleNotificationDeliveryStore.Acceptance.READY);
        var dispatcher = new RuleActionDispatcher(outbox, objectMapper, clock, null, null, null, notifications);
        UUID execution = UUID.randomUUID();
        var provenance = RuleActionProvenance.automation(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "auto-trace", clock.instant().minusSeconds(60), clock.instant().minusSeconds(30), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), execution);
        var intent = new RuleSideEffectIntent("notification", objectMapper.createObjectNode()
                .put("channel", "email").put("recipient", "ops@example.com").put("subject", "auto").put("body", "body"));
        dispatcher.dispatch(provenance, List.of(intent, intent));
        var first = outbox.appended.getFirst(); var second = outbox.appended.getLast();
        var request = objectMapper.readValue(first.payload(), RuleNotificationDeliveryRequest.class);
        verify(notifications).accept(request);
        assertThat(request.validSource()).isTrue();
        assertThat(request.automationExecutionId()).isEqualTo(execution);
        assertThat(request.automationVersionId()).isEqualTo(provenance.automationVersionId());
        assertThat(request.ruleId()).isNull(); assertThat(request.sceneId()).isNull();
        assertThat(first.id()).isNotEqualTo(second.id());
        when(notifications.accept(any())).thenReturn(RuleNotificationDeliveryStore.Acceptance.IDEMPOTENT_REPLAY);
        outbox.appended.clear(); dispatcher.dispatch(provenance, List.of(intent, intent));
        assertThat(outbox.appended.getFirst().id()).isEqualTo(first.id());
        assertThat(outbox.appended.getLast().id()).isEqualTo(second.id());
    }

    /** ADR0157：受理拒绝或数据库故障不能产生未受理的通知Outbox。 */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void automationAdmissionFailureDoesNotAppendOutbox(boolean databaseFailure) {
        var outbox = new RecordingOutboxRepository();
        var notifications = mock(RuleNotificationDeliveryStore.class);
        var unavailable = new org.springframework.dao.DataAccessResourceFailureException("受理数据库不可用");
        if (databaseFailure) when(notifications.accept(any())).thenThrow(unavailable);
        else when(notifications.accept(any())).thenReturn(RuleNotificationDeliveryStore.Acceptance.REJECTED);
        var dispatcher = new RuleActionDispatcher(outbox, objectMapper, clock, null, null, null, notifications);
        var provenance = RuleActionProvenance.automation(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "auto-trace", clock.instant(), clock.instant(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID());
        var intent = new RuleSideEffectIntent("notification", objectMapper.createObjectNode()
                .put("channel", "email").put("recipient", "ops@example.com").put("subject", "auto").put("body", "body"));
        var failure = assertThatThrownBy(() -> dispatcher.dispatch(provenance, List.of(intent)));
        if (databaseFailure) failure.isSameAs(unavailable);
        else failure.isInstanceOf(AutomationActionRejectedException.class);
        assertThat(outbox.appended).isEmpty();
        verify(notifications).accept(any());
    }

    /** 旧JSON省略新增字段仍可读；残缺第二来源不得混入完整来源。 */
    @Test
    void notificationSourceCompatibilityAndExclusivity() {
        var legacy = RuleNotificationDeliveryRequest.rule(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),
                UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"EMAIL","ops@example.com",
                "subject","body","trace",1,clock.instant());
        var tree = (ObjectNode)objectMapper.valueToTree(legacy);
        tree.remove("automationId"); tree.remove("automationVersionId"); tree.remove("automationExecutionId");
        var restored=objectMapper.treeToValue(tree,RuleNotificationDeliveryRequest.class);
        assertThat(restored).isEqualTo(legacy); assertThat(restored.validSource()).isTrue();
        tree.put("automationId",UUID.randomUUID().toString());
        assertThat(objectMapper.treeToValue(tree,RuleNotificationDeliveryRequest.class).validSource()).isFalse();
        tree.remove("automationId"); tree.put("sceneExecutionId",UUID.randomUUID().toString());
        assertThat(objectMapper.treeToValue(tree,RuleNotificationDeliveryRequest.class).validSource()).isFalse();
    }

    /** 相同规则消息重放产生相同投递 ID，而同一版本中的两个通知位置保持不同。 */
    @Test
    void usesDeterministicDeliveryIdPerActionPosition() {
        RecordingOutboxRepository outbox = new RecordingOutboxRepository();
        RecordingReceiptStore receipts = new RecordingReceiptStore();
        RuleExecutionEnvelope envelope = fixture().envelope();
        ObjectNode first = objectMapper.createObjectNode()
                .put("channel", "email").put("recipient", "first@example.com");
        ObjectNode second = objectMapper.createObjectNode()
                .put("channel", "email").put("recipient", "second@example.com");

        new RuleSideEffectOutboxBridge(outbox, receipts, objectMapper, clock, lifecycleAccessService)
                .completeWithSideEffects(envelope, List.of(
                        new RuleSideEffectIntent("notification", first),
                        new RuleSideEffectIntent("notification", second)));
        UUID firstRun = outbox.appended.get(0).id();
        UUID secondPosition = outbox.appended.get(1).id();
        outbox.appended.clear();

        new RuleSideEffectOutboxBridge(outbox, receipts, objectMapper, clock, lifecycleAccessService)
                .completeWithSideEffects(envelope, List.of(
                        new RuleSideEffectIntent("notification", first),
                        new RuleSideEffectIntent("notification", second)));

        assertThat(outbox.appended.get(0).id()).isEqualTo(firstRun);
        assertThat(secondPosition).isNotEqualTo(firstRun);
    }

    /** 动作节点未提供的可选字段按空串落入契约，不因缺失而中断收口。 */
    @Test
    void absentSubjectAndBodyAreMappedToEmptyText() {
        RecordingOutboxRepository outbox = new RecordingOutboxRepository();
        RecordingReceiptStore receipts = new RecordingReceiptStore();
        RuleSideEffectOutboxBridge bridge = new RuleSideEffectOutboxBridge(outbox, receipts, objectMapper, clock, lifecycleAccessService);
        Fixture fixture = fixture();
        ObjectNode payload = objectMapper.createObjectNode()
                .put("channel", "email")
                .put("recipient", "ops@example.com");

        bridge.completeWithSideEffects(fixture.envelope(),
                List.of(new RuleSideEffectIntent("notification", payload)));

        RuleNotificationDeliveryRequest request =
                objectMapper.readValue(outbox.appended.get(0).payload(), RuleNotificationDeliveryRequest.class);
        assertThat(request.subject()).isEmpty();
        assertThat(request.body()).isEmpty();
        assertThat(receipts.completed).isEqualTo(1);
    }

    /** 无副作用的规则只推进回执终态，不产生任何 Outbox 行。 */
    @Test
    void emptyIntentsOnlyCompleteReceipt() {
        RecordingOutboxRepository outbox = new RecordingOutboxRepository();
        RecordingReceiptStore receipts = new RecordingReceiptStore();
        RuleSideEffectOutboxBridge bridge = new RuleSideEffectOutboxBridge(outbox, receipts, objectMapper, clock, lifecycleAccessService);

        bridge.completeWithSideEffects(fixture().envelope(), List.of());

        assertThat(receipts.completed).isEqualTo(1);
        assertThat(outbox.appended).isEmpty();
    }

    /** 多条通知意图逐条追加 Outbox 行，但仍只推进一次回执终态。 */
    @Test
    void multipleIntentsAppendOneEventEachAndSingleReceiptCompletion() {
        RecordingOutboxRepository outbox = new RecordingOutboxRepository();
        RecordingReceiptStore receipts = new RecordingReceiptStore();
        RuleSideEffectOutboxBridge bridge = new RuleSideEffectOutboxBridge(outbox, receipts, objectMapper, clock, lifecycleAccessService);
        Fixture fixture = fixture();
        ObjectNode first = objectMapper.createObjectNode().put("channel", "email").put("recipient", "a@example.com");
        ObjectNode second = objectMapper.createObjectNode().put("channel", "webhook").put("recipient", "b@example.com");

        bridge.completeWithSideEffects(fixture.envelope(), List.of(
                new RuleSideEffectIntent("notification", first),
                new RuleSideEffectIntent("notification", second)));

        assertThat(outbox.appended).hasSize(2);
        assertThat(receipts.completed).isEqualTo(1);
    }

    /** 设备属性设置交给 telemetry 公开端口受理，并把稳定 commandId 记录为规则动作关联事实。 */
    @Test
    void propertySetIntentUsesDeviceCommandPortAndRecordsAcceptedDelivery() {
        RecordingReceiptStore receipts = new RecordingReceiptStore();
        DeviceCommandService commandService = mock(DeviceCommandService.class);
        RuleDeviceActionDeliveryStore deliveryStore = mock(RuleDeviceActionDeliveryStore.class);
        UUID commandId = UUID.randomUUID();
        when(commandService.submitRuleAction(any())).thenReturn(new RuleDeviceActionResult(commandId, true, null));
        RuleSideEffectOutboxBridge bridge = new RuleSideEffectOutboxBridge(
                new RecordingOutboxRepository(), receipts, objectMapper, clock, commandService, deliveryStore, lifecycleAccessService);
        Fixture fixture = fixture();
        ObjectNode properties = objectMapper.createObjectNode().put("enabled", true);
        ObjectNode payload = objectMapper.createObjectNode().set("properties", properties);

        bridge.completeWithSideEffects(fixture.envelope(),
                List.of(new RuleSideEffectIntent("device-property-set", payload)));

        verify(commandService).submitRuleAction(any(RuleDeviceActionRequest.class));
        verify(deliveryStore).record(any(), any(), any(), any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.eq(DeviceCommandDispatch.OperationType.PROPERTY_SET),
                org.mockito.ArgumentMatchers.eq(commandId), org.mockito.ArgumentMatchers.eq("ACCEPTED"),
                org.mockito.ArgumentMatchers.isNull(), any(), any());
        assertThat(receipts.completed).isEqualTo(1);
    }

    /** 告警创建/清除只进入 alarm application 权威端口，且使用消息与信封中的可信身份。 */
    @Test
    void alarmIntentsUseTrustedAlarmApplicationPort() {
        RecordingReceiptStore receipts = new RecordingReceiptStore();
        RuleAlarmActionService alarmService = mock(RuleAlarmActionService.class);
        RuleSideEffectOutboxBridge bridge = new RuleSideEffectOutboxBridge(
                new RecordingOutboxRepository(), receipts, objectMapper, clock, null, null, alarmService, lifecycleAccessService);
        Fixture fixture = fixture();
        UUID alarmRuleId = UUID.randomUUID();
        ObjectNode payload = objectMapper.createObjectNode().put("alarmRuleId", alarmRuleId.toString());

        bridge.completeWithSideEffects(fixture.envelope(), List.of(
                new RuleSideEffectIntent("alarm-create", payload),
                new RuleSideEffectIntent("alarm-clear", payload)));

        org.mockito.ArgumentCaptor<RuleAlarmActionInput> createInput =
                org.mockito.ArgumentCaptor.forClass(RuleAlarmActionInput.class);
        org.mockito.ArgumentCaptor<RuleAlarmActionInput> clearInput =
                org.mockito.ArgumentCaptor.forClass(RuleAlarmActionInput.class);
        verify(alarmService).create(createInput.capture());
        verify(alarmService).clear(clearInput.capture());
        assertThat(createInput.getValue().messageId()).isEqualTo(fixture.key().messageId());
        assertThat(createInput.getValue().tenantId()).isEqualTo(fixture.tenant());
        assertThat(createInput.getValue().projectId()).isEqualTo(fixture.key().projectId());
        assertThat(createInput.getValue().alarmRuleId()).isEqualTo(alarmRuleId);
        assertThat(createInput.getValue().deviceId()).isEqualTo(fixture.message().deviceId());
        assertThat(clearInput.getValue()).isEqualTo(createInput.getValue());
        assertThat(receipts.completed).isEqualTo(1);
    }

    /** 未冻结的意图类型在提交前 fail-closed：不追加 Outbox、不推进回执，交由事务整体回滚。 */
    @Test
    void unknownIntentTypeFailsClosed() {
        RecordingOutboxRepository outbox = new RecordingOutboxRepository();
        RecordingReceiptStore receipts = new RecordingReceiptStore();
        RuleSideEffectOutboxBridge bridge = new RuleSideEffectOutboxBridge(outbox, receipts, objectMapper, clock, lifecycleAccessService);

        assertThatThrownBy(() -> bridge.completeWithSideEffects(fixture().envelope(),
                List.of(new RuleSideEffectIntent("unregistered-action", objectMapper.createObjectNode()))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不支持的规则副作用意图类型");

        assertThat(outbox.appended).isEmpty();
        assertThat(receipts.completed).isZero();
    }

    /** 冻结许可拒绝包括空动作，不能以无副作用为由推进成功receipt。 */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deniedAdmissionPreventsDispatchAndReceiptEvenWithoutActions(boolean empty) {
        RuleActionDispatcher dispatcher = mock(RuleActionDispatcher.class);
        RuleExecutionReceiptStore receipts = mock(RuleExecutionReceiptStore.class);
        RuleExecutionEnvelope envelope = fixture().envelope();
        when(lifecycleAccessService.lockActiveForWrite(envelope.tenantId(), envelope.key().projectId())).thenReturn(false);
        RuleSideEffectOutboxBridge bridge = new RuleSideEffectOutboxBridge(dispatcher, receipts, lifecycleAccessService);
        List<RuleSideEffectIntent> intents = empty ? List.of()
                : List.of(new RuleSideEffectIntent("notification", objectMapper.createObjectNode()));
        assertThatThrownBy(() -> bridge.completeWithSideEffects(envelope, intents))
                .isInstanceOfSatisfying(RuleExecutionException.class, failure ->
                        assertThat(failure.failure()).isEqualTo(RuleExecutionFailure.SECURITY_REJECTED));
        verifyNoInteractions(dispatcher, receipts);
    }

    /** SQL异常不被伪装成安全拒绝，许可成功必须先于动作及receipt。 */
    @Test
    void permitFailurePropagatesWithoutDispatching() {
        RuleActionDispatcher dispatcher = mock(RuleActionDispatcher.class);
        RuleExecutionReceiptStore receipts = mock(RuleExecutionReceiptStore.class);
        RuleExecutionEnvelope envelope = fixture().envelope();
        RuntimeException failure = new org.springframework.dao.QueryTimeoutException("project permit timeout");
        when(lifecycleAccessService.lockActiveForWrite(envelope.tenantId(), envelope.key().projectId())).thenThrow(failure);
        RuleSideEffectOutboxBridge bridge = new RuleSideEffectOutboxBridge(dispatcher, receipts, lifecycleAccessService);
        assertThatThrownBy(() -> bridge.completeWithSideEffects(envelope, List.of())).isSameAs(failure);
        verifyNoInteractions(dispatcher, receipts);
    }

    /** 许可先于任何动作，成功回执严格位于全部派发之后。 */
    @Test
    void admittedBridgeUsesEnvelopeIdentityBeforeDispatchAndCompletion() {
        RuleActionDispatcher dispatcher = mock(RuleActionDispatcher.class);
        RuleExecutionReceiptStore receipts = mock(RuleExecutionReceiptStore.class);
        RuleExecutionEnvelope envelope = fixture().envelope();
        new RuleSideEffectOutboxBridge(dispatcher, receipts, lifecycleAccessService)
                .completeWithSideEffects(envelope, List.of());
        var order = inOrder(lifecycleAccessService, dispatcher, receipts);
        order.verify(lifecycleAccessService).lockActiveForWrite(envelope.tenantId(), envelope.key().projectId());
        order.verify(dispatcher).dispatch(any(), org.mockito.ArgumentMatchers.eq(List.of()));
        order.verify(receipts).complete(envelope);
    }

    /** 当前用例独占的租户、身份与消息快照。 */
    private Fixture fixture() {
        UUID tenant = UUID.randomUUID();
        UUID project = UUID.randomUUID();
        UUID message = UUID.randomUUID();
        UUID device = UUID.randomUUID();
        RuleExecutionKey key = new RuleExecutionKey(project, message, UUID.randomUUID(), UUID.randomUUID());
        RuleMessage ruleMessage = new RuleMessage(message, tenant, project, device, "trace-123",
                Instant.parse("2026-08-13T05:59:00Z"), "PROPERTY", objectMapper.createObjectNode(), Map.of());
        return new Fixture(tenant, key, ruleMessage,
                new RuleExecutionEnvelope(key, tenant, ruleMessage, 1, Instant.parse("2026-08-13T06:00:00Z")));
    }

    /** 捕获 append 的内存 Outbox 端口；不关心的领取/确认路径返回空。 */
    private static final class RecordingOutboxRepository implements TransactionalOutboxRepository {
        /** 已追加的事件。 */
        private final List<OutboxEvent> appended = new ArrayList<>();
        /** {@inheritDoc} */
        @Override public void append(OutboxEvent event) { appended.add(event); }
        /** {@inheritDoc} */
        @Override public OutboxClaim claimReady(int maximumEvents, Duration leaseDuration) { return null; }
        /** {@inheritDoc} */
        @Override public boolean markPublished(UUID eventId, UUID leaseToken) { return false; }
        /** {@inheritDoc} */
        @Override public boolean markRetry(UUID eventId, UUID leaseToken, Instant nextAvailableAt, String failureMessage) {
            return false;
        }
    }

    /** 记录 complete 次数的内存回执端口。 */
    private static final class RecordingReceiptStore implements RuleExecutionReceiptStore {
        /** complete 次数。 */
        private int completed;
        /** {@inheritDoc} */
        @Override public RuleExecutionReceiptClaim tryClaim(RuleExecutionEnvelope envelope) {
            return RuleExecutionReceiptClaim.ACQUIRED;
        }
        /** {@inheritDoc} */
        @Override public void complete(RuleExecutionEnvelope envelope) { completed++; }
        /** {@inheritDoc} */
        @Override public void release(RuleExecutionEnvelope envelope) { }
    }

    /** 桥接断言所需的最小信封快照。 */
    private record Fixture(UUID tenant, RuleExecutionKey key, RuleMessage message, RuleExecutionEnvelope envelope) {
    }
}
