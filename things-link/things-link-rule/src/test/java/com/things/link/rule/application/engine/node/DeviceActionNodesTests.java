package com.things.link.rule.application.engine.node;

import com.things.link.rule.application.engine.RuleExecutionContext;
import com.things.link.rule.application.engine.RuleMessage;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** S9-2 两类设备动作节点的纯意图与 fail-closed 配置测试。 */
class DeviceActionNodesTests {
    /** JSON 工具。 */ private final ObjectMapper mapper = new ObjectMapper();
    /** 消息与执行身份。 */ private final RuleMessage message = new RuleMessage(UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), UUID.randomUUID(), "trace", Instant.parse("2026-08-13T00:00:00Z"), "PROPERTY_REPORT",
            mapper.createObjectNode().put("temperature", 20), Map.of());
    /** 执行上下文不影响动作输出。 */ private final RuleExecutionContext context = new RuleExecutionContext(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, Instant.parse("2026-08-13T00:00:00Z"));

    /** 命令动作只产出 device-command，目标设备强制来自可信 RuleMessage。 */
    @Test void commandIntentUsesTrustedMessageDevice() {
        var config = mapper.createObjectNode().put("commandKey", "reboot")
                .set("input", mapper.createObjectNode().put("delay", 1));
        DeviceCommandActionNode node = new DeviceCommandActionNode();
        assertThat(node.validate(config).valid()).isTrue();
        var result = node.execute(message, config, context);
        assertThat(result.sideEffectIntents()).hasSize(1);
        assertThat(result.sideEffectIntents().getFirst().type()).isEqualTo("device-command");
        assertThat(result.sideEffectIntents().getFirst().payload().path("deviceId").asText())
                .isEqualTo(message.deviceId().toString());
        assertThat(result.message()).isEqualTo(message);
    }

    /** 属性设置拒绝空对象，合法时只产出 device-property-set。 */
    @Test void propertySetRequiresNonEmptyProperties() {
        DevicePropertySetActionNode node = new DevicePropertySetActionNode();
        assertThat(node.validate(mapper.createObjectNode().set("properties", mapper.createObjectNode())).valid())
                .isFalse();
        var config = mapper.createObjectNode().set("properties",
                mapper.createObjectNode().put("targetTemperature", 24));
        assertThat(node.validate(config).valid()).isTrue();
        assertThat(node.execute(message, config, context).sideEffectIntents().getFirst().type())
                .isEqualTo("device-property-set");
    }
}
