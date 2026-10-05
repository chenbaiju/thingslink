package com.things.link.assistant.infrastructure.transport;

import com.things.link.assistant.application.PreparedModelEvidence.*;
import com.things.link.assistant.application.OutboundPropertyPolicy.*;
import com.things.link.assistant.domain.AnalysisCall;
import com.things.link.assistant.domain.AnalysisCall.Status;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class InternalAnalysisRequestEncoderTests {
    final JsonMapper json = JsonMapper.builder().build();
    final Instant time = Instant.parse("2026-10-04T00:00:00.123456789Z");
    final UUID id = UUID.fromString("019c1234-5678-7890-8123-456789abcdef");
    AnalysisCall call(UUID identity, long revision, Status status, Instant deadline, Instant dispatched) {
        return new AnalysisCall(identity, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), "private-key-hash", "private-request-hash", revision, status,
                time, deadline, time.plusSeconds(86400), dispatched, null);
    }
    AnalysisCall call() { return call(id, 2, Status.DISPATCHED, time.plusSeconds(60), time.plusSeconds(1)); }
    Input input(String alias, List<Reading> readings) {
        return new Input(Template.STATUS_SUMMARY, alias, time, time,
                new Device("e-device", DeviceStatus.INACTIVE, null, time),
                new Alarm("e-alarm", AlarmState.ACTIVE, time), readings);
    }
    Input input() {
        return input("device-1", List.of(
                new Reading("e-property-1", Semantic.TEMPERATURE, Unit.CELSIUS,
                        new NumberValue(new BigDecimal("0.12345678901234567890123456789")), time, time),
                new Reading("e-property-2", Semantic.BINARY_STATE, Unit.UNITLESS, new BooleanValue(false), time, time)));
    }
    void rejected(AnalysisCall call, Input input) {
        assertThatThrownBy(() -> InternalAnalysisRequestEncoder.encode(call, input))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("INVALID_INTERNAL_ANALYSIS_REQUEST").hasNoCause();
    }
    @Test void preservesExactInputAndOmitsAllNonWireMetadata() throws Exception {
        var call = call();
        var input = input();
        byte[] encoded = InternalAnalysisRequestEncoder.encode(call, input);
        var body = json.readTree(encoded);
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("version", "callId", "configurationRevision",
                "deadlineEpochMillis", "inputSha256", "inputBase64");
        assertThat(body.get("version").asString()).isEqualTo("agent-internal-analysis-v1");
        assertThat(body.get("callId").asString()).isEqualTo(id.toString());
        assertThat(body.get("configurationRevision").asLong()).isEqualTo(2);
        assertThat(body.get("deadlineEpochMillis").asLong()).isEqualTo(time.plusSeconds(60).toEpochMilli());
        byte[] actual = Base64.getDecoder().decode(body.get("inputBase64").asString());
        assertThat(actual).isEqualTo(json.writeValueAsBytes(input));
        assertThat(body.get("inputSha256").asString()).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(actual)));
        String text = new String(actual, StandardCharsets.UTF_8);
        assertThat(text).contains("0.12345678901234567890123456789", "2026-10-04T00:00:00.123456789Z", "false");
        assertThat(new String(encoded, StandardCharsets.UTF_8)).doesNotContain(call.tenantId().toString(),
                call.projectId().toString(), call.createdBy().toString(), call.deviceId().toString(),
                call.modelVersionId().toString(), call.keyHash(), call.requestHash(), "token", "credential");
        assertThat(input.readings()).hasSize(2);
        assertThat(((NumberValue) input.readings().getFirst().value()).value()).isEqualTo(new BigDecimal("0.12345678901234567890123456789"));
    }
    @ParameterizedTest @EnumSource(value=Status.class, names={"DISPATCHED"}, mode=EnumSource.Mode.EXCLUDE)
    void noOtherStateIsEncoded(Status status) { rejected(call(id, 2, status, time.plusSeconds(60), time.plusSeconds(1)), input()); }
    @ParameterizedTest @ValueSource(longs={0, -1})
    void invalidConfigurationRevisionIsRejected(long revision) { rejected(call(id, revision, Status.DISPATCHED, time.plusSeconds(60), time.plusSeconds(1)), input()); }
    @Test void missingAndWrongIdentityAreRejected() {
        rejected(null, input()); rejected(call(), null);
        rejected(call(null, 2, Status.DISPATCHED, time.plusSeconds(60), time.plusSeconds(1)), input());
        rejected(call(UUID.randomUUID(), 2, Status.DISPATCHED, time.plusSeconds(60), time.plusSeconds(1)), input());
    }
    @Test void originalDeadlineAndDispatchIntervalCannotBeExtended() {
        rejected(call(id, 2, Status.DISPATCHED, time.plusSeconds(61), time.plusSeconds(1)), input());
        rejected(call(id, 2, Status.DISPATCHED, time.plusSeconds(60), time.plusSeconds(60)), input());
        rejected(call(id, 2, Status.DISPATCHED, time.plusSeconds(60), time.minusNanos(1)), input());
        rejected(call(id, 2, Status.DISPATCHED, null, time.plusSeconds(1)), input());
    }
    @Test void aliasesReadingsAndEncodedByteBudgetRemainBounded() {
        rejected(call(), input("private-device-name", List.of()));
        rejected(call(), input("device-1", Collections.nCopies(11, input().readings().getFirst())));
        rejected(call(), input("device-1", List.of(new Reading("x".repeat(16384), Semantic.TEMPERATURE,
                Unit.CELSIUS, new NumberValue(BigDecimal.ONE), time, time))));
    }
}
