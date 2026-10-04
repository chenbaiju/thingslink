package com.things.link.enduser.api.support;

import com.things.link.enduser.domain.EndUserErrorCode;
import com.things.link.project.application.ProjectService;
import com.things.link.project.domain.ProjectErrorCode;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.error.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 终端用户看板授权HTTP首层守卫的成员隐藏与OWNER/ADMIN闭集测试。 */
class DashboardGrantApiAuthorizationTests {

    /** OWNER与ADMIN是管理授权的完整允许集合，不能依赖前端菜单替代实时角色查询。 */
    @Test
    void acceptsOnlyOwnerAndAdminRoles() {
        for (ProjectRole role : new ProjectRole[]{ProjectRole.OWNER, ProjectRole.ADMIN}) {
            ProjectService projects = mock(ProjectService.class);
            UUID projectId = UUID.randomUUID();
            when(projects.requireRoleInProject(projectId)).thenReturn(role);

            new DashboardGrantApiAuthorization(projects).requireManage(projectId);

            verify(projects).requireRoleInProject(projectId);
        }
    }

    /** 已确认的OPERATOR与VIEWER不足只返回60024，不复用其他域的管理错误。 */
    @Test
    void rejectsLowerProjectRolesWithGrantSpecificError() {
        for (ProjectRole role : new ProjectRole[]{ProjectRole.OPERATOR, ProjectRole.VIEWER}) {
            ProjectService projects = mock(ProjectService.class);
            UUID projectId = UUID.randomUUID();
            when(projects.requireRoleInProject(projectId)).thenReturn(role);

            assertThatThrownBy(() -> new DashboardGrantApiAuthorization(projects).requireManage(projectId))
                    .isInstanceOf(BusinessException.class)
                    .extracting(error -> ((BusinessException) error).errorCode())
                    .isEqualTo(EndUserErrorCode.DASHBOARD_GRANT_MANAGE_FORBIDDEN);
        }
    }

    /** 项目不存在或非成员的50001必须原样传播，不能被角色不足60024替换而泄露项目事实。 */
    @Test
    void preservesProjectMembershipFailure() {
        ProjectService projects = mock(ProjectService.class);
        UUID projectId = UUID.randomUUID();
        BusinessException hidden = new BusinessException(ProjectErrorCode.PROJECT_NOT_FOUND);
        when(projects.requireRoleInProject(projectId)).thenThrow(hidden);

        assertThatThrownBy(() -> new DashboardGrantApiAuthorization(projects).requireManage(projectId))
                .isSameAs(hidden);
    }
}
