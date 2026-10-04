package com.things.link.enduser.application;

import com.things.link.enduser.domain.AppUser;
import com.things.link.enduser.domain.AppUserAssignment;
import com.things.link.enduser.domain.AppUserDevice;
import com.things.link.enduser.domain.AppUserDeviceRepository;
import com.things.link.enduser.domain.AppUserRole;
import com.things.link.enduser.domain.AppUserRoleRepository;
import com.things.link.enduser.domain.EndUserRole;
import com.things.link.project.application.ProjectService;
import com.things.link.project.domain.ProjectErrorCode;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.page.CursorPage;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 终端用户查询用例的单元测试（S11-1b）：项目用户列表、设备绑定概览。 */
class EndUserQueryServiceTests {

    private ProjectService projectService;
    private AppUserRoleRepository appUserRoleRepository;
    private AppUserDeviceRepository appUserDeviceRepository;
    /** 项目权威路由进入受RLS事实前的集中范围入口。 */
    private TransactionLocalRlsScope transactionLocalRlsScope;
    private EndUserQueryService service;

    private UUID projectId;
    private UUID owningTenantId;
    private UUID appUserId;

    @BeforeEach
    void setUp() {
        projectService = mock(ProjectService.class);
        appUserRoleRepository = mock(AppUserRoleRepository.class);
        appUserDeviceRepository = mock(AppUserDeviceRepository.class);
        transactionLocalRlsScope = mock(TransactionLocalRlsScope.class);
        service = new EndUserQueryService(projectService, appUserRoleRepository, appUserDeviceRepository,
                transactionLocalRlsScope);
        projectId = UUID.randomUUID();
        owningTenantId = UUID.randomUUID();
        appUserId = UUID.randomUUID();
        when(projectService.requireRoleInProject(projectId)).thenReturn(ProjectRole.VIEWER);
        when(projectService.requireRoutingContext(projectId))
                .thenReturn(new ProjectService.ProjectRoutingContext(owningTenantId, "proj-key"));
    }

    /** 任意项目成员（含 VIEWER）都能看列表；结果原样透传。 */
    @Test
    void listReturnsAssignmentsForAnyMember() {
        CursorPage<AppUserAssignment> page = CursorPage.last(List.of(assignment()));
        when(appUserRoleRepository.findAssignmentsByProject(projectId, null, 50))
                .thenReturn(page);

        assertThat(service.list(projectId, null, 50)).isSameAs(page);
        var order = inOrder(transactionLocalRlsScope, appUserRoleRepository);
        order.verify(transactionLocalRlsScope).establish(owningTenantId, projectId);
        order.verify(appUserRoleRepository).findAssignmentsByProject(projectId, null, 50);
    }

    /** 非成员看列表按项目不存在处理，且不触碰仓储。 */
    @Test
    void listNonMemberIsRejectedAsNotFound() {
        when(projectService.requireRoleInProject(projectId))
                .thenThrow(new BusinessException(ProjectErrorCode.PROJECT_NOT_FOUND));

        assertThatThrownBy(() -> service.list(projectId, null, 50))
                .isInstanceOf(BusinessException.class);
        verify(appUserRoleRepository, never()).findAssignmentsByProject(any(), any(), anyInt());
    }

    /** 设备绑定概览只陈述事实，结果原样透传。 */
    @Test
    void listDeviceBindingsReturnsBindings() {
        AppUserDevice device = device();
        when(appUserDeviceRepository.findByProjectAndUser(projectId, appUserId))
                .thenReturn(List.of(device));

        assertThat(service.listDeviceBindings(projectId, appUserId)).containsExactly(device);
    }

    /** 非成员看设备绑定同样按项目不存在处理。 */
    @Test
    void listDeviceBindingsNonMemberIsRejectedAsNotFound() {
        when(projectService.requireRoleInProject(projectId))
                .thenThrow(new BusinessException(ProjectErrorCode.PROJECT_NOT_FOUND));

        assertThatThrownBy(() -> service.listDeviceBindings(projectId, appUserId))
                .isInstanceOf(BusinessException.class);
        verify(appUserDeviceRepository, never()).findByProjectAndUser(any(), any());
    }

    /** @return 一条角色赋值投影（列表元素） */
    private static AppUserAssignment assignment() {
        return new AppUserAssignment(
                UUID.randomUUID(), "alice", "张三", AppUser.Status.ACTIVE,
                EndUserRole.OPERATOR, AppUserRole.Status.ACTIVE, Instant.now());
    }

    /** @return 一条设备授权关系（概览元素） */
    private static AppUserDevice device() {
        return new AppUserDevice(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), AppUserDevice.RelationRole.PRIMARY,
                AppUserDevice.Status.ACTIVE, Instant.now());
    }
}
