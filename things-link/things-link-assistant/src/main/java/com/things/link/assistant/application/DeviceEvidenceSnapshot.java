package com.things.link.assistant.application;

import com.things.link.device.application.ConsoleDeviceEvidence;
import com.things.link.alarm.application.DeviceAlarmStatusSnapshot;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 多来源采集区间，不宣称设备与告警共享数据库快照。 */
@Schema(requiredProperties = {"schemaVersion", "projectId", "deviceId", "modelVersionId", "collectionStartedAt", "collectionFinishedAt", "device", "properties", "alarmSummary"})
public record DeviceEvidenceSnapshot(int schemaVersion, UUID projectId, UUID deviceId, UUID modelVersionId,
        Instant collectionStartedAt, Instant collectionFinishedAt, Device device,
        List<ConsoleDeviceEvidence.Property> properties, AlarmSummary alarmSummary) {
    public DeviceEvidenceSnapshot { properties = List.copyOf(properties); }
    @io.swagger.v3.oas.annotations.media.Schema(name = "AssistantEvidenceDevice", requiredProperties = {"status", "lastOnlineAt", "readAt"})
    public record Device(String status, @Schema(types = {"string", "null"}, format = "date-time") Instant lastOnlineAt, Instant readAt) { }
    @io.swagger.v3.oas.annotations.media.Schema(name = "AssistantEvidenceAlarmSummary", requiredProperties = {"state", "observedAt"})
    public record AlarmSummary(DeviceAlarmStatusSnapshot.State state, Instant observedAt) { }
}
