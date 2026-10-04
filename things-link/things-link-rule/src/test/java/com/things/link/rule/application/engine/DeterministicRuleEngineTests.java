package com.things.link.rule.application.engine;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** S8-2A 内核契约测试：不可变消息、配置门禁、类型注册与防御性复制。 */
class DeterministicRuleEngineTests {

    /** 测试使用的 Jackson 3 映射器。 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 原始 JSON、读取副本和 metadata 调用方都不能穿透修改消息。 */
    @Test
    void keepsMessageSnapshotDeeplyImmutable() {
        ObjectNode payload = objectMapper.createObjectNode().put("temperature", 30);
        Map<String, String> metadata = new java.util.HashMap<>(Map.of("source", "mqtt"));

        RuleMessage message = message(payload, metadata);
        payload.put("temperature", 99);
        metadata.put("source", "http");
        ((ObjectNode) message.payload()).put("temperature", 88);

        assertThat(message.payload().get("temperature").intValue()).isEqualTo(30);
        assertThat(message.metadata()).containsEntry("source", "mqtt");
        assertThatThrownBy(() -> message.metadata().put("new", "value"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    /** 未知类型与非法配置必须在节点执行前失败，避免队列吞掉契约错误。 */
    @Test
    void failsClosedBeforeExecutingInvalidNode() {
        RecordingNode node = new RecordingNode(false);
        DeterministicRuleEngine engine = new DeterministicRuleEngine(List.of(node));

        assertThatThrownBy(() -> engine.execute("missing", objectMapper.createObjectNode(),
                message(objectMapper.createObjectNode(), Map.of()), context()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> engine.execute(node.type(), objectMapper.createObjectNode(),
                message(objectMapper.createObjectNode(), Map.of()), context()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(node.executed).isFalse();
    }

    /** 控制面 validate 门禁：未知类型与非法配置返回封闭错误而非抛异常，合法配置通过。 */
    @Test
    void validateFailsClosedForUnknownAndInvalid() {
        DeterministicRuleEngine validNode = new DeterministicRuleEngine(List.of(new RecordingNode(true)));
        DeterministicRuleEngine invalidNode = new DeterministicRuleEngine(List.of(new RecordingNode(false)));

        RuleNodeValidation unknown = validNode.validate("missing", objectMapper.createObjectNode());
        RuleNodeValidation invalid = invalidNode.validate("recording", objectMapper.createObjectNode());
        RuleNodeValidation valid = validNode.validate("recording",
                objectMapper.createObjectNode().put("value", "ok"));

        assertThat(unknown.valid()).isFalse();
        assertThat(unknown.errors()).containsExactly("不支持的规则节点类型: missing");
        assertThat(invalid.valid()).isFalse();
        assertThat(invalid.errors()).containsExactly("固定错误");
        assertThat(valid.valid()).isTrue();
        assertThat(valid.errors()).isEmpty();
    }

    /** 引擎传入节点的是配置副本，恶意或错误节点不能污染调用方配置。 */
    @Test
    void isolatesCallerConfigurationAndRejectsDuplicateTypes() {
        RecordingNode node = new RecordingNode(true);
        DeterministicRuleEngine engine = new DeterministicRuleEngine(List.of(node));
        ObjectNode config = objectMapper.createObjectNode().put("value", "original");

        engine.execute(node.type(), config, message(objectMapper.createObjectNode(), Map.of()), context());

        assertThat(config.get("value").stringValue()).isEqualTo("original");
        assertThatThrownBy(() -> new DeterministicRuleEngine(List.of(node, new RecordingNode(true))))
                .isInstanceOf(IllegalStateException.class);
    }

    /** 构造带完整可信身份的规则消息。 */
    private static RuleMessage message(JsonNode payload, Map<String, String> metadata) {
        return new RuleMessage(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "trace-test", Instant.parse("2026-08-13T00:00:00Z"), "PROPERTY_REPORT", payload, metadata);
    }

    /** 构造不携带服务对象的受限执行上下文。 */
    private static RuleExecutionContext context() {
        return new RuleExecutionContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1,
                Instant.parse("2026-08-13T00:00:01Z"));
    }

    /** 验证引擎控制流和配置复制的最小测试节点。 */
    private static final class RecordingNode implements RuleNode {

        /** 固定配置有效性。 */
        private final boolean valid;
        /** 是否曾进入执行方法。 */
        private boolean executed;

        /** 保存当前用例所需的固定校验结果。 */
        private RecordingNode(boolean valid) {
            this.valid = valid;
        }

        /** {@inheritDoc} */
        @Override
        public String type() {
            return "recording";
        }

        /** {@inheritDoc} */
        @Override
        public JsonNode configSchema() {
            return new ObjectMapper().createObjectNode();
        }

        /** {@inheritDoc} */
        @Override
        public RuleNodeValidation validate(JsonNode config) {
            return valid ? RuleNodeValidation.success() : RuleNodeValidation.invalid("固定错误");
        }

        /** {@inheritDoc} */
        @Override
        public RuleNodeResult execute(RuleMessage message, JsonNode config, RuleExecutionContext context) {
            executed = true;
            ((ObjectNode) config).put("value", "changed");
            return RuleNodeResult.withoutSideEffect(RuleRelation.SUCCESS, message);
        }
    }
}
