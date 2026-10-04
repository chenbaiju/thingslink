package com.things.link.ota.application;

import com.things.link.ota.domain.OtaDeviceReportRepository;
import com.things.link.ota.domain.OtaJobExecutionOrigin;
import com.things.link.ota.domain.OtaJobProgressRepository;
import java.util.Arrays;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** 当前资格不能替代原执行来源；仅在调用方已锁完整资格的事务内使用。 */
@Component
public class OtaExecutionOriginGuard {
    /** 不可变job来源。 */ private final OtaJobProgressRepository progress;
    /** 同事务当前报告头。 */ private final OtaDeviceReportRepository reports;
    /** 完整报告合同。 */ private final OtaDeviceReportCodec codec = new OtaDeviceReportCodec();

    /** 显式依赖两个OTA领域事实，不跨域读取设备表。 */
    public OtaExecutionOriginGuard(OtaJobProgressRepository progress, OtaDeviceReportRepository reports) {
        this.progress = progress; this.reports = reports;
    }
    /** 返回安全拒绝原因；旧来源缺失不可恢复为当前报告，持久损坏保持内部异常。 */
    String rejection(UUID project, UUID job, int attempt, UUID device, long credential,
            long currentRevision, String currentHash) {
        var origin = progress.origin(job, attempt).orElse(null);
        if (origin == null) return "EXECUTION_ORIGIN_MISSING";
        if (!origin.projectId().equals(project) || !origin.deviceId().equals(device)
                || origin.credentialVersion() != credential) return "EXECUTION_SOURCE_CHANGED";
        var current = reports.find(project, device, false, true)
                .orElseThrow(() -> new IllegalStateException("已通过资格的当前报告丢失"));
        if (current.revision() != currentRevision || !current.reportHash().equals(currentHash)
                || current.credentialVersion() != credential) {
            throw new IllegalStateException("持锁来源复核与当前资格不一致");
        }
        var source = decode(origin);
        OtaDeviceReportCodec.Decoded actual;
        try {
            actual = codec.decode(current.canonical());
            if (!actual.sha256().equals(currentHash) || !Arrays.equals(actual.canonical(), current.canonical())) {
                throw new IllegalArgumentException("当前报告摘要不一致");
            }
        } catch (IllegalArgumentException failure) {
            throw new IllegalStateException("当前OTA报告不完整", failure);
        }
        return OtaJobProgressEvidenceValidator.sameSource(source, actual.value()) ? null : "EXECUTION_SOURCE_CHANGED";
    }
    /** 验证持久来源自身完整性；不会吞掉基础设施或存储损坏异常。 */
    OtaDeviceReportCodec.Report decode(OtaJobExecutionOrigin origin) {
        try {
            var decoded = codec.decode(origin.canonical());
            if (!decoded.sha256().equals(origin.reportHash()) || !Arrays.equals(decoded.canonical(), origin.canonical())
                    || decoded.value().reportSequence() != origin.reportSequence()) {
                throw new IllegalArgumentException("原来源摘要或序号不一致");
            }
            return decoded.value();
        } catch (IllegalArgumentException failure) {
            throw new IllegalStateException("OTA原执行来源不完整", failure);
        }
    }
}
