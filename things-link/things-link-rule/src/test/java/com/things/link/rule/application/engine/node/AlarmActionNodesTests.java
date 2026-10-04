package com.things.link.rule.application.engine.node;

import com.things.link.rule.application.engine.RuleExecutionContext;
import com.things.link.rule.application.engine.RuleMessage;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** S9-3 告警创建/清除节点的规则引用、可信消息身份和 fail-closed 配置测试。 */
class AlarmActionNodesTests {

    /** 构造不可变配置与载荷的 JSON 工具。 */
    private final ObjectMapper mapper = new ObjectMapper();

    /** 已经由上行链路完成项目与设备确权的规则消息。 */
    private final RuleMessage message = new RuleMessage(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "trace-alarm",
            Instant.parse("2026-08-14T02:00:00Z"), "PROPERTY_REPORT",
            mapper.createObjectNode().put("temperature", 90), Map.of());

    /** 固定的处理接收时间会进入意图，不能用设备时间伪装平台接收时间。 */
    private final RuleExecutionContext context = new RuleExecutionContext(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1,
            Instant.parse("2026-08-14T02:00:01Z"));

    /** 创建动作只冻结规则引用，其他身份全部来自可信消息与执行上下文。 */
    @Test
    void createIntentUsesAlarmRuleReferenceAndTrustedIdentity() {
        UUID alarmRuleId = UUID.randomUUID();
        AlarmCreateActionNode node = new AlarmCreateActionNode();
        var config = mapper.createObjectNode().put("alarmRuleId", alarmRuleId.toString());

        assertThat(node.validate(config).valid()).isTrue();
        var result = node.execute(message, config, context);

        assertThat(result.message()).isEqualTo(message);
        assertThat(result.sideEffectIntents()).hasSize(1);
        assertThat(result.sideEffectIntents().getFirst().type()).isEqualTo("alarm-create");
        var payload = result.sideEffectIntents().getFirst().payload();
        assertThat(payload.path("alarmRuleId").asText()).isEqualTo(alarmRuleId.toString());
        assertThat(payload.path("deviceId").asText()).isEqualTo(message.deviceId().toString());
        assertThat(payload.path("messageId").asText()).isEqualTo(message.messageId().toString());
        assertThat(payload.path("occurredAt").asText()).isEqualTo(message.occurredAt().toString());
        assertThat(payload.path("receivedAt").asText()).isEqualTo(context.startedAt().toString());
        assertThat(payload.path("traceId").asText()).isEqualTo(message.traceId());
    }

    /** 创建动作拒绝非 UUID 与复制的告警字段，权威类型和严重程度只能来自 S6 规则。 */
    @Test
    void createConfigurationFailsClosed() {
        AlarmCreateActionNode node = new AlarmCreateActionNode();

        assertThat(node.validate(mapper.createObjectNode().put("alarmRuleId", "not-a-uuid")).valid()).isFalse();
        assertThat(node.validate(mapper.createObjectNode().put("alarmRuleId", UUID.randomUUID().toString())
                .put("severity", "CRITICAL")).valid()).isFalse();
    }

    /** 清除动作输出与创建动作同形的可信定位字段，便于同一后台端口消费。 */
    @Test
    void clearIntentUsesSameTrustedIdentityContract() {
        UUID alarmRuleId = UUID.randomUUID();
        AlarmClearActionNode node = new AlarmClearActionNode();
        var config = mapper.createObjectNode().put("alarmRuleId", alarmRuleId.toString());

        assertThat(node.validate(config).valid()).isTrue();
        var result = node.execute(message, config, context);

        assertThat(result.message()).isEqualTo(message);
        assertThat(result.sideEffectIntents().getFirst().type()).isEqualTo("alarm-clear");
        assertThat(result.sideEffectIntents().getFirst().payload().path("alarmRuleId").asText())
                .isEqualTo(alarmRuleId.toString());
        assertThat(result.sideEffectIntents().getFirst().payload().path("messageId").asText())
                .isEqualTo(message.messageId().toString());
    }

    /** 清除动作拒绝空配置和实例字段，实例选择必须留给告警公开端口。 */
    @Test
    void clearConfigurationFailsClosed() {
        AlarmClearActionNode node = new AlarmClearActionNode();

        assertThat(node.validate(null).valid()).isFalse();
        assertThat(node.validate(mapper.createObjectNode().put("alarmRuleId", UUID.randomUUID().toString())
                .put("instanceId", UUID.randomUUID().toString())).valid()).isFalse();
    }
}
