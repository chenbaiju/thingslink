package com.things.link.enduser.application;

import com.things.link.enduser.domain.AppAccount;
import com.things.link.enduser.domain.AppUser;
import com.things.link.enduser.domain.AppUserRepository;
import com.things.link.enduser.domain.AppUserRole;
import com.things.link.enduser.domain.AppUserRoleRepository;
import com.things.link.enduser.domain.EndUserErrorCode;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.shared.error.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

/** 只读取令牌本人的账户公开字段，沿当前项目资格复验。 */
@Service
public class AppAccountService {
    private final AppUserRepository users;
    private final AppUserRoleRepository roles;
    private final ProjectLifecycleAccessService projects;
    /** @param users 终端身份仓储 @param roles 当前项目角色 @param projects 项目生命周期 */
    public AppAccountService(AppUserRepository users, AppUserRoleRepository roles,
                             ProjectLifecycleAccessService projects) {
        this.users = users;
        this.roles = roles;
        this.projects = projects;
    }
    /**
     * 读取本人资料，不以用户名或前端入参选择身份。
     * @param tenantId 已验签租户范围
     * @param projectId 已验签项目范围
     * @param userId 已验签终端用户主体
     * @return 公开资料和当前改密入口资格；失效身份统一401
     */
    @Transactional(readOnly = true)
    public AppAccount read(UUID tenantId, UUID projectId, UUID userId) {
        var role = roles.findByProjectAndUser(projectId, userId)
                .filter(value -> value.status() == AppUserRole.Status.ACTIVE)
                .orElseThrow(AppAccountService::invalid);
        var user = users.findByIdAndTenant(tenantId, userId)
                .filter(value -> value.status() == AppUser.Status.ACTIVE)
                .orElseThrow(AppAccountService::invalid);
        var policy = projects.snapshot(tenantId, projectId);
        if (!policy.readAllowed()) throw invalid();
        return new AppAccount(user.id(), user.username(), user.displayName(), user.createdAt(),
                role.role(), policy.writeAllowed());
    }
    /** 不暴露身份失效的具体查询步骤。 */
    private static BusinessException invalid() {
        return new BusinessException(EndUserErrorCode.END_USER_ACCESS_INVALID);
    }
}
