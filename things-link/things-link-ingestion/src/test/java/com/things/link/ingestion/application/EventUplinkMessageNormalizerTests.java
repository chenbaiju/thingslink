package com.things.link.ingestion.application;

import com.things.link.shared.id.Uuid7;
import com.things.link.shared.message.RawUplinkMessage;
import com.things.link.shared.message.TransportProtocol;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventUplinkMessageNormalizerTests {
    private final EventUplinkMessageNormalizer normalizer = new EventUplinkMessageNormalizer(new EventUplinkMessageReader());

    @Test
    void bindsEventKeyOnlyToExactTopicAndPreservesTrustedScopeAndOriginalBytes() {
        var raw = raw("tc/v1/project/device/up/event/alarm", "2026-10-06T00:05:00Z");
        var result = normalizer.tryNormalize(raw).orElseThrow();
        assertThat(result.eventKey()).isEqualTo("alarm");
        assertThat(result.tenantId()).isEqualTo(raw.tenantId());
        assertThat(result.projectId()).isEqualTo(raw.projectId());
        assertThat(result.deviceId()).isEqualTo(raw.deviceId());
        assertThat(result.protocol()).isEqualTo(TransportProtocol.MQTT);
        assertThat(result.receivedAt()).isEqualTo(raw.receivedAt());
        assertThat(result.rawBytes()).isEqualTo(raw.payload().length);
        assertThat(result.traceId()).isEqualTo(raw.traceId());
        assertThat(result.params()).isEmpty();
    }

    @Test
    void declinesOtherTypesButRejectsExtraMissingInvalidEventLayersAndFutureTime() {
        assertThat(normalizer.tryNormalize(raw("tc/v1/project/device/up/property/report", "2026-10-06T00:00:00Z"))).isEmpty();
        for (String topic : new String[] {"tc/v1/project/device/up/event", "tc/v1/project/device/up/event/alarm/extra",
                "tc/v1/project/device/up/event/_alarm", "tc/v1/project/device/up/event/"}) {
            assertThatThrownBy(() -> normalizer.tryNormalize(raw(topic, "2026-10-06T00:00:00Z")))
                    .isInstanceOf(InvalidUplinkMessageException.class).hasMessage("EVENT_TOPIC_INVALID");
        }
        assertThatThrownBy(() -> normalizer.tryNormalize(raw("tc/v1/project/device/up/event/alarm", "2026-10-06T00:05:00.000000001Z")))
                .isInstanceOf(InvalidUplinkMessageException.class).hasMessage("EVENT_TIMESTAMP_INVALID");
        assertThat(normalizer.tryNormalize(raw("tc/v1/project/device/up/event/alarm", "2020-01-01T00:00:00Z"))).isPresent();
    }

    @Test
    void rawBoundaryRejectsOtherQosAndRetainedDeliveryBeforeEventNormalization() {
        var source = raw("tc/v1/project/device/up/event/alarm", "2026-10-06T00:00:00Z");
        for (int qos : new int[] {0, 2}) {
            assertThatThrownBy(() -> new RawUplinkMessage(source.tenantId(), source.projectId(), source.deviceId(), source.topic(),
                    source.payload(), qos, false, source.clientId(), source.receivedAt(), source.traceId()))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("上行消息 QoS 必须为 1");
        }
        assertThatThrownBy(() -> new RawUplinkMessage(source.tenantId(), source.projectId(), source.deviceId(), source.topic(),
                source.payload(), 1, true, source.clientId(), source.receivedAt(), source.traceId()))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("上行消息禁止 retained");
    }

    private static RawUplinkMessage raw(String topic, String occurredAt) {
        return new RawUplinkMessage(Uuid7.generate(), Uuid7.generate(), Uuid7.generate(), topic,
                ("{\"messageId\":\"" + Uuid7.generate() + "\",\"modelVersion\":\"1.0.0\",\"occurredAt\":\""
                        + occurredAt + "\",\"params\":{}}").getBytes(StandardCharsets.UTF_8),
                1, false, "client", Instant.parse("2026-10-06T00:00:00Z"), "trace-event");
    }
}
