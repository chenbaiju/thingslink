package com.things.link.enduser.application;

import com.things.link.enduser.domain.AppUser;
import com.things.link.enduser.domain.AppUserRepository;
import com.things.link.enduser.domain.AppUserRole;
import com.things.link.enduser.domain.AppUserRoleRepository;
import com.things.link.enduser.domain.EndUserErrorCode;
import com.things.link.project.application.ProjectAccessPolicy;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.shared.error.BusinessException;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** 运行访问冻结§3.3/3.4：current与Schema共用的可信App身份门禁，不包含资源授权。 */
@Service
public class AppRuntimeIdentityService {
    /** 调用方只读事务内唯一建立可信租户与项目轴。 */
    private final TransactionLocalRlsScope scope;
    /** 用户每请求必须仍ACTIVE，不能仅信已签发JWT。 */
    private final AppUserRepository users;
    /** 项目角色每请求必须仍ACTIVE，不代替显式READ grant。 */
    private final AppUserRoleRepository roles;
    /** 项目读许可与删除生命周期代次。 */
    private final ProjectLifecycleAccessService lifecycle;

    /** 创建共用身份门禁；所有持久读取加入current或Schema已有事务。 */
    public AppRuntimeIdentityService(TransactionLocalRlsScope scope, AppUserRepository users,
            AppUserRoleRepository roles, ProjectLifecycleAccessService lifecycle) {
        this.scope = scope;
        this.users = users;
        this.roles = roles;
        this.lifecycle = lifecycle;
    }

    /** 先拒绝可信JWT轴缺失，保持资源语法错误之前的身份错误顺序。 */
    static void validateParameters(UUID tenantId, UUID projectId, UUID appUserId, long projectGeneration) {
        if (tenantId == null || projectId == null || appUserId == null || projectGeneration < 0) {
            throw invalidIdentity();
        }
    }

    /** 用户、项目角色与项目代次每次重验；确定身份拒绝60009，仓储身份漂移和数据库故障保留系统首因。 */
    @Transactional(readOnly = true, propagation = Propagation.MANDATORY)
    public void requireActive(UUID tenantId, UUID projectId, UUID appUserId, long projectGeneration) {
        validateParameters(tenantId, projectId, appUserId, projectGeneration);
        scope.establish(tenantId, projectId);
        AppUser user = users.findByIdAndTenant(tenantId, appUserId)
                .orElseThrow(AppRuntimeIdentityService::invalidIdentity);
        if (!tenantId.equals(user.tenantId()) || !appUserId.equals(user.id())) {
            throw new IllegalStateException("App用户仓储返回不一致的可信身份");
        }
        if (user.status() != AppUser.Status.ACTIVE) throw invalidIdentity();
        AppUserRole role = roles.findByProjectAndUser(projectId, appUserId)
                .orElseThrow(AppRuntimeIdentityService::invalidIdentity);
        if (!tenantId.equals(role.tenantId()) || !projectId.equals(role.projectId()) || !appUserId.equals(role.appUserId())) {
            throw new IllegalStateException("App角色仓储返回不一致的可信身份");
        }
        if (role.status() != AppUserRole.Status.ACTIVE) throw invalidIdentity();
        ProjectAccessPolicy policy = lifecycle.snapshot(tenantId, projectId);
        if (!policy.readAllowed() || !policy.matchesGeneration(projectGeneration)) throw invalidIdentity();
    }

    /** 身份失效独立于资源是否存在，统一按60009拒绝。 */
    private static BusinessException invalidIdentity() {
        return new BusinessException(EndUserErrorCode.END_USER_ACCESS_INVALID);
    }
}
