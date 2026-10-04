package com.things.link.ota.application;

import com.things.link.ota.application.OtaRollbackOperationCodec.Operation;
import com.things.link.ota.application.OtaRollbackOperationCodec.Source;
import com.things.link.ota.application.OtaRollbackOperationReportCodec.Evidence;
import com.things.link.ota.application.OtaRollbackPreflightReportCodec.Report;
import com.things.link.ota.application.OtaRollbackPreflightReportCodec.Slot;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** 固定原子回退证据规则；认证、当前下限、数据库期限和方向CAS由应用事务独立核验。 */
public final class OtaRollbackExecutionEvaluator {
    /** 不允许实例化无状态纯规则。 */
    private OtaRollbackExecutionEvaluator() { }

    /** 当前候选必须精确签名、已经验证启动且明确应用不健康，不能以未知替代。 */
    public static boolean candidateUnhealthy(Report report, byte[] canonicalManifest, String targetSlot) {
        if (!targetSlot.equals(report.activeSlot())) return false;
        var candidate=slot(report.evidence().slots(),targetSlot);
        return candidate!=null && targetMatches(candidate,manifest(canonicalManifest))
                && verified(candidate) && "UNHEALTHY".equals(candidate.health());
    }

    /** 耐久接纳日志必须精确同一操作且仅增加一次逻辑互斥修订。 */
    public static boolean acceptedJournal(Operation op,String operationHash,Evidence e) {
        var journal=e.journal();
        if (!OtaRollbackBaselineCodec.PROFILE.equals(journal.atomicOperationProfile())
                || op.expectedOperationRevision()>=9_007_199_254_740_991L
                || journal.operationRevision()!=op.expectedOperationRevision()+1
                || journal.rollbackOperations().size()!=1) return false;
        var entry=journal.rollbackOperations().getFirst();
        return op.operationId().equals(entry.operationId()) && operationHash.equals(entry.operationSha256())
                && op.expectedBootId().equals(entry.acceptedBootId())
                && entry.acceptedRevision()==op.expectedOperationRevision()+1
                && List.of("ACCEPTED","ROLLING_BACK","ROLLED_BACK").contains(entry.state());
    }

    /** null仅代表完整静态证据匹配，不授予当前数据库动作资格。 */
    public static String proofMismatch(Operation op,String operationHash,UUID currentBoot,String status,Evidence e,
            Report originalPreflight,byte[] canonicalManifest) {
        if ("REFUSED".equals(status)||"UNKNOWN".equals(status)) return "ROLLBACK_RESULT_NOT_CONFIRMED";
        if (!List.of("ACCEPTED","ROLLING_BACK","ROLLED_BACK","COMMIT_WON").contains(status)) return "ROLLBACK_STATUS_INVALID";
        if (!Objects.equals(e.hardware(),originalPreflight.evidence().hardware())
                || !e.bootloaderVersion().equals(originalPreflight.bootloaderVersion())) return "ROLLBACK_PLATFORM_TUPLE_CHANGED";
        var original=slot(e.slots(),op.source().slot());
        if (original==null || !sourceMatches(original,op.source())) return "ROLLBACK_SOURCE_TUPLE_MISMATCH";
        if (!OtaRollbackBaselineCodec.PROFILE.equals(e.journal().atomicOperationProfile())
                || op.expectedOperationRevision()>=9_007_199_254_740_991L
                || e.journal().operationRevision()!=op.expectedOperationRevision()+1) return "ROLLBACK_JOURNAL_REVISION_MISMATCH";
        if ("COMMIT_WON".equals(status)) {
            var target=slot(e.slots(),op.targetSlot());var manifest=manifest(canonicalManifest);
            if (op.permitIds().isEmpty() || !permits(op,e,"COMMITTED") || !e.journal().rollbackOperations().isEmpty()) return "COMMIT_WINNER_JOURNAL_MISMATCH";
            if (!op.targetSlot().equals(e.activeSlot()) || target==null || !targetMatches(target,manifest)
                    || !verified(target) || !"HEALTHY".equals(target.health()) || !"QUIESCENT".equals(e.writeState())
                    || e.committedSecurityVersion()!=(Long)manifest.get("securityVersion")) return "COMMIT_WINNER_TARGET_MISMATCH";
            return null;
        }
        if (!acceptedJournal(op,operationHash,e)) return "ROLLBACK_ACCEPTED_JOURNAL_MISMATCH";
        if (!status.equals(e.journal().rollbackOperations().getFirst().state())) return "ROLLBACK_JOURNAL_STAGE_MISMATCH";
        if (!permits(op,e,"FENCED")) return "ROLLBACK_COMMIT_FENCE_MISSING";
        if (!verified(original) || !"HEALTHY".equals(original.health())
                || original.securityVersion()<e.committedSecurityVersion()) return "ROLLBACK_SOURCE_UNSAFE";
        if (e.committedSecurityVersion()!=op.expectedCommittedSecurityVersion()) return "ROLLBACK_COMMITTED_COUNTER_CHANGED";
        if ("ROLLED_BACK".equals(status)) {
            if (currentBoot==null || currentBoot.equals(op.expectedBootId())) return "ROLLBACK_NEW_BOOT_MISSING";
            if (!op.source().slot().equals(e.activeSlot()) || !"QUIESCENT".equals(e.writeState())) return "ROLLBACK_SOURCE_NOT_RUNNING";
        } else if (!List.of(op.source().slot(),op.targetSlot()).contains(e.activeSlot())) return "ROLLBACK_ACTIVE_SLOT_INVALID";
        return null;
    }

    /** 原许可集合完整有序匹配，不从空集合推导设备全局没有其他事务。 */
    private static boolean permits(Operation op,Evidence e,String state) {
        var entries=e.journal().commitOperations();
        return entries.stream().map(OtaRollbackPreflightReportCodec.CommitOperation::permitId).toList().equals(op.permitIds())
                && entries.stream().allMatch(entry->state.equals(entry.state()));
    }
    /** 已验证且可启动，不把未知完整性解释为真。 */
    private static boolean verified(Slot slot) { return "VERIFIED".equals(slot.integrity())&&slot.bootable()&&slot.bootloaderVerified(); }
    /** 严格codec保证唯一槽位；纯规则依旧不选任意缺失槽。 */
    private static Slot slot(List<Slot> slots,String name) { return slots.stream().filter(value->name.equals(value.slot())).findFirst().orElse(null); }
    /** 完整原来源身份，不仅比较摘要或模型UUID。 */
    private static boolean sourceMatches(Slot slot,Source source) {
        return slot.slot().equals(source.slot())&&slot.artifactSha256().equals(source.artifactSha256())
                &&slot.securityVersion()==source.securityVersion()&&slot.thingModelVersionId().equals(source.thingModelVersionId())
                &&slot.thingModelSchemaDigestAlgorithm().equals(source.thingModelSchemaDigestAlgorithm())
                &&slot.thingModelSchemaDigest().equals(source.thingModelSchemaDigest())&&slot.propertyProfile().equals(source.propertyProfile());
    }
    /** 候选身份严格等于签名manifest六轴，不从报告推导目标。 */
    private static boolean targetMatches(Slot slot,Map<String,Object> manifest) {
        return slot.artifactSha256().equals(manifest.get("artifactSha256"))&&slot.securityVersion()==(Long)manifest.get("securityVersion")
                &&slot.thingModelVersionId().toString().equals(manifest.get("thingModelVersionId"))
                &&slot.thingModelSchemaDigestAlgorithm().equals(manifest.get("thingModelSchemaDigestAlgorithm"))
                &&slot.thingModelSchemaDigest().equals(manifest.get("thingModelSchemaDigest"))
                &&slot.propertyProfile().equals(OtaTrustBundleCodec.object(manifest.get("requirements")).get("profile"));
    }
    /** 已持久签名manifest解析错误是平台事实损坏，不伪装成普通健康失败。 */
    private static Map<String,Object> manifest(byte[] canonical) {
        try {
            if(!Arrays.equals(new OtaManifestCodec().canonicalize(canonical),canonical))
                throw new IllegalArgumentException("持久清单不是规范字节");
            return new OtaCanonicalJson().parseObject(canonical);
        } catch(IllegalArgumentException failure) {
            throw new IllegalStateException("回退引用的签名清单不完整",failure);
        }
    }
}
