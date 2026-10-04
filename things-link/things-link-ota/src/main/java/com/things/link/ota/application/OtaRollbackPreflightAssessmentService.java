package com.things.link.ota.application;

import com.things.link.device.application.OtaDeviceCommitPort;
import com.things.link.device.application.OtaDeviceIdentity;
import com.things.link.device.application.OtaDeviceIdentityPort;
import com.things.link.ota.domain.OtaCampaignRuntimeRepository;
import com.things.link.ota.domain.OtaJobProgressRepository;
import com.things.link.ota.domain.OtaRollbackPreflightErrorCode;
import com.things.link.ota.domain.OtaRollbackPreflightQuery;
import com.things.link.ota.domain.OtaRollbackPreflightRepository;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.message.AuthenticatedDeviceIdentity;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 同一真实事务重验当前准备前提，历史PREPARABLE绝不作为执行凭证。 */
@Service
public class OtaRollbackPreflightAssessmentService {
    /** 当前身份与原模型。 */ private final OtaDeviceIdentityPort identities;
    /** 原不可变来源。 */ private final OtaJobProgressRepository progress;
    /** 规范来源恢复。 */ private final OtaExecutionOriginGuard origins;
    /** 当前受控基线交集。 */ private final OtaRollbackBaselineQualification baselines;
    /** 设备公开只读下限。 */ private final OtaDeviceCommitPort floors;
    /** 全部历史报告最高计数。 */ private final OtaRollbackPreflightRepository queries;
    /** 当前数据库裁决时间。 */ private final OtaCampaignRuntimeRepository runtime;
    /** 完整双槽准备规则。 */ private final OtaRollbackPreflightEvaluator evaluator = new OtaRollbackPreflightEvaluator();
    /** 内部快照规范序列化。 */ private final OtaCanonicalJson json = new OtaCanonicalJson();

    /** 调用者先建立真实范围、项目许可及完整控制图。 */
    public OtaRollbackPreflightAssessmentService(OtaDeviceIdentityPort identities, OtaJobProgressRepository progress,
            OtaExecutionOriginGuard origins, OtaRollbackBaselineQualification baselines, OtaDeviceCommitPort floors,
            OtaRollbackPreflightRepository queries, OtaCampaignRuntimeRepository runtime) {
        this.identities=identities; this.progress=progress; this.origins=origins; this.baselines=baselines;
        this.floors=floors; this.queries=queries; this.runtime=runtime;
    }

    /** 当前窗口资格由调用者按接纳或管理读取语义提供，所有其他前提在本事务独立重验。 */
    @Transactional(propagation=Propagation.MANDATORY)
    public Assessment assess(OtaJobProgressRepository.Context job, OtaRollbackPreflightQuery query,
            OtaRollbackPreflightReportCodec.Report report, Instant brokerAt, boolean windowAvailable) {
        Instant decisionAt=runtime.currentTime();
        var identity=new AuthenticatedDeviceIdentity(job.tenantId(),job.projectId(),job.deviceId(),job.credentialVersion());
        OtaDeviceIdentity device=identities.lockCurrent(identity).orElse(null);
        Optional<OtaDeviceCommitPort.SecurityFloor> floor=device==null?Optional.empty():floors.currentFloor(job.tenantId(),job.projectId(),job.deviceId());
        OptionalLong maximum=queries.maxObservedCommitted(job.jobId(),job.attemptNo());
        OtaRollbackPreflightEvaluator.Decision decision;
        if (!windowAvailable || !"RECOVERY_REQUIRED".equals(job.status()) || job.revision()!=query.recoveryRevision()
                || !OtaDeviceReportIngestionService.fresh(brokerAt,decisionAt)) {
            decision=OtaRollbackPreflightEvaluator.denied("QUERY_NOT_CURRENT_OR_FRESH");
        } else if (device==null) {
            decision=OtaRollbackPreflightEvaluator.denied("CURRENT_IDENTITY_CHANGED");
        } else {
            OtaRollbackBaselineQualification.Qualified current=null;
            try { current=baselines.requireCurrent(job.tenantId(),job.projectId(),device.deviceTypeId()); }
            catch (BusinessException unavailable) {
                if (unavailable.errorCode()!=OtaRollbackPreflightErrorCode.UNAVAILABLE) throw unavailable;
            }
            if (current==null || !Arrays.equals(current.extension().canonical(),query.baselineCanonical())
                    || !Arrays.equals(current.parent().canonical(),query.typeBaselineCanonical())) {
                decision=OtaRollbackPreflightEvaluator.denied("CONTROLLED_BASELINE_CHANGED_OR_MISSING");
            } else {
                var origin=progress.origin(job.jobId(),job.attemptNo()).orElseThrow(
                        ()->new IllegalStateException("预检原执行来源丢失"));
                decision=evaluator.evaluate(origins.decode(origin),device,current.parent().value(),current.extension().value(),
                        report,query.permitIds(),floor,maximum);
            }
            if (!identities.credentialValid(identity)) decision=OtaRollbackPreflightEvaluator.denied("CURRENT_IDENTITY_CHANGED");
        }
        Map<String,Object> snapshot=new LinkedHashMap<>();
        snapshot.put("contractVersion","tc-ota-rollback-preflight-assessment/v1");
        snapshot.put("queryId",query.id().toString()); snapshot.put("reportId",report.reportId().toString());
        snapshot.put("disposition",decision.disposition()); snapshot.put("reason",decision.reason());
        snapshot.put("evaluatedAt",decisionAt.toEpochMilli()); snapshot.put("executionAuthorized",false);
        snapshot.put("requiresAtomicCommitFence",true);
        snapshot.put("observedMaximum",maximum.isPresent()?List.of(maximum.getAsLong()):List.of());
        snapshot.put("knownFloor",floor.map(value->List.of(Map.of("committedSecurityVersion",value.committedSecurityVersion(),
                "artifactSha256",value.artifactSha256()))).orElseGet(List::of));
        snapshot.put("currentModel",device==null?List.of():List.of(Map.of("id",device.thingModelVersionId().toString(),
                "digestAlgorithm",device.schemaDigestAlgorithm(),"digest",device.schemaDigest(),"profile",device.schemaProfile())));
        snapshot.put("baselineSha256",OtaTrustBundleCodec.sha256(query.baselineCanonical()));
        snapshot.put("typeBaselineSha256",OtaTrustBundleCodec.sha256(query.typeBaselineCanonical()));
        snapshot.put("operationRevision",report.evidence().journal().operationRevision());
        return new Assessment(decision,json.writeObject(snapshot),decisionAt);
    }

    /** 当次完整判断及规范观察快照，不含执行令牌。 */
    public record Assessment(OtaRollbackPreflightEvaluator.Decision decision, byte[] canonical, Instant evaluatedAt) {
        /** 防止规范判断字节被调用者修改。 */ public Assessment { canonical=canonical.clone(); }
        /** 输出独立副本。 */ @Override public byte[] canonical() { return canonical.clone(); }
    }
}
