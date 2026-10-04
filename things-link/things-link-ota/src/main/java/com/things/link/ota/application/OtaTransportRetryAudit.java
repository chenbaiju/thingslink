package com.things.link.ota.application;

import com.things.link.ota.domain.OtaCampaignRuntimeRepository;
import com.things.link.support.audit.AuditLogEntry;
import com.things.link.support.audit.AuditLogService;
import java.util.Map;
import java.util.UUID;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** ADR0206：覆盖传输SQL内部耗尽分支，在原事务提交前审计业务状态边界。 */
final class OtaTransportRetryAudit implements TransactionSynchronization {
    /** 真实项目锁保护下的原始状态，绝不从物理响应推测业务成功。 */
    private final OtaCampaignRuntimeRepository.JobState before;
    /** 原事务内读取当前作业。 */ private final OtaCampaignRuntimeRepository runtime;
    /** 写失败必须阻止整个事务提交。 */ private final AuditLogService audit;
    /** 固定范围及失败来源，不携带供应商正文。 */
    private final UUID tenant, project, job, source;

    private OtaTransportRetryAudit(OtaCampaignRuntimeRepository runtime, AuditLogService audit,
            UUID tenant, UUID project, UUID job, UUID source, OtaCampaignRuntimeRepository.JobState before) {
        this.runtime = runtime; this.audit = audit; this.tenant = tenant; this.project = project;
        this.job = job; this.source = source; this.before = before;
    }

    /** 在项目控制锁之后、任何可能耗尽的SQL之前登记；重复调用同作业仅保留首个状态。 */
    static void watch(OtaCampaignRuntimeRepository runtime, AuditLogService audit,
            UUID tenant, UUID project, UUID job, UUID source) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            throw new IllegalStateException("OTA失败归类审计需要真实事务");
        }
        boolean registered = TransactionSynchronizationManager.getSynchronizations().stream()
                .anyMatch(sync -> sync instanceof OtaTransportRetryAudit existing
                        && existing.tenant.equals(tenant) && existing.project.equals(project) && existing.job.equals(job));
        if (registered) return;
        runtime.jobState(job).filter(state -> "DISPATCHED".equals(state.status())).ifPresent(state ->
                TransactionSynchronizationManager.registerSynchronization(
                        new OtaTransportRetryAudit(runtime, audit, tenant, project, job, source, state)));
    }

    /** 同一原事务里的新业务归类才形成审计；失败传播到提交调用方并回滚传输观察。 */
    @Override public void beforeCommit(boolean readOnly) {
        var after = runtime.jobState(job).orElseThrow();
        if (after.stateVersion() == before.stateVersion()) return;
        if (!"RETRY_WAIT".equals(after.status()) && !"TIMED_OUT".equals(after.status())) return;
        audit.record(new AuditLogEntry(tenant, project, null, "ota_device_job", job,
                "ota.retry.failure_observed", Map.of("actorKind", "SYSTEM", "sourceId", source.toString(),
                "fromStatus", before.status(), "status", after.status(),
                "attemptNo", Integer.toString(after.attemptNo()),
                "stateVersion", Long.toString(after.stateVersion()))));
    }
}
