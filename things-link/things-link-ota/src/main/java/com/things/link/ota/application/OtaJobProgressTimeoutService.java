package com.things.link.ota.application;

import com.things.link.ota.domain.OtaCampaignRuntimeRepository;
import com.things.link.ota.domain.OtaJobProgressRepository;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.support.audit.AuditLogEntry;
import com.things.link.support.audit.AuditLogService;
import com.things.link.support.tenant.DataPlaneDatabase;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 平台到期只保留未知责任，缺少未安装证明时不能自动重试或宣布失败终态。 */
@Service
@DataPlaneDatabase
public class OtaJobProgressTimeoutService {
    /** 独立到期能力，不能借用PENDING派发租约。 */ private final OtaJobProgressRepository repository;
    /** 项目控制锁与完整运行图。 */ private final OtaCampaignRuntimeRepository runtime;
    /** 真实项目生命周期许可。 */ private final ProjectLifecycleAccessService lifecycle;
    /** 绑定实际事务连接的范围。 */ private final TransactionLocalRlsScope rls;
    /** 超时状态采用与系统审计原子提交。 */ private final AuditLogService audit;

    /** 独立事务代理供轻量调度器调用。 */
    public OtaJobProgressTimeoutService(OtaJobProgressRepository repository, OtaCampaignRuntimeRepository runtime,
            ProjectLifecycleAccessService lifecycle, TransactionLocalRlsScope rls, AuditLogService audit) {
        this.repository = repository; this.runtime = runtime; this.lifecycle = lifecycle;
        this.rls = rls; this.audit = audit;
    }
    /** 每轮最多一个真实数据库到期作业，不继承管理身份。 */
    @Transactional(timeout = 5)
    public Optional<OtaJobProgressRepository.ExpiryClaim> claimOne() {
        requireBackground();
        return repository.claimExpired();
    }
    /** 再验租约、原期限与当前修订；先完成的进度使旧超时不能覆盖新阶段。 */
    @Transactional(timeout = 5)
    public boolean expire(UUID jobId, UUID token) {
        requireBackground();
        if (jobId == null || token == null) return false;
        var claim = repository.authoritativeExpiry(jobId, token).orElse(null);
        if (claim == null) return false;
        var context = claim.context();
        rls.establish(context.tenantId(), context.projectId());
        if (!lifecycle.lockActiveForWrite(context.tenantId(), context.projectId())) return false;
        runtime.controlLock(context.tenantId(), context.projectId());
        if (runtime.lockRuntime(context.projectId(), context.campaignId()).isEmpty()
                || repository.authoritativeExpiry(jobId, token).filter(claim::equals).isEmpty()) return false;
        String reason = repository.origin(jobId, context.attemptNo()).isEmpty()
                ? "EXECUTION_ORIGIN_MISSING" : "STAGE_DEADLINE_EXCEEDED";
        if (!repository.expire(claim, reason)) return false;
        audit.record(new AuditLogEntry(context.tenantId(), context.projectId(), null, "ota_device_job", jobId,
                "ota.job.timeout", Map.of("actorKind", "SYSTEM", "stage", context.status(),
                        "attemptNo", context.attemptNo(), "reason", reason)));
        return true;
    }
    /** 后台不能借用某个真实或虚构管理账号的权限。 */
    private static void requireBackground() {
        if (TenantContext.current().isPresent()) throw new IllegalStateException("OTA到期处理不能继承管理账号");
    }
}
