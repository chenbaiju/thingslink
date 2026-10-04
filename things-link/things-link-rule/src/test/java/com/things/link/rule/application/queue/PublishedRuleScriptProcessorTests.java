package com.things.link.rule.application.queue;

import com.things.link.rule.application.ScriptExecutionResult;
import com.things.link.rule.application.ScriptExecutionStatus;
import com.things.link.rule.application.ScriptSandbox;
import com.things.link.rule.application.ScriptValidationResult;
import com.things.link.rule.application.engine.DeterministicRuleEngine;
import com.things.link.rule.application.engine.RuleMessage;
import com.things.link.rule.application.engine.RuleSideEffectIntent;
import com.things.link.rule.application.engine.node.NotificationActionNode;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** S8-2C 生产脚本处理器验证串行组合、可信身份、失败分类与 S9-1 动作意图产出。 */
class PublishedRuleScriptProcessorTests {

    /** 固定时钟保证动作执行上下文 startedAt 稳定可断言。 */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-13T06:00:00Z"), ZoneOffset.UTC);

    /** 历史宽校验留下的非动作节点不能在生产冻结计划中获得副作用资格。 */
    @Test void rejectsRegisteredConditionUsedAsAction() {
        ObjectMapper mapper = new ObjectMapper();
        var processor = new PublishedRuleScriptProcessor(sandbox(request -> ScriptExecutionResult.success("{}", Duration.ZERO)),
                mapper, new DeterministicRuleEngine(List.of(new com.things.link.rule.application.engine.node.PayloadPropertyCompareNode())), CLOCK);
        var action = new PublishedRuleAction("payload-property-compare", mapper.readTree("{\"pointer\":\"/a\",\"operator\":\"EXISTS\"}"));
        var plan = envelope(mapper, List.of(new PublishedRuleStep(UUID.randomUUID(), UUID.randomUUID(), 1, "input => input", List.of(action))));
        assertThatThrownBy(() -> processor.process(plan)).isInstanceOfSatisfying(RuleExecutionException.class,
                failure -> assertThat(failure.failure()).isEqualTo(RuleExecutionFailure.CONTRACT_INVALID));
    }

    /** 多个 ACTIVE 版本必须按冻结计划串行组合且只变换 payload。 */
    @Test
    void executesFrozenPlanInOrderAndPreservesIdentity() {
        ObjectMapper mapper = new ObjectMapper();
        AtomicInteger invocation = new AtomicInteger();
        PublishedRuleScriptProcessor processor = new PublishedRuleScriptProcessor(sandbox(request -> {
            int step = invocation.incrementAndGet();
            return ScriptExecutionResult.success(step == 1 ? "{\"value\":2}" : "{\"value\":3}",
                    Duration.ofMillis(1));
        }), mapper, new DeterministicRuleEngine(List.of()), CLOCK);
        RuleExecutionEnvelope envelope = envelope(mapper, List.of(step("first"), step("second")));

        RuleExecutionResult result = processor.process(envelope);

        assertThat(invocation).hasValue(2);
        RuleMessage transformed = result.message();
        assertThat(transformed.payload().get("value").asInt()).isEqualTo(3);
        assertThat(transformed.messageId()).isEqualTo(envelope.message().messageId());
        assertThat(transformed.tenantId()).isEqualTo(envelope.message().tenantId());
        assertThat(transformed.projectId()).isEqualTo(envelope.message().projectId());
        assertThat(transformed.deviceId()).isEqualTo(envelope.message().deviceId());
        assertThat(transformed.traceId()).isEqualTo(envelope.message().traceId());
        assertThat(transformed.occurredAt()).isEqualTo(envelope.message().occurredAt());
        assertThat(transformed.metadata()).isEqualTo(envelope.message().metadata());
        assertThat(result.sideEffectIntents()).isEmpty();
    }

    /** 沙箱队列拒绝是暂态依赖，必须进入有限 retry 而非永久脚本 DLQ。 */
    @Test
    void classifiesSandboxRejectionAsRetryable() {
        ObjectMapper mapper = new ObjectMapper();
        PublishedRuleScriptProcessor processor = new PublishedRuleScriptProcessor(sandbox(request ->
                ScriptExecutionResult.failed(ScriptExecutionStatus.REJECTED, "QUEUE_FULL", Duration.ZERO)),
                mapper, new DeterministicRuleEngine(List.of()), CLOCK);

        assertThatThrownBy(() -> processor.process(envelope(mapper, List.of(step("only")))))
                .isInstanceOfSatisfying(RuleExecutionException.class,
                        exception -> assertThat(exception.failure())
                                .isEqualTo(RuleExecutionFailure.SANDBOX_UNAVAILABLE));
    }

    /** 生产预处理只接受 JSON object，禁止把标量写入 Standard payload。 */
    @Test
    void rejectsScalarOutput() {
        ObjectMapper mapper = new ObjectMapper();
        PublishedRuleScriptProcessor processor = new PublishedRuleScriptProcessor(sandbox(request ->
                ScriptExecutionResult.success("42", Duration.ZERO)),
                mapper, new DeterministicRuleEngine(List.of()), CLOCK);

        assertThatThrownBy(() -> processor.process(envelope(mapper, List.of(step("only")))))
                .isInstanceOfSatisfying(RuleExecutionException.class,
                        exception -> assertThat(exception.failure())
                                .isEqualTo(RuleExecutionFailure.CONTRACT_INVALID));
    }

    /** 脚本派生 payload 后，动作节点产出通知意图且不改变最终 payload。 */
    @Test
    void producesNotificationIntentWithoutMutatingPayload() {
        ObjectMapper mapper = new ObjectMapper();
        DeterministicRuleEngine engine = new DeterministicRuleEngine(List.of(new NotificationActionNode()));
        PublishedRuleScriptProcessor processor = new PublishedRuleScriptProcessor(sandbox(request ->
                ScriptExecutionResult.success("{\"temperature\":42}", Duration.ZERO)),
                mapper, engine, CLOCK);
        PublishedRuleAction action = new PublishedRuleAction(NotificationActionNode.TYPE,
                mapper.createObjectNode()
                        .put("channel", "email")
                        .put("recipient", "ops@example.com")
                        .put("subject", "温度告警 ${payload.temperature}")
                        .put("body", "设备 ${deviceId} 温度异常"));
        RuleExecutionEnvelope envelope = envelope(mapper, List.of(new PublishedRuleStep(
                UUID.randomUUID(), UUID.randomUUID(), 1, "input => input", List.of(action))));

        RuleExecutionResult result = processor.process(envelope);

        assertThat(result.message().payload().get("temperature").asInt()).isEqualTo(42);
        assertThat(result.sideEffectIntents()).hasSize(1);
        RuleSideEffectIntent intent = result.sideEffectIntents().getFirst();
        assertThat(intent.type()).isEqualTo("notification");
        assertThat(intent.payload().get("channel").asText()).isEqualTo("email");
        assertThat(intent.payload().get("recipient").asText()).isEqualTo("ops@example.com");
        assertThat(intent.payload().get("subject").asText()).isEqualTo("温度告警 42");
        assertThat(intent.payload().get("deviceId").asText()).isEqualTo(result.message().deviceId().toString());
        assertThat(intent.payload().get("occurredAt").asText()).isEqualTo(result.message().occurredAt().toString());
    }

    /** @param source 可辨识步骤源码 @return 不可变计划步骤 */
    private static PublishedRuleStep step(String source) {
        return new PublishedRuleStep(UUID.randomUUID(), UUID.randomUUID(), 1, source);
    }

    /** @param execution 测试执行函数 @return 同时满足 validate/execute 的沙箱桩 */
    private static ScriptSandbox sandbox(
            java.util.function.Function<com.things.link.rule.application.ScriptExecutionRequest,
                    ScriptExecutionResult> execution) {
        return new ScriptSandbox() {
            /** {@inheritDoc} */
            @Override
            public ScriptValidationResult validate(com.things.link.rule.application.ScriptKind kind, String source) {
                return ScriptValidationResult.valid(Duration.ZERO);
            }

            /** {@inheritDoc} */
            @Override
            public ScriptExecutionResult execute(
                    com.things.link.rule.application.ScriptExecutionRequest request) {
                return execution.apply(request);
            }
        };
    }

    /** @param mapper JSON 映射器 @param steps 有序步骤 @return 身份一致的生产信封 */
    private static RuleExecutionEnvelope envelope(ObjectMapper mapper, List<PublishedRuleStep> steps) {
        UUID tenant = UUID.randomUUID();
        UUID project = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        RuleMessage message = new RuleMessage(messageId, tenant, project, UUID.randomUUID(), "trace",
                Instant.parse("2026-08-13T05:59:00Z"), "PROPERTY",
                mapper.createObjectNode().put("value", 1), Map.of("protocol", "MQTT"));
        PublishedRulePlan plan = new PublishedRulePlan(steps);
        PublishedRuleStep first = steps.getFirst();
        return new RuleExecutionEnvelope(new RuleExecutionKey(project, messageId,
                first.ruleId(), first.ruleVersionId()), tenant, message, plan, 1,
                Instant.parse("2026-08-13T06:00:00Z"));
    }
}
