package com.things.link.assistant.application;
import com.things.link.assistant.domain.PersonalEvidenceRecord;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
/** 个人记录外部视图，不返回租户、创建者及内部存储正文。 */
@Schema(requiredProperties = {"id", "deviceId", "modelVersionId", "createdAt", "expiresAt", "contentSha256"})
public record PersonalEvidenceRecordView(UUID id, UUID deviceId, UUID modelVersionId,
        Instant createdAt, Instant expiresAt, String contentSha256) {
    public static PersonalEvidenceRecordView from(PersonalEvidenceRecord record) {
        return new PersonalEvidenceRecordView(record.id(),record.deviceId(),record.modelVersionId(),record.createdAt(),record.expiresAt(),record.contentSha256());
    }
    /** 单条详情附带完整已存历史事实，列表仅用上层元数据。 */
    @Schema(name = "PersonalEvidenceRecordDetail", requiredProperties = {"record", "snapshot"})
    public record Detail(PersonalEvidenceRecordView record, PersonalEvidenceSnapshot snapshot) {
        @Override public String toString() { return "个人事实详情[历史快照]"; }
    }
}
