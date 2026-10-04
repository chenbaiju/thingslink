package com.things.link.project.application;

import com.things.link.project.domain.DeletedProject;
import com.things.link.project.domain.ProjectErrorCode;
import com.things.link.project.domain.ProjectMembership;
import com.things.link.project.domain.ProjectRepository;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.support.audit.AuditLogEntry;
import com.things.link.support.audit.AuditLogService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ADR0073：项目回收站与恢复用例。
 *
 * <p>恢复查询刻意独立于普通项目授权：只有本服务可以观察 {@code DELETING}，
 * 现有列表、角色和写许可继续把删除项目当作不存在。恢复事务在锁前隐藏项目存在性，
 * 持项目排他锁后再次核验OWNER与账号状态，再原子恢复并写不可篡改审计。</p>
 */
@Service
public class ProjectRecoveryService {

    /** 保留项目、成员事实及恢复排他锁均由project仓储提供。 */
    private final ProjectRepository projectRepository;
    /** 账号状态属于iam；依赖反转端口避免project反向依赖iam实现。 */
    private final AccountDirectory accountDirectory;
    /** 恢复与审计必须参加同一事务，任一失败都不能留下单边事实。 */
    private final AuditLogService auditLogService;

    /**
     * 创建项目恢复服务。
     * @param projectRepository 项目与保留成员仓储
     * @param accountDirectory 账号有效性目录
     * @param auditLogService 不可篡改审计写入服务
     */
    public ProjectRecoveryService(
            ProjectRepository projectRepository,
            AccountDirectory accountDirectory,
            AuditLogService auditLogService) {
        this.projectRepository = projectRepository;
        this.accountDirectory = accountDirectory;
        this.auditLogService = auditLogService;
    }

    /**
     * 列出当前账号仍以ACTIVE OWNER身份持有的全部删除项目，包括已经超过恢复期限的保留事实。
     * @return 回收站项目，按删除时刻倒序排列
     */
    @Transactional(readOnly = true)
    public List<DeletedProject> listRecycleBin() {
        return projectRepository.findDeletedOwnedBy(currentScope().accountId());
    }

    /**
     * 在三十天窗口内恢复项目，保留删除时已经递增的生命周期代次。
     * @param projectId 待恢复项目
     * @return 恢复后的项目及当前OWNER角色
     * @throws BusinessException 不可见项目统一50001，已证明OWNER但超期为50018
     */
    @Transactional
    public ProjectMembership restore(UUID projectId) {
        TenantScope scope = currentScope();
        UUID accountId = scope.accountId();

        // 锁前只做统一布尔预检，陌生账号不能凭锁等待或错误分类探测删除项目存在性。
        requireRetainedOwner(projectId, accountId);
        DeletedProject deletedProject = projectRepository.lockDeletedForRecovery(projectId)
                .orElseThrow(() -> new BusinessException(ProjectErrorCode.PROJECT_NOT_FOUND));

        // 等锁期间OWNER可能被历史修复或旁路事务改变；必须在RC新快照中重新授权。
        requireRetainedOwner(projectId, accountId);
        if (!accountDirectory.isActive(accountId)) {
            throw new BusinessException(ProjectErrorCode.PROJECT_NOT_FOUND);
        }

        // clock_timestamp由仓储在条件UPDATE内单次取得，锁等待跨过截止也不能复用事务起点时间。
        if (projectRepository.restoreWithinWindow(projectId) != 1) {
            throw new BusinessException(ProjectErrorCode.PROJECT_RESTORE_EXPIRED);
        }

        ProjectMembership restored = projectRepository.findMembership(projectId, accountId)
                .orElseThrow(() -> new BusinessException(ProjectErrorCode.PROJECT_NOT_FOUND));
        auditLogService.record(new AuditLogEntry(
                deletedProject.project().tenantId(),
                projectId,
                accountId,
                "project",
                projectId,
                "project.restored",
                Map.of(
                        "lifecycleGeneration", deletedProject.project().lifecycleGeneration(),
                        "deletedAt", deletedProject.deletedAt().toString())));
        return restored;
    }

    /** 统一保留OWNER拒绝为50001，不能向调用者区分项目、成员、角色或状态。 */
    private void requireRetainedOwner(UUID projectId, UUID accountId) {
        if (!projectRepository.isRetainedActiveOwner(projectId, accountId)) {
            throw new BusinessException(ProjectErrorCode.PROJECT_NOT_FOUND);
        }
    }

    /**
     * 恢复允许使用未选择项目的账号令牌，但仍必须具有认证过滤器建立的账号与租户范围。
     * @return 当前控制台账号范围
     */
    private static TenantScope currentScope() {
        return TenantContext.current().orElseThrow(() -> new IllegalStateException(
                "没有租户上下文。项目恢复接口必须在已认证请求中调用"));
    }
}
