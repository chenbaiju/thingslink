package com.things.link.project.application;

import com.things.link.project.domain.Project;
import com.things.link.project.domain.ProjectErrorCode;
import com.things.link.project.domain.ProjectRepository;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.error.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;
import java.util.function.Predicate;

/**
 * ADR0064决策2/5：项目和成员变更先预检授权，再持项目排他锁重新核验角色与状态。
 * 只加入调用者原非只读事务，不读取JWT租户，避免误拒跨租户协作者或提前释放行锁。
 * 仓储核验原连接仅支持READ COMMITTED或PG等价的READ UNCOMMITTED；RR/Serializable不能承诺锁后成员新快照，拒绝而不降级外层事务。
 */
@Service
public class ProjectManagementWriteGuard {

    /** 项目域权威成员事实与排他锁；调用方不能用锁前角色或缓存代替锁后查询。 */
    private final ProjectRepository repository;

    /** @param repository 原事务中读取项目和成员事实的仓储 */
    public ProjectManagementWriteGuard(ProjectRepository repository) {
        this.repository = repository;
    }

    /**
     * 项目改名、删除或转让要求当前OWNER；持锁后重新核验防止等待期间所有权转移。
     * @param projectId 请求中显式项目身份
     * @param accountId 已认证控制台账号，不从租户归属推导OWNER
     * @return 锁后当前OWNER角色
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public ProjectRole requireOwner(UUID projectId, UUID accountId) {
        return requireWritable(projectId, accountId, role -> role == ProjectRole.OWNER,
                ProjectErrorCode.PROJECT_OWNER_REQUIRED);
    }

    /**
     * 成员管理沿共享角色规则，避免与原成员服务和IAM权限集合分叉。
     * @param projectId 请求中显式项目身份
     * @param accountId 已认证控制台账号
     * @return 锁后当前有权管理成员的角色
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public ProjectRole requireMemberManager(UUID projectId, UUID accountId) {
        return requireWritable(projectId, accountId, ProjectRole::canManageMembers,
                ProjectErrorCode.MEMBER_MANAGEMENT_FORBIDDEN);
    }

    /**
     * 主动退出等操作仅要求有效成员；OWNER不能退出等操作专属限制仍由调用方判断。
     * @param projectId 请求中显式项目身份
     * @param accountId 已认证控制台账号
     * @return 锁后当前成员角色，不能返回锁前快照
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public ProjectRole requireMember(UUID projectId, UUID accountId) {
        return requireWritable(projectId, accountId, role -> true, ProjectErrorCode.PROJECT_NOT_FOUND);
    }

    /** 持锁之前只做授权预检；仓储确认实际RC/RU连接后，变更许可依赖锁后角色与状态，SQL故障不转为业务拒绝。 */
    private ProjectRole requireWritable(UUID projectId, UUID accountId, Predicate<ProjectRole> allowed,
                                        ProjectErrorCode forbidden) {
        if (projectId == null || accountId == null) {
            throw new IllegalArgumentException("项目管理写许可必须提供项目与账号身份");
        }
        // 防止绕过Spring代理直调时使用auto-commit丢失排他锁；单靠MANDATORY不能识别只读外层事务。
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            throw new IllegalStateException("项目管理写许可必须加入已有非只读事务");
        }
        requireRole(projectId, accountId, allowed, forbidden);
        Project project = repository.lockForManagement(projectId)
                .filter(current -> projectId.equals(current.id()))
                .orElseThrow(() -> new BusinessException(ProjectErrorCode.PROJECT_NOT_FOUND));
        ProjectRole currentRole = requireRole(projectId, accountId, allowed, forbidden);
        // 先重新授权才分类归档，不能向等待期间失权的账号暴露项目仍存在且只读。
        if (project.status() == Project.Status.ARCHIVED) {
            throw new BusinessException(ProjectErrorCode.PROJECT_READ_ONLY);
        }
        if (project.status() != Project.Status.ACTIVE) {
            throw new BusinessException(ProjectErrorCode.PROJECT_NOT_FOUND);
        }
        return currentRole;
    }

    /** 两次授权采用相同角色规则；查无成员统一50001，不泄露项目状态。 */
    private ProjectRole requireRole(UUID projectId, UUID accountId, Predicate<ProjectRole> allowed,
                                    ProjectErrorCode forbidden) {
        ProjectRole role = repository.findRole(projectId, accountId)
                .orElseThrow(() -> new BusinessException(ProjectErrorCode.PROJECT_NOT_FOUND));
        if (!allowed.test(role)) {
            throw new BusinessException(forbidden);
        }
        return role;
    }
}
