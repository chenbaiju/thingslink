package com.things.link.enduser.api.support;

import com.things.link.enduser.domain.EndUserErrorCode;
import com.things.link.project.application.ProjectService;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.error.BusinessException;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.UUID;

/**
 * 终端用户看板授权管理HTTP入口的首层项目角色守卫。
 *
 * <p>S12-2a3b规定读取和写入均只向项目OWNER与ADMIN开放。项目不存在或非成员继续由
 * {@link ProjectService#requireRoleInProject(UUID)}隐藏为50001；已确认成员但角色不足才返回60024。
 * 管理服务仍保留同等二次授权，防止内部调用绕过HTTP边界。</p>
 */
@Component
public class DashboardGrantApiAuthorization {

    /** 提供实时项目成员角色判定，不依赖菜单下发结果。 */
    private final ProjectService projectService;

    /**
     * 创建终端用户看板授权HTTP守卫。
     *
     * @param projectService 项目成员授权服务
     */
    public DashboardGrantApiAuthorization(ProjectService projectService) {
        this.projectService = Objects.requireNonNull(projectService, "projectService");
    }

    /**
     * 要求当前Console账号为项目OWNER或ADMIN。
     *
     * @param projectId 当前选定项目ID
     * @throws BusinessException 非成员沿50001，已确认低权限成员沿60024
     */
    public void requireManage(UUID projectId) {
        ProjectRole role = projectService.requireRoleInProject(projectId);
        if (role != ProjectRole.OWNER && role != ProjectRole.ADMIN) {
            throw new BusinessException(EndUserErrorCode.DASHBOARD_GRANT_MANAGE_FORBIDDEN);
        }
    }
}
