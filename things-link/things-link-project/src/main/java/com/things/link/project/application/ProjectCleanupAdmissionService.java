package com.things.link.project.application;

import com.things.link.project.domain.ProjectCleanupRepository;
import com.things.link.shared.id.Uuid7;
import com.things.link.support.audit.AuditLogEntry;
import com.things.link.support.audit.AuditLogService;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Connection;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * ADR0076：不可逆项目清理准入与可接管进度的公开端口。
 * 本服务没有调度入口；完整贡献器和最终编排交付前不得自动领取生产项目。
 */
@Service
public class ProjectCleanupAdmissionService {

    /** 所有项目清理SQL仍归project仓储。 */
    private final ProjectCleanupRepository repository;
    /** 准入审计必须与PURGING同事务成功或回滚。 */
    private final AuditLogService audits;
    /** 用于检查实际连接隔离级，不能假设注解替换已有事务。 */
    private final JdbcTemplate jdbc;

    /** @param repository 清理仓储 @param audits 原子审计 @param jdbc 当前事务连接 */
    public ProjectCleanupAdmissionService(ProjectCleanupRepository repository, AuditLogService audits, JdbcTemplate jdbc) {
        this.repository = repository;
        this.audits = audits;
        this.jdbc = jdbc;
    }

    /** @return 一个持久领取身份；首轮准入与系统审计共享五秒事务，失败均回滚 */
    @Transactional(timeout = 5)
    public Optional<ProjectCleanupClaim> claimNext() {
        requireWriteTransaction();
        Optional<ProjectCleanupClaim> claimed = repository.claimNext(Uuid7.generate());
        claimed.filter(ProjectCleanupClaim::newlyAdmitted).ifPresent(claim -> audits.record(new AuditLogEntry(
                claim.tenantId(), claim.projectId(), null, "project", claim.projectId(), "project.cleanup.started",
                Map.of("lifecycleGeneration", claim.generation(), "stage", claim.stage()))));
        return claimed;
    }

    /**
     * 后续批次先持有项目排他锁再配置领域范围，锁持续到调用者原事务结束。
     * @param claim 完整领取身份
     * @return 是否仍有权执行该阶段，false后不得继续删除
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean lockCurrent(ProjectCleanupClaim claim) {
        Objects.requireNonNull(claim, "清理身份不得为空");
        requireWriteTransaction();
        return repository.lockCurrent(claim);
    }

    /** @param claim 当前身份 @param failureCode 稳定失败分类 @return 当前token是否成功释放并进入三十秒退避 */
    @Transactional(timeout = 5)
    public boolean defer(ProjectCleanupClaim claim, String failureCode) {
        Objects.requireNonNull(claim, "清理身份不得为空");
        if (failureCode == null || !failureCode.matches("[A-Z][A-Z0-9_]{0,63}")) {
            throw new IllegalArgumentException("清理失败必须使用有限稳定分类码");
        }
        requireWriteTransaction();
        return repository.defer(claim, failureCode);
    }

    /** 锁后重新读取依赖RC快照；不接受只读、无事务或RR/SERIALIZABLE旧快照。 */
    private void requireWriteTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("项目清理必须运行在非只读事务中");
        }
        Integer isolation = jdbc.execute((ConnectionCallback<Integer>) Connection::getTransactionIsolation);
        if (!Objects.equals(isolation, Connection.TRANSACTION_READ_COMMITTED)
                && !Objects.equals(isolation, Connection.TRANSACTION_READ_UNCOMMITTED)) {
            throw new IllegalStateException("项目清理只支持READ COMMITTED事务");
        }
    }
}
