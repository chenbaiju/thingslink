package com.things.link.ota.api;

import com.things.link.ota.application.OtaRollbackPreflightReadService;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/** 历史与当前资格分离，不返回原始槽摘要、规范证据或租约。
 * @param queryId 当前查询
 * @param reportId 已认证报告
 * @param observedDisposition 当时分类
 * @param observedReason 当时原因
 * @param currentDisposition 当前分类
 * @param currentReason 当前原因
 * @param observedAt 原接纳时刻
 * @param queryExpiresAt 原查询截止
 * @param checkedAt 当前重验时刻
 * @param operationRevision 设备日志修订字符串
 * @param sourceSlot 原来源槽
 * @param targetSlot 原候选槽
 * @param executionAuthorized 始终无执行权
 * @param requiresAtomicCommitFence 始终需要未来原子互斥
 */
@Schema(name="OtaRollbackPreflightResponse",additionalProperties=Schema.AdditionalPropertiesValue.FALSE,
        requiredProperties={"queryId","reportId","observedDisposition","observedReason","currentDisposition",
                "currentReason","observedAt","queryExpiresAt","checkedAt","operationRevision","sourceSlot","targetSlot",
                "executionAuthorized","requiresAtomicCommitFence"})
public record OtaRollbackPreflightResponse(UUID queryId,UUID reportId,
        @Schema(allowableValues={"PREPARABLE","INELIGIBLE","UNKNOWN"}) String observedDisposition,String observedReason,
        @Schema(allowableValues={"PREPARABLE","INELIGIBLE","UNKNOWN"}) String currentDisposition,String currentReason,
        Instant observedAt,Instant queryExpiresAt,Instant checkedAt,
        @Schema(pattern="^(0|[1-9][0-9]{0,15})$") String operationRevision,
        @Schema(allowableValues={"A","B"}) String sourceSlot,@Schema(allowableValues={"A","B"}) String targetSlot,
        boolean executionAuthorized,boolean requiresAtomicCommitFence) {
    /** 只投影稳定观察字段，任何准备分类都不会授予执行。 */
    public static OtaRollbackPreflightResponse from(OtaRollbackPreflightReadService.Snapshot snapshot){
        var query=snapshot.query();var observed=snapshot.observed();var current=snapshot.current();
        return new OtaRollbackPreflightResponse(query.id(),observed.receipt().reportId(),observed.disposition(),observed.reason(),
                current.decision().disposition(),current.decision().reason(),observed.receipt().acceptedAt(),
                Instant.ofEpochSecond(query.deadlineAt().getEpochSecond()),current.evaluatedAt(),
                Long.toString(snapshot.report().evidence().journal().operationRevision()),query.sourceSlot(),query.targetSlot(),false,true);
    }
}
