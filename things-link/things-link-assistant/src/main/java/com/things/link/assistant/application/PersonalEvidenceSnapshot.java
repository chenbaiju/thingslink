package com.things.link.assistant.application;
import com.things.link.device.application.ConsoleDeviceEvidence;
import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 历史事实的独立正文；不存文本/对象值，不提供当前设备状态或模型诊断断言。 */
@Schema(requiredProperties = {"schemaVersion", "deviceId", "modelVersionId", "collectionStartedAt", "collectionFinishedAt", "device", "alarmSummary", "properties"})
public record PersonalEvidenceSnapshot(int schemaVersion, UUID deviceId, UUID modelVersionId,
        Instant collectionStartedAt, Instant collectionFinishedAt, DeviceEvidenceSnapshot.Device device,
        DeviceEvidenceSnapshot.AlarmSummary alarmSummary, List<Property> properties) {
    public PersonalEvidenceSnapshot { properties = List.copyOf(properties); }
    /**
     * 从服务端当前受权证据提取数字/布尔值，其余值明确省略。
     * @param source 当前服务端重新采集且最终确权的证据
     * @return 不携带项目身份或任意文本的历史事实正文
     */
    public static PersonalEvidenceSnapshot from(DeviceEvidenceSnapshot source) {
        return new PersonalEvidenceSnapshot(1, source.deviceId(), source.modelVersionId(),
                source.collectionStartedAt(), source.collectionFinishedAt(), source.device(), source.alarmSummary(),
                source.properties().stream().map(p -> {
                    boolean present = p.availability() == ConsoleDeviceEvidence.Availability.PRESENT;
                    boolean safe = present && p.value() != null && (p.value().isNumber() || p.value().isBoolean());
                    return new Property(p.key(), p.availability(), safe ? p.value().deepCopy() : null,
                            present && !safe, p.occurredAt(), p.sourceModelVersionId(), p.readAt());
                }).toList());
    }
    @Override public String toString() { return "个人历史事实[属性数=" + properties.size() + "]"; }
    /** 原始来源状态与值省略分别表达；文本省略不等于设备未上报。 */
    @Schema(name = "PersonalEvidenceProperty", requiredProperties = {"key", "availability", "value", "valueOmitted", "occurredAt", "sourceModelVersionId", "readAt"})
    public record Property(String key, ConsoleDeviceEvidence.Availability availability,
            @Schema(implementation = Object.class, types = {"number", "boolean", "null"}) JsonNode value,
            boolean valueOmitted, @Schema(types = {"string", "null"}, format = "date-time") Instant occurredAt,
            @Schema(types = {"string", "null"}, format = "uuid") UUID sourceModelVersionId, Instant readAt) {
        @Override public String toString() { return "个人历史属性[" + availability + ",值省略=" + valueOmitted + "]"; }
    }
}
