package com.things.link.rule.application;

import com.things.link.rule.application.engine.DeterministicRuleEngine;
import com.things.link.rule.application.engine.RuleMessage;
import com.things.link.rule.application.engine.node.NotificationActionNode;
import com.things.link.rule.application.engine.node.PayloadPropertyCompareNode;
import com.things.link.rule.domain.RuleSceneExecution;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** S9-4 确定性执行处理器：ALL_OF 有序短路、白名单 fail-closed 与动作意图收集不依赖仓储或网络。 */
class RuleSceneExecutionProcessorTests {

    /** 条件与动作 jsonb 映射器。 */
    private final ObjectMapper objectMapper = new ObjectMapper();
    /** 真实确定性引擎，含条件与通知动作两个节点。 */
    private DeterministicRuleEngine engine;
    /** 被测处理器。 */
    private RuleSceneExecutionProcessor processor;
    /** 固定场景与版本身份。 */
    private UUID sceneId;
    private UUID sceneVersionId;

    /** 每个用例重建引擎与身份。 */
    @BeforeEach
    void setUp() {
        engine = new DeterministicRuleEngine(List.of(new PayloadPropertyCompareNode(), new NotificationActionNode()));
        processor = new RuleSceneExecutionProcessor(engine);
        sceneId = UUID.randomUUID();
        sceneVersionId = UUID.randomUUID();
    }

    /** 全部条件成立时动作按序收集意图，终态 DISPATCHED。 */
    @Test
    void dispatchesIntentsWhenAllConditionsTrue() {
        JsonNode conditions = json("""
                [{"nodeType":"payload-property-compare","config":{"pointer":"/temperature","operator":"GT","value":30}}]
                """);
        JsonNode actions = json("""
                [{"nodeType":"notification-action","config":{"channel":"email","recipient":"ops@example.com"}}]
                """);

        var outcome = processor.process(
                message(json("{\"temperature\":42}")), sceneId, sceneVersionId, Instant.now(),
                conditions, actions);

        assertThat(outcome.status()).isEqualTo(RuleSceneExecution.Status.DISPATCHED);
        assertThat(outcome.intents()).hasSize(1);
        assertThat(outcome.intents().getFirst().type()).isEqualTo("notification");
    }

    /** 首个条件不成立即短路 SKIPPED，后续即使非法节点也不求值、不产出意图。 */
    @Test
    void shortCircuitsOnFirstFalseCondition() {
        JsonNode conditions = json("""
                [
                  {"nodeType":"payload-property-compare","config":{"pointer":"/temperature","operator":"GT","value":30}},
                  {"nodeType":"not-a-real-node","config":{}}
                ]
                """);
        JsonNode actions = json("""
                [{"nodeType":"notification-action","config":{"channel":"email","recipient":"ops@example.com"}}]
                """);

        var outcome = processor.process(
                message(json("{\"temperature\":10}")), sceneId, sceneVersionId, Instant.now(),
                conditions, actions);

        assertThat(outcome.status()).isEqualTo(RuleSceneExecution.Status.SKIPPED);
        assertThat(outcome.intents()).isEmpty();
    }

    /** 条件白名单外的节点直接 fail-closed 为 FAILED，绝不回退到宽松语义。 */
    @Test
    void failsClosedOnUnknownConditionNode() {
        JsonNode conditions = json("""
                [{"nodeType":"message-type-filter","config":{"types":["PROPERTY_REPORT"]}}]
                """);
        JsonNode actions = json("""
                [{"nodeType":"notification-action","config":{"channel":"email","recipient":"ops@example.com"}}]
                """);

        var outcome = processor.process(
                message(json("{}")), sceneId, sceneVersionId, Instant.now(), conditions, actions);

        assertThat(outcome.status()).isEqualTo(RuleSceneExecution.Status.FAILED);
        assertThat(outcome.intents()).isEmpty();
    }

    /** 条件成立但动作白名单外的节点同样 fail-closed，已收集的意图全部丢弃。 */
    @Test
    void failsClosedOnUnknownActionNode() {
        JsonNode conditions = json("""
                [{"nodeType":"payload-property-compare","config":{"pointer":"/on","operator":"EQ","value":true}}]
                """);
        JsonNode actions = json("""
                [{"nodeType":"script-action","config":{"source":"1+1"}}]
                """);

        var outcome = processor.process(
                message(json("{\"on\":true}")), sceneId, sceneVersionId, Instant.now(), conditions, actions);

        assertThat(outcome.status()).isEqualTo(RuleSceneExecution.Status.FAILED);
        assertThat(outcome.intents()).isEmpty();
    }

    /** 空动作数组时条件全真仍为 DISPATCHED，只是没有意图；动作数量约束由应用服务负责。 */
    @Test
    void dispatchesEmptyIntentsWhenNoActions() {
        JsonNode conditions = json("""
                [{"nodeType":"payload-property-compare","config":{"pointer":"/on","operator":"EQ","value":true}}]
                """);

        var outcome = processor.process(
                message(json("{\"on\":true}")), sceneId, sceneVersionId, Instant.now(),
                conditions, objectMapper.createArrayNode());

        assertThat(outcome.status()).isEqualTo(RuleSceneExecution.Status.DISPATCHED);
        assertThat(outcome.intents()).isEmpty();
    }

    /** 解析测试 JSON 字面量。 */
    private JsonNode json(String value) {
        return objectMapper.readTree(value);
    }

    /** 构造服务端受理语义的不可变规则消息。 */
    private RuleMessage message(JsonNode payload) {
        return new RuleMessage(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "trace-scene", Instant.parse("2026-08-14T00:00:00Z"), "MANUAL_SCENE", payload, Map.of());
    }
}
