package com.things.link.ota.application;

import com.things.link.ota.domain.OtaInstallStopOperation;
import java.util.UUID;

/** 停止日志与安全取消分开：原操作已接纳不等于当前已证明安装前静止。 */
public final class OtaInstallStopEvaluator {
    /** 纯判断器不持有可变状态。 */
    private OtaInstallStopEvaluator() { }

    /** 只与原操作受控快照比较；当前运维配置资格由事务服务另行复验。 */
    public static boolean boundEvidence(OtaInstallStopOperation operation,OtaInstallStopOperationReportCodec.Evidence evidence) {
        var parent=new OtaTypeBaselineCodec().decode(operation.parentBaseline());
        var extension=new OtaInstallStopBaselineCodec().decode(operation.stopBaseline());
        var expected=parent.value().hardware();var actual=evidence.hardware();
        return OtaInstallStopBaselineQualification.matches(parent,extension)
                &&extension.sha256().equals(evidence.stopBaselineSha256())
                &&OtaInstallStopBaselineCodec.PROFILE.equals(evidence.atomicOperationProfile())
                &&expected.model().equals(actual.model())&&actual.boardRevision()>=expected.boardRevisionMin()
                &&actual.boardRevision()<=expected.boardRevisionMax()
                &&OtaRollbackBaselineCodec.compareVersions(evidence.bootloaderVersion(),extension.value().bootloader().minimumVersion())>=0
                &&OtaRollbackBaselineCodec.compareVersions(evidence.bootloaderVersion(),extension.value().bootloader().maximumVersion())<=0;
    }
    /** 未过期的资格不是耐久日志的代替品，原操作ID与完整哈希必须绑定。 */
    public static boolean accepted(OtaInstallStopOperation operation,OtaInstallStopOperationReportCodec.Evidence evidence) {
        if(!boundEvidence(operation,evidence)||evidence.stopOperations().size()!=1) return false;
        var stop=evidence.stopOperations().getFirst();
        return operation.id().equals(stop.operationId())&&operation.payloadHash().equals(stop.operationSha256())
                &&"STOPPED".equals(stop.state())&&stop.acceptedRevision()>=1&&stop.acceptedRevision()<=evidence.journalRevision();
    }
    /** 安装声明只可绑定平台真实已封存的原尝试授权，不相信载荷自报UUID。 */
    public static boolean installed(OtaInstallStopOperation operation,OtaInstallStopOperationReportCodec.Evidence evidence,UUID sealedAuthorization) {
        if(!boundEvidence(operation,evidence)||evidence.installOperations().size()!=1||sealedAuthorization==null) return false;
        var install=evidence.installOperations().getFirst();
        return sealedAuthorization.equals(install.authorizationId())&&"INSTALL_ACCEPTED".equals(install.state())
                &&install.acceptedRevision()>=1&&install.acceptedRevision()<=evidence.journalRevision();
    }
    /** 只有原尝试永久停止、未获安装接纳且写入已静止才能关闭本任务责任。 */
    public static boolean stopped(OtaInstallStopOperation operation,OtaInstallStopOperationReportCodec.Report report) {
        return "STOPPED".equals(report.status())&&accepted(operation,report.evidence())
                &&report.evidence().installOperations().isEmpty()&&"QUIESCENT".equals(report.evidence().writeState());
    }
}
