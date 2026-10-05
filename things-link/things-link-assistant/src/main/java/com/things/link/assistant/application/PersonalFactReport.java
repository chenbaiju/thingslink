package com.things.link.assistant.application;

import io.swagger.v3.oas.annotations.media.Schema;

/** 本人历史事实的可重建报告视图，保留与删除完全跟随来源记录。 */
@Schema(requiredProperties={"schemaVersion","mode","sourceRecord","contentSha256","markdown"})
public record PersonalFactReport(@Schema(minimum="1",maximum="1") int schemaVersion,
        @Schema(allowableValues={"FACTS_ONLY"}) String mode, PersonalEvidenceRecordView sourceRecord,
        @Schema(pattern="[0-9a-f]{64}") String contentSha256,
        @Schema(description="确定性汇编的历史事实纯文本；必须转义展示，不是模型诊断",maxLength=131072) String markdown) {
    @Override public String toString() { return "个人历史事实报告[无模型诊断]"; }
}
