package com.things.link.dashboard.api.support;

import com.things.link.dashboard.domain.DashboardErrorCode;
import com.things.link.project.application.ProjectService;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.error.BusinessException;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.UUID;

/**
 * 看板管理HTTP入口的首层项目授权守卫。
 *
 * <p>S12-1d3的{@code dashboard_definition:read}向四种已选项目角色开放；S12-1d4a的
 * {@code dashboard_definition:manage}仅向OWNER与ADMIN开放。此处使用实时项目成员关系判定；
 * {@link com.things.link.dashboard.application.DashboardManagementService}保留相同的二次校验，
 * 避免内部调用绕过HTTP边界。</p>
 */
@Component
public class DashboardApiAuthorization {

    /** 提供实时项目成员角色校验的公开应用端口。 */
    private final ProjectService projectService;

    /**
     * 创建看板HTTP首层授权守卫。
     *
     * @param projectService 项目成员授权服务
     */
    public DashboardApiAuthorization(ProjectService projectService) {
        this.projectService = Objects.requireNonNull(projectService, "projectService");
    }

    /**
     * 要求当前Console账号具有项目成员关系，即满足{@code dashboard_definition:read}。
     *
     * @param projectId 当前选定的项目ID
     */
    public void requireRead(UUID projectId) {
        projectService.requireRoleInProject(projectId);
    }

    /**
     * 要求当前Console账号为项目OWNER或ADMIN，即满足{@code dashboard_definition:manage}。
     *
     * @param projectId 当前选定的项目ID
     */
    public void requireManage(UUID projectId) {
        ProjectRole role = projectService.requireRoleInProject(projectId);
        if (role != ProjectRole.OWNER && role != ProjectRole.ADMIN) {
            throw new BusinessException(DashboardErrorCode.DASHBOARD_MANAGEMENT_FORBIDDEN);
        }
    }
}
