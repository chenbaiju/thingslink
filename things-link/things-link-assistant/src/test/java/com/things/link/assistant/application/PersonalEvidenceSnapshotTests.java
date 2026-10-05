package com.things.link.assistant.application;
import com.things.link.alarm.application.DeviceAlarmStatusSnapshot;
import com.things.link.device.application.ConsoleDeviceEvidence;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
class PersonalEvidenceSnapshotTests {
    final ObjectMapper json=new ObjectMapper();
    @Test void onlyServerNumericBooleanFactsPersistAndTextNeverAppearsInContentOrPrinting() {
        var now=Instant.parse("2026-10-04T00:00:00Z");var model=UUID.randomUUID();
        var properties=List.of(
            new ConsoleDeviceEvidence.Property("n",json.readTree("12.34"),now,"1",model,now,ConsoleDeviceEvidence.Availability.PRESENT),
            new ConsoleDeviceEvidence.Property("b",json.readTree("false"),now,"2",model,now,ConsoleDeviceEvidence.Availability.PRESENT),
            new ConsoleDeviceEvidence.Property("s",json.readTree("\"sensitive free text\""),now,"3",model,now,ConsoleDeviceEvidence.Availability.PRESENT),
            new ConsoleDeviceEvidence.Property("o",json.readTree("{\"password\":\"sensitive\"}"),now,"4",model,now,ConsoleDeviceEvidence.Availability.PRESENT),
            new ConsoleDeviceEvidence.Property("u",json.readTree("99"),now,"5",null,now,ConsoleDeviceEvidence.Availability.SOURCE_UNKNOWN));
        var source=new DeviceEvidenceSnapshot(1,UUID.randomUUID(),UUID.randomUUID(),model,now,now,
            new DeviceEvidenceSnapshot.Device("ONLINE",null,now),properties,new DeviceEvidenceSnapshot.AlarmSummary(DeviceAlarmStatusSnapshot.State.ACTIVE,now));
        var result=PersonalEvidenceSnapshot.from(source);String content=json.writeValueAsString(result);
        assertThat(content).doesNotContain("sensitive","password","projectId","tenantId","reportedRevision");
        assertThat(result.properties().get(0).value().asDouble()).isEqualTo(12.34);
        assertThat(result.properties().get(1).value().asBoolean()).isFalse();
        assertThat(result.properties().get(2).valueOmitted()).isTrue();assertThat(result.properties().get(3).valueOmitted()).isTrue();
        assertThat(result.properties().get(4).value()).isNull();assertThat(result.properties().get(4).valueOmitted()).isFalse();
        assertThat(json.writeValueAsString(json.readValue(content,PersonalEvidenceSnapshot.class))).isEqualTo(content);
        assertThat(result.toString()+result.properties()).doesNotContain("12.34","sensitive","password");
    }
}
