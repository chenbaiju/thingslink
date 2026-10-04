package com.things.link.ota.application;

import com.things.link.ota.domain.OtaInstallStopRepository;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** 已锁原尝试内的耐久停止围栏，当前作业阶段不替代设备接纳事实。 */
@Component
public final class OtaInstallStopObservationGuard {
    /** 同一RLS事务的真实停止方向。 */ private final OtaInstallStopRepository repository;
    /** 不创建新的逻辑操作。 */
    public OtaInstallStopObservationGuard(OtaInstallStopRepository repository) { this.repository = repository; }
    /** 已接受停止或冲突只能保留旧协议观察，不能恢复安装或采用模型。 */
    public boolean onlyObserve(UUID job, int attempt) {
        var operation = repository.findForJob(job, attempt).orElse(null);
        if (operation == null) return false;
        var control = repository.control(operation.id()).orElseThrow(() -> new IllegalStateException("安装停止方向事实缺失"));
        return control.acceptedReportId() != null || control.conflictedAt() != null;
    }
}
