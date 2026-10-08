package com.things.link.enduser.application;

import com.things.link.enduser.domain.AppUserRepository;
import com.things.link.enduser.domain.AppUserRole;
import com.things.link.enduser.domain.AppUserRoleRepository;
import com.things.link.enduser.domain.EndUserErrorCode;
import com.things.link.project.application.ProjectService;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.UUID;

/** 项目管理员按精确用户名找回预置身份，不提供租户账号目录。 */
@Service
public class EndUserLookupService {
    private final ProjectService projects;
    private final AppUserRepository users;
    private final AppUserRoleRepository roles;
    private final TransactionLocalRlsScope scope;

    /**
     * 创建受限查找服务。
     * @param projects 项目确权及权威归属
     * @param users 租户身份仓储
     * @param roles 本项目角色仓储
     * @param scope 事务局部RLS范围
     */
    public EndUserLookupService(ProjectService projects, AppUserRepository users,
                               AppUserRoleRepository roles, TransactionLocalRlsScope scope) {
        this.projects = projects;
        this.users = users;
        this.roles = roles;
        this.scope = scope;
    }

    /**
     * 精确查找项目归属租户中的身份及仅当前项目的角色。
     * @param projectId 已存在的项目
     * @param username 精确用户名，与预置同样规范化
     * @return 不含口令的身份及可空的本项目角色，未知统一60001
     */
    @Transactional(readOnly = true)
    public Result lookup(UUID projectId, String username) {
        if (!projects.requireRoleInProject(projectId).canManageMembers()) {
            throw new BusinessException(EndUserErrorCode.END_USER_MANAGE_FORBIDDEN);
        }
        if (username == null || username.isBlank() || username.length() > 64) {
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER, "精确用户名必须为1至64位");
        }
        UUID tenantId = projects.requireRoutingContext(projectId).tenantId();
        scope.establish(tenantId, projectId);
        var user = users.findByTenantAndUsername(tenantId, username.trim().toLowerCase(Locale.ROOT))
                .orElseThrow(() -> new BusinessException(EndUserErrorCode.END_USER_NOT_FOUND));
        return new Result(AppUserReference.of(user), roles.findByProjectAndUser(projectId, user.id()).orElse(null));
    }

    /**
     * 查找结果，不包含其他项目的关系或任何口令材料。
     * @param user 非敏感租户身份
     * @param assignment 本项目角色，未分配时为空
     */
    public record Result(AppUserReference user, AppUserRole assignment) { }
}
