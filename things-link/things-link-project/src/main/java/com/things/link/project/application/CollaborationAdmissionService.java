package com.things.link.project.application;

import com.things.link.project.domain.CollaborationCapacityRepository;
import com.things.link.project.domain.ProjectErrorCode;
import com.things.link.shared.error.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** S14-R4d：成员添加取得项目管理许可后，原事务内串行账号安全上限和租户外部席位。 */
@Service
public class CollaborationAdmissionService {
    private final ProjectService projects;
    private final CollaborationCapacityRepository repository;
    private final PlanCapacityService capacity;
    private final SubscriptionExpansionGuard expansion;
    private final com.things.link.project.domain.ProjectRepository projectRepository;
    private final ProjectManagementWriteGuard management;

    /** 只复用项目公开归属与权威套餐端口，不用调用者租户冒充资源归属。 */
    public CollaborationAdmissionService(ProjectService projects, CollaborationCapacityRepository repository,
            PlanCapacityService capacity, SubscriptionExpansionGuard expansion,
            com.things.link.project.domain.ProjectRepository projectRepository, ProjectManagementWriteGuard management) {
        this.projects = projects;
        this.repository = repository;
        this.capacity = capacity;
        this.expansion = expansion;
        this.projectRepository = projectRepository;
        this.management = management;
    }

    /** 按持久owner租户检查额度；50为不可出售的账号安全常量，既有审计scope不变。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireAdmission(UUID projectId, UUID inviteeId) {
        checkAdmission(projects.requireProjectTenant(projectId), projectId, inviteeId);
    }

    /** 邀请接受方尚非成员；仍锁后复核原邀请人，不伪造TenantContext为邀请人。仅本域邀请编排使用。 */
    @Transactional(propagation = Propagation.MANDATORY)
    void requireAcceptedInvitationAdmission(UUID projectId, UUID inviteeId, UUID inviterId) {
        management.requireMemberManager(projectId, inviterId);
        UUID tenant = projectRepository.findById(projectId)
                .orElseThrow(() -> new BusinessException(ProjectErrorCode.PROJECT_NOT_FOUND)).tenantId();
        checkAdmission(tenant, projectId, inviteeId);
    }

    private void checkAdmission(UUID tenant, UUID projectId, UUID inviteeId) {
        repository.lockCapacity(tenant, inviteeId);
        var usage = repository.readUsage(tenant, projectId, inviteeId);
        if (usage.alreadyMember()) throw new BusinessException(ProjectErrorCode.ALREADY_MEMBER);
        expansion.requireExpansionAllowed(tenant);
        long limit = capacity.externalCollaboratorSeats(tenant, projectId);
        if (!usage.internal()) {
            if (usage.externalProjects() >= 50) {
                throw new BusinessException(ProjectErrorCode.EXTERNAL_PROJECT_LIMIT_EXCEEDED);
            }
            if (!usage.occupiesSeat() && usage.seats() >= limit) {
                throw new BusinessException(ProjectErrorCode.EXTERNAL_SEAT_QUOTA_EXCEEDED);
            }
        }
    }
}
