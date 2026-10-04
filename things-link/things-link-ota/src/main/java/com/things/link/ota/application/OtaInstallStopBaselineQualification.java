package com.things.link.ota.application;

import com.things.link.ota.domain.OtaInstallStopErrorCode;
import com.things.link.ota.domain.OtaTypeBaselineErrorCode;
import com.things.link.shared.error.BusinessException;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 在既有真实RLS事务内验证父登记与只读扩展，不创建第二套当前基线登记。 */
@Service
public class OtaInstallStopBaselineQualification {
    /** 当前连接的事务与范围验证。 */ private final JdbcTemplate jdbc;
    /** 已登记父基线、运维配置及权威类型共用校验。 */ private final OtaTypeBaselineService parents;
    /** 当前进程不可变扩展配置。 */ private final OtaInstallStopBaselineSource source;

    /** 不自行创建身份、事务或数据库范围。 */
    public OtaInstallStopBaselineQualification(JdbcTemplate jdbc, OtaTypeBaselineService parents,
            OtaInstallStopBaselineSource source) {
        this.jdbc = jdbc; this.parents = parents; this.source = source;
    }

    /** 必须持有真实事务和当前项目范围；预期资格拒绝交由外层保存，不污染其回滚状态。 */
    public Qualified requireCurrent(UUID tenant, UUID project, UUID type) {
        var scope = OtaRuntimeScope.require(jdbc, tenant, project);
        OtaTypeBaselineCodec.Decoded parent;
        try { parent = parents.lockRuntime(scope, type).decoded(); }
        catch (BusinessException failure) {
            if (failure.errorCode() == OtaTypeBaselineErrorCode.UNAVAILABLE
                    || failure.errorCode() == OtaTypeBaselineErrorCode.INVALID
                    || failure.errorCode() == OtaTypeBaselineErrorCode.NOT_FOUND) throw unavailable();
            throw failure;
        }
        OtaInstallStopBaselineCodec.Decoded extension;
        try { extension = source.require(tenant, project, type); }
        catch (IllegalArgumentException failure) { throw unavailable(); }
        if (!matches(parent, extension)) throw unavailable();
        return new Qualified(parent, extension);
    }

    /** 完整父身份与范围必须一致，扩展只收窄已声明的启动器范围。 */
    static boolean matches(OtaTypeBaselineCodec.Decoded parent, OtaInstallStopBaselineCodec.Decoded extension) {
        var p = parent.value(); var e = extension.value();
        return p.tenantId().equals(e.tenantId()) && p.projectId().equals(e.projectId())
                && p.deviceTypeId().equals(e.deviceTypeId()) && p.productKey().equals(e.productKey())
                && p.baselineVersion() == e.typeBaselineVersion() && parent.sha256().equals(e.typeBaselineSha256())
                && OtaInstallStopBaselineCodec.compareVersions(e.bootloader().minimumVersion(), p.bootloader().minimumVersion()) >= 0
                && OtaInstallStopBaselineCodec.compareVersions(e.bootloader().maximumVersion(), p.bootloader().maximumVersion()) <= 0;
    }
    /** 缺失或过时配置仅返回固定资格不可用错误。 */
    private static BusinessException unavailable() { return new BusinessException(OtaInstallStopErrorCode.UNAVAILABLE); }
    /** 当前两份完整规范快照只供只读准备，不是已停止或硬件验收事实。
     * @param parent 已登记且当前配置完全匹配的原基线
     * @param extension 当前不可变安装前停止扩展
     */
    public record Qualified(OtaTypeBaselineCodec.Decoded parent, OtaInstallStopBaselineCodec.Decoded extension) { }
}
