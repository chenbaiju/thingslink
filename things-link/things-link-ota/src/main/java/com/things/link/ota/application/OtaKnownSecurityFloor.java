package com.things.link.ota.application;

import com.things.link.device.application.OtaDeviceCommitPort;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** 设备真实提交的已知下限独立于会覆盖的普通能力报告。 */
@Component
public final class OtaKnownSecurityFloor {
    /** 本域公开只读端口，要求调用方持有真实RLS事务。 */ private final OtaDeviceCommitPort commits;
    /** 不直接跨域查询设备表。 */ public OtaKnownSecurityFloor(OtaDeviceCommitPort commits) { this.commits = commits; }
    /** 旧报告或等值异固件不能覆盖已经确认的设备安全事实。 */
    boolean accepts(UUID tenant, UUID project, UUID device, OtaDeviceReportCodec.Report report) {
        var floor = commits.currentFloor(tenant, project, device).orElse(null);
        if (floor == null) return true;
        return report.committedSecurityVersion() >= floor.committedSecurityVersion()
                && report.currentSecurityVersion() >= floor.committedSecurityVersion()
                && (report.currentSecurityVersion() != floor.committedSecurityVersion()
                    || report.currentFirmwareSha256().equals(floor.artifactSha256()));
    }
}
