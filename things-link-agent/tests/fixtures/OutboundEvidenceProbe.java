import com.things.link.alarm.application.DeviceAlarmStatusSnapshot;
import com.things.link.assistant.application.DeviceEvidenceSnapshot;
import com.things.link.assistant.application.OutboundEvidenceProjector;
import com.things.link.assistant.application.OutboundPropertyPolicy;
import com.things.link.assistant.application.OutboundPropertyPolicy.*;
import com.things.link.assistant.application.PreparedModelEvidence.Template;
import com.things.link.device.application.ConsoleDeviceEvidence;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;

/** Synthetic, offline producer proof. No platform connection or provider credentials. */
class OutboundEvidenceProbe {
    public static void main(String[] args) {
        var project = UUID.fromString("00000000-0000-0000-0000-000000000001");
        var version = UUID.fromString("00000000-0000-0000-0000-000000000002");
        var time = Instant.parse("2026-10-03T12:00:00.123456789Z");
        var json = JsonMapper.builder().build();
        var properties = List.of(
                new ConsoleDeviceEvidence.Property("private_omitted", json.readTree("\"private text\""), time,
                        "7", version, time, ConsoleDeviceEvidence.Availability.PRESENT),
                new ConsoleDeviceEvidence.Property("private_numeric", JsonNodeFactory.instance.numberNode(
                        new BigDecimal("0.12345678901234567890123456789")), time,
                        "7", version, time, ConsoleDeviceEvidence.Availability.PRESENT),
                new ConsoleDeviceEvidence.Property("private_boolean", json.readTree("false"), time,
                        "7", version, time, ConsoleDeviceEvidence.Availability.PRESENT));
        var snapshot = new DeviceEvidenceSnapshot(1, project,
                UUID.fromString("00000000-0000-0000-0000-000000000003"), version, time, time,
                new DeviceEvidenceSnapshot.Device("INACTIVE", null, time), properties,
                new DeviceEvidenceSnapshot.AlarmSummary(DeviceAlarmStatusSnapshot.State.ACTIVE, time));
        var projector = new OutboundEvidenceProjector(new OutboundPropertyPolicy(List.of(
                new Binding(project, version, "private_numeric", Semantic.TEMPERATURE, Unit.CELSIUS),
                new Binding(project, version, "private_boolean", Semantic.BINARY_STATE, Unit.UNITLESS))));
        for (var template : Template.values()) {
            System.out.println(json.writeValueAsString(projector.prepare(snapshot, template).input()));
        }
    }
}
