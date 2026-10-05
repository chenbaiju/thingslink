import com.things.link.assistant.application.PreparedModelEvidence.*;
import com.things.link.assistant.application.OutboundPropertyPolicy.*;
import com.things.link.assistant.domain.AnalysisCall;
import com.things.link.assistant.infrastructure.transport.InternalAnalysisRequestEncoder;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 合成离线生产者证明；无平台连接、真实设备或供应商凭据。 */
class InternalAnalysisEnvelopeProbe {
    public static void main(String[] args) {
        var time = Instant.parse("2026-10-04T00:00:00.123456789Z");
        var foreign = UUID.fromString("00000000-0000-0000-0000-000000000001");
        var call = new AnalysisCall(UUID.fromString("019c1234-5678-7890-8123-456789abcdef"),
                foreign, foreign, foreign, foreign, foreign, "private-key-hash", "private-request-hash",
                2, AnalysisCall.Status.DISPATCHED, time, time.plusSeconds(60), time.plusSeconds(86400), time.plusSeconds(1), null);
        for (var template : Template.values()) {
            var input = new Input(template, "device-1", time, time,
                    new Device("e-device", DeviceStatus.INACTIVE, null, time),
                    new Alarm("e-alarm", AlarmState.ACTIVE, time), List.of(
                        new Reading("e-property-1", Semantic.TEMPERATURE, Unit.CELSIUS,
                                new NumberValue(new BigDecimal("0.12345678901234567890123456789")), time, time),
                        new Reading("e-property-2", Semantic.BINARY_STATE, Unit.UNITLESS,
                                new BooleanValue(false), time, time)));
            System.out.println(new String(InternalAnalysisRequestEncoder.encode(call, input), StandardCharsets.UTF_8));
        }
    }
}
