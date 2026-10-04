package com.things.link.ingestion.application;

import com.things.link.shared.message.TransportProtocol;
import com.things.link.shared.message.StandardUplinkMessage;
import com.things.link.shared.id.Uuid7;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 上行预处理扩展链测试，固定顺序与确权身份不可变约束。 */
class UplinkPreprocessingChainTests {
    /** 多个处理器按 order 顺序变换 payload。 */
    @Test
    void appliesProcessorsInStableOrder() {
        StandardUplinkMessage source = message(Map.of("temperature", 20));
        List<String> calls = new ArrayList<>();
        UplinkPreprocessor second = processor(20, "second", calls);
        UplinkPreprocessor first = processor(10, "first", calls);

        StandardUplinkMessage result = new UplinkPreprocessingChain(List.of(second, first)).apply(source);

        assertThat(calls).containsExactly("first", "second");
        assertThat(result.payload()).containsKeys("temperature", "first", "second");
    }

    /** 预处理器不得伪造接入层已经确权的设备身份。 */
    @Test
    void rejectsProcessorThatChangesTrustedIdentity() {
        StandardUplinkMessage source = message(Map.of("temperature", 20));
        UplinkPreprocessor malicious = input -> new StandardUplinkMessage(
                input.messageId(), input.tenantId(), input.projectId(), UUID.randomUUID(), input.gatewayId(),
                input.protocol(), input.direction(), input.type(), input.modelVersion(), input.occurredAt(), input.receivedAt(),
                input.traceId(), input.payload());

        assertThatThrownBy(() -> new UplinkPreprocessingChain(List.of(malicious)).apply(source))
                .isInstanceOf(InvalidUplinkMessageException.class)
                .hasMessageContaining("不得修改已确权");
    }

    /** 创建只向 payload 追加标记的测试处理器。 */
    private static UplinkPreprocessor processor(int order, String marker, List<String> calls) {
        return new UplinkPreprocessor() {
            /** {@inheritDoc} */
            @Override public StandardUplinkMessage preprocess(StandardUplinkMessage input) {
                calls.add(marker);
                Map<String, Object> payload = new LinkedHashMap<>(input.payload());
                payload.put(marker, true);
                return new StandardUplinkMessage(input.messageId(), input.tenantId(), input.projectId(),
                        input.deviceId(), input.gatewayId(), input.protocol(), input.direction(), input.type(),
                        input.modelVersion(), input.occurredAt(), input.receivedAt(), input.traceId(), payload);
            }

            /** {@inheritDoc} */
            @Override public int order() {
                return order;
            }
        };
    }

    /** 创建完整可信标准上行信封。 */
    private static StandardUplinkMessage message(Map<String, Object> payload) {
        return new StandardUplinkMessage(Uuid7.generate(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                null, TransportProtocol.MQTT, StandardUplinkMessage.Direction.UP,
                StandardUplinkMessage.Type.PROPERTY_REPORT, "1.0.0", Instant.parse("2026-08-07T08:00:00Z"),
                Instant.parse("2026-08-07T08:00:01Z"), "0123456789abcdef0123456789abcdef", payload);
    }
}
