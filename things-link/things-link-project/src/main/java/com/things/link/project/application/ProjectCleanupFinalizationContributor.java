package com.things.link.project.application;

import com.things.link.project.domain.ProjectCleanupRepository;
import com.things.link.support.audit.AuditLogEntry;
import com.things.link.support.audit.AuditLogService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/** ADR0091：完成审计先写、真实时钟围栏最后封存；两者只能同时提交或回滚。 */
@Component
public class ProjectCleanupFinalizationContributor implements ProjectCleanupContributor {
    /** 持有同一项目排他锁的能力端口。 */
    private final ProjectCleanupAdmissionService admission;
    /** 只封存project墓碑，不跨域扫描或删除。 */
    private final ProjectCleanupRepository repository;
    /** 与最终条件更新同一物理事务。 */
    private final AuditLogService audits;

    /** @param admission 锁及完整身份 @param repository 末序持久事实 @param audits 系统审计 */
    public ProjectCleanupFinalizationContributor(ProjectCleanupAdmissionService admission,
            ProjectCleanupRepository repository, AuditLogService audits) {
        this.admission = admission;
        this.repository = repository;
        this.audits = audits;
    }

    /** {@inheritDoc} */
    @Override public ProjectCleanupStage stage() { return ProjectCleanupStage.FINALIZE; }

    /** {@inheritDoc} */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ProjectCleanupBatchResult clean(ProjectCleanupClaim claim) {
        if (!stage().name().equals(claim.stage()) || !admission.lockCurrent(claim)) {
            throw new IllegalStateException("项目墓碑只接受当前FINALIZE完整身份");
        }
        if (repository.hasMembers(claim)) return ProjectCleanupBatchResult.blocked("PROJECT_MEMBERS_REMAIN");
        audits.record(new AuditLogEntry(claim.tenantId(), claim.projectId(), null, "project", claim.projectId(),
                "project.cleanup.completed", Map.of("lifecycleGeneration", claim.generation(), "stage", "DONE")));
        // 最后一次实际时钟判断位于审计之后，审计耗尽租约不能留下成功墓碑或伪完成日志。
        if (!repository.finalizeProject(claim)) throw new IllegalStateException("项目墓碑提交前租约失效");
        return ProjectCleanupBatchResult.done();
    }
}
