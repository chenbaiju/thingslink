package com.things.link.ota.application;

import com.things.link.device.application.OtaDeviceCommitPort;
import com.things.link.device.application.OtaDeviceIdentity;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/** ADR0134只判断后续原子准备的必要前提，永不授予切槽或反回滚例外。 */
public final class OtaRollbackPreflightEvaluator {
    /** 完整原来源、当前设备与受控能力共同限制报告，拒绝把双槽声明推导成互斥已经发生。 */
    public Decision evaluate(OtaDeviceReportCodec.Report source, OtaDeviceIdentity current,
            OtaTypeBaselineCodec.Baseline parent, OtaRollbackBaselineCodec.Baseline extension,
            OtaRollbackPreflightReportCodec.Report report, List<UUID> permits,
            Optional<OtaDeviceCommitPort.SecurityFloor> floor, OptionalLong maximumObserved) {
        if (!OtaDeviceReportIngestionService.matchesModel(current, source)) return denied("CURRENT_MODEL_CHANGED");
        if (!source.supportsAbSlots() || !parent.supportsAbSlots() || !List.of("A", "B").contains(source.activeSlot()))
            return denied("SOURCE_AB_CAPABILITY_MISSING");
        var evidence = report.evidence();
        var hardware = evidence.hardware();
        if (!hardware.model().equals(source.hardware().model()) || hardware.boardRevision() != source.hardware().boardRevision()
                || !hardware.model().equals(parent.hardware().model()) || hardware.boardRevision() < parent.hardware().boardRevisionMin()
                || hardware.boardRevision() > parent.hardware().boardRevisionMax()) return denied("HARDWARE_MISMATCH");
        if (OtaRollbackBaselineCodec.compareVersions(report.bootloaderVersion(), extension.bootloader().minimumVersion()) < 0
                || OtaRollbackBaselineCodec.compareVersions(report.bootloaderVersion(), extension.bootloader().maximumVersion()) > 0)
            return denied("BOOTLOADER_OUTSIDE_CONTROLLED_PROFILE");
        if (report.committedSecurityVersion() != source.committedSecurityVersion()
                || maximumObserved.isPresent() && maximumObserved.getAsLong() > source.committedSecurityVersion())
            return denied("COMMITTED_COUNTER_CHANGED");
        if (floor.isPresent()) {
            var known = floor.orElseThrow();
            if (known.committedSecurityVersion() > report.committedSecurityVersion()
                    || known.committedSecurityVersion() == source.currentSecurityVersion()
                    && !known.artifactSha256().equals(source.currentFirmwareSha256())) return denied("KNOWN_SECURITY_FLOOR_CONFLICT");
        }
        var original = evidence.slots().stream().filter(slot -> slot.slot().equals(source.activeSlot())).findFirst().orElse(null);
        if (original == null || !original.artifactSha256().equals(source.currentFirmwareSha256())
                || original.securityVersion() != source.currentSecurityVersion()
                || original.securityVersion() < report.committedSecurityVersion()
                || !original.thingModelVersionId().equals(source.thingModelVersionId())
                || !original.thingModelSchemaDigestAlgorithm().equals(source.thingModelSchemaDigestAlgorithm())
                || !original.thingModelSchemaDigest().equals(source.thingModelSchemaDigest())
                || !original.propertyProfile().equals(source.propertyProfile())) return denied("SOURCE_SLOT_TUPLE_MISMATCH");
        if ("UNKNOWN".equals(original.integrity()) || "UNKNOWN".equals(original.health())) return unknown("SOURCE_SLOT_UNCERTAIN");
        if (!"VERIFIED".equals(original.integrity()) || !original.bootable() || !original.bootloaderVerified()
                || !"HEALTHY".equals(original.health())) return denied("SOURCE_SLOT_NOT_BOOTABLE_AND_HEALTHY");
        if ("UNKNOWN".equals(evidence.writeState())) return unknown("WRITER_STATE_UNKNOWN");
        if (!"QUIESCENT".equals(evidence.writeState())) return denied("WRITER_NOT_QUIESCENT");
        var journal = evidence.journal();
        if (!journal.atomicOperationProfile().equals(extension.atomicOperationProfile())) return denied("ATOMIC_PROFILE_MISMATCH");
        if ("UNKNOWN".equals(journal.state())) return unknown("JOURNAL_STATE_UNKNOWN");
        if (!"IDLE".equals(journal.state()) || !journal.rollbackOperationIds().isEmpty()) return denied("JOURNAL_HAS_ACTIVE_OPERATION");
        var knownPermits = journal.commitOperations().stream().map(OtaRollbackPreflightReportCodec.CommitOperation::permitId).toList();
        if (!knownPermits.equals(permits)) return denied("COMMIT_JOURNAL_PERMIT_MISMATCH");
        for (var operation : journal.commitOperations()) {
            if ("UNKNOWN".equals(operation.state())) return unknown("COMMIT_OPERATION_UNKNOWN");
            if (!List.of("NOT_ACCEPTED", "FENCED").contains(operation.state())) return denied("COMMIT_OPERATION_NOT_SAFE_TO_PREPARE");
        }
        return new Decision("PREPARABLE", "ATOMIC_FENCE_STILL_REQUIRED");
    }

    /** 明确不合格保留稳定原因，不变成技术重试。 */
    static Decision denied(String reason) { return new Decision("INELIGIBLE", reason); }
    /** 明确未知不能以布尔默认值放行。 */
    private static Decision unknown(String reason) { return new Decision("UNKNOWN", reason); }
    /** 当次观察判定，没有执行凭证或槽位切换能力。 */
    public record Decision(String disposition, String reason) { }
}
