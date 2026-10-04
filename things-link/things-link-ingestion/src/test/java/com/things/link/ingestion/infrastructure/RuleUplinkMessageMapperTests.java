package com.things.link.ingestion.infrastructure;

import com.things.link.ingestion.application.InvalidUplinkMessageException;
import com.things.link.rule.application.engine.RuleMessage;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.StandardUplinkMessage;
import com.things.link.shared.message.TransportProtocol;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证脚本前后 payload 可变、可信信封字段不可变的双向映射。 */
class RuleUplinkMessageMapperTests {

    /** 合法脚本输出只替换 payload，并完整保留网关、协议、时间、trace 与原始字节计量。 */
    @Test
    void restoresAllTrustedFieldsWithTransformedPayload() {
        RuleUplinkMessageMapper mapper = new RuleUplinkMessageMapper(new ObjectMapper());
        StandardUplinkMessage source = message();
        RuleMessage ruleMessage = mapper.toRuleMessage(source);
        RuleMessage transformed = ruleMessage.withPayload(
                new ObjectMapper().valueToTree(Map.of("temperature", 27.5)));

        StandardUplinkMessage result = mapper.toStandardMessage(ruleMessage, transformed);

        assertThat(result).usingRecursiveComparison().ignoringFields("payload").isEqualTo(source);
        assertThat(result.payload()).containsEntry("temperature", new java.math.BigDecimal("27.5"));
    }

    /** 即使处理器试图伪造 traceId，也必须在发布 processed 前 fail closed。 */
    @Test
    void rejectsTrustedIdentityMutation() {
        RuleUplinkMessageMapper mapper = new RuleUplinkMessageMapper(new ObjectMapper());
        RuleMessage original = mapper.toRuleMessage(message());
        RuleMessage malicious = new RuleMessage(original.messageId(), original.tenantId(), original.projectId(),
                original.deviceId(), "forged-trace", original.occurredAt(), original.type(),
                original.payload(), original.metadata());

        assertThatThrownBy(() -> mapper.toStandardMessage(original, malicious))
                .isInstanceOf(InvalidUplinkMessageException.class);
    }

    /** @return 带网关和非零计费字节数的标准上行 */
    private static StandardUplinkMessage message() {
        return new StandardUplinkMessage(Uuid7.generate(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), TransportProtocol.MQTT,
                StandardUplinkMessage.Direction.UP, StandardUplinkMessage.Type.PROPERTY_REPORT,
                "1.0.0", Instant.parse("2026-08-13T08:00:00Z"), Instant.parse("2026-08-13T08:00:01Z"),
                "trace-s8-2c", 128, Map.of("temperature", 26.5));
    }
}
