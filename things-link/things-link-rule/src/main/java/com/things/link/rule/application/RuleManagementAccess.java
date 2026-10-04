package com.things.link.rule.application;

import com.things.link.project.application.ProjectService;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.rule.domain.RuleErrorCode;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import com.things.link.shared.tenant.TenantContext;
import org.springframework.stereotype.Component;
import java.util.UUID;

/** 管理控制面按当前成员和持久归属授权；持续许可必须留在原写事务内。 */
@Component
public class RuleManagementAccess {
    /** 项目成员及持久归属公开端口。 */
    private final ProjectService projects;
    /** 项目生命周期持续许可。 */
    private final ProjectLifecycleAccessService lifecycle;
    /** 装配项目域公开端口。 */
    public RuleManagementAccess(ProjectService projects, ProjectLifecycleAccessService lifecycle) {
        this.projects = projects; this.lifecycle = lifecycle;
    }
    /** 读取允许归档，拒绝当前项目不匹配和非管理角色。 */
    public Identity read(UUID projectId, boolean scene) {
        var scope = TenantContext.current().orElseThrow(() -> new IllegalStateException("没有租户上下文"));
        if (projectId == null || !projectId.equals(scope.projectId()))
            throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);
        role(projectId, scene);
        return new Identity(projects.requireProjectTenant(projectId), scope.accountId());
    }
    /** 先取持续项目许可再复核角色，调用方继续领域锁和CAS。 */
    public Identity write(UUID projectId, boolean scene) {
        Identity identity = read(projectId, scene);
        lifecycle.requireActiveForWrite(identity.tenantId(), projectId);
        role(projectId, scene);
        return identity;
    }
    /** Worker之前短事务取得许可并立即释放；保存结果时仍需重新取得持续许可。 */
    @org.springframework.transaction.annotation.Transactional
    public Identity debug(UUID projectId) { return write(projectId, false); }
    /** 第一层与锁后授权都使用当前数据库角色，权限投影不代替授权。 */
    private void role(UUID projectId, boolean scene) {
        ProjectRole role = projects.requireRoleInProject(projectId);
        if (role != ProjectRole.OWNER && role != ProjectRole.ADMIN)
            throw new BusinessException(scene ? RuleErrorCode.SCENE_MANAGE_FORBIDDEN : RuleErrorCode.RULE_MANAGE_FORBIDDEN);
    }
    /** @param tenantId 项目持久归属 @param accountId 实际操作者 */
    public record Identity(UUID tenantId, UUID accountId) { }
}
