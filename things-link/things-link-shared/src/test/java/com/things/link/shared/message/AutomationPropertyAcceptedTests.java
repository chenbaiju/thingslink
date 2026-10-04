package com.things.link.shared.message;

import com.things.link.shared.id.Uuid7;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/** 事件协议不允许未知版本或缺身份，精确数字及深层快照须保持。 */
class AutomationPropertyAcceptedTests {
    @Test void freezesNestedInputAndPreservesExactNumbers() {
        Map<String,Object> nested = new HashMap<>();
        nested.put("value", new BigDecimal("0.12345678901234567890123456789"));
        var message = event(1, Map.of("sensor", nested));
        nested.put("value", 99);
        assertThat(((Map<?,?>) message.payload().get("sensor")).get("value"))
                .isEqualTo(new BigDecimal("0.12345678901234567890123456789"));
        assertThatThrownBy(() -> message.payload().put("other", true)).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void rejectsUnknownSchemaAndEmptyPayload() {
        assertThatThrownBy(() -> event(2, Map.of("x", 1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> event(1, Map.of())).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejectsMissingResolvedVersionAndNonV7Source() {
        var m = event(1, Map.of("x", 1));
        assertThatThrownBy(() -> new AutomationPropertyAccepted(1, m.sourceEventId(), m.tenantId(),
                m.projectId(), m.deviceId(), null, m.occurredAt(), m.acceptedAt(), m.payload(), m.traceId()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AutomationPropertyAccepted(1, UUID.randomUUID(), m.tenantId(),
                m.projectId(), m.deviceId(), m.modelVersion(), m.occurredAt(), m.acceptedAt(), m.payload(), m.traceId()))
                .isInstanceOf(IllegalArgumentException.class);
    }
    private static AutomationPropertyAccepted event(int schema, Map<String,Object> payload) {
        return new AutomationPropertyAccepted(schema, Uuid7.generate(), Uuid7.generate(), Uuid7.generate(),
                Uuid7.generate(), "1.0.0", Instant.now(), Instant.now(), payload, "automation-test");
    }
}
