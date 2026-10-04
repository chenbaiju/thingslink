package com.things.link.enduser.application;

import com.things.link.enduser.domain.AppUser;
import com.things.link.enduser.domain.AppUserDeviceRepository;
import com.things.link.enduser.domain.AppUserRepository;
import com.things.link.enduser.domain.AppUserRole;
import com.things.link.enduser.domain.AppUserRoleRepository;
import com.things.link.enduser.domain.EndUserErrorCode;
import com.things.link.enduser.domain.EndUserRole;
import com.things.link.project.application.ProjectService;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.project.domain.ProjectErrorCode;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.error.BusinessException;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 终端用户项目角色用例的单元测试（S11-1a）。 */
class EndUserRoleServiceTests {

    private ProjectService projectService;
    private AppUserRepository appUserRepository;
    private AppUserRoleRepository appUserRoleRepository;
    private AppUserDeviceRepository appUserDeviceRepository;
    /** 项目权威路由进入受RLS事实前的集中范围入口。 */
    private TransactionLocalRlsScope transactionLocalRlsScope;
    /** 项目许可必须先于用户互斥。 */
    private ProjectLifecycleAccessService lifecycle;
    private EndUserRoleService service;

    private UUID projectId;
    private UUID owningTenantId;
    private UUID appUserId;

    @BeforeEach
    void setUp() {
        projectService = mock(ProjectService.class);
        appUserRepository = mock(AppUserRepository.class);
        appUserRoleRepository = mock(AppUserRoleRepository.class);
        appUserDeviceRepository = mock(AppUserDeviceRepository.class);
        transactionLocalRlsScope = mock(TransactionLocalRlsScope.class);
        lifecycle = mock(ProjectLifecycleAccessService.class);
        service = new EndUserRoleService(projectService, appUserRepository, appUserRoleRepository,
                appUserDeviceRepository,
                transactionLocalRlsScope, lifecycle);
        projectId = UUID.randomUUID();
        owningTenantId = UUID.randomUUID();
        appUserId = UUID.randomUUID();
        when(projectService.requireRoleInProject(projectId)).thenReturn(ProjectRole.ADMIN);
        when(projectService.requireRoutingContext(projectId))
                .thenReturn(new ProjectService.ProjectRoutingContext(owningTenantId, "proj-key"));
        when(appUserRepository.lockByIdAndTenant(owningTenantId, appUserId))
                .thenReturn(Optional.of(user()));
    }

    /** 角色记录落在项目归属租户，role / status 符合预期。 */
    @Test
    void assignPersistsRoleUnderOwningTenant() {
        service.assign(projectId, appUserId, EndUserRole.APP_ADMIN);

        ArgumentCaptor<AppUserRole> captor = ArgumentCaptor.forClass(AppUserRole.class);
        var order = inOrder(transactionLocalRlsScope, lifecycle, appUserRepository);
        order.verify(transactionLocalRlsScope).establish(owningTenantId, projectId);
        order.verify(lifecycle).requireActiveForWrite(owningTenantId, projectId);
        order.verify(appUserRepository).lockByIdAndTenant(owningTenantId, appUserId);
        verify(appUserRepository).lockByIdAndTenant(owningTenantId, appUserId);
        verify(appUserRoleRepository).assign(captor.capture());
        AppUserRole assigned = captor.getValue();
        assertThat(assigned.tenantId()).isEqualTo(owningTenantId);
        assertThat(assigned.projectId()).isEqualTo(projectId);
        assertThat(assigned.appUserId()).isEqualTo(appUserId);
        assertThat(assigned.role()).isEqualTo(EndUserRole.APP_ADMIN);
        assertThat(assigned.status()).isEqualTo(AppUserRole.Status.ACTIVE);
    }

    /** 目标用户不属于项目归属租户时按「不存在」处理，不落库。 */
    @Test
    void assignRejectsForeignTenantUser() {
        when(appUserRepository.lockByIdAndTenant(owningTenantId, appUserId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.assign(projectId, appUserId, EndUserRole.APP_ADMIN))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(EndUserErrorCode.END_USER_NOT_FOUND);
        verify(appUserRoleRepository, never()).assign(any());
    }

    /** 重复分配由 (project_id, app_user_id) 唯一索引仲裁，映射为 60004。 */
    @Test
    void assignRejectsDuplicateRole() {
        doThrow(new DuplicateKeyException("dup")).when(appUserRoleRepository).assign(any());

        assertThatThrownBy(() -> service.assign(projectId, appUserId, EndUserRole.APP_ADMIN))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(EndUserErrorCode.END_USER_ROLE_ALREADY_ASSIGNED);
    }

    /** OPERATOR / VIEWER 无权分配角色。 */
    @Test
    void nonManagerIsRejected() {
        when(projectService.requireRoleInProject(projectId)).thenReturn(ProjectRole.OPERATOR);

        assertThatThrownBy(() -> service.assign(projectId, appUserId, EndUserRole.APP_ADMIN))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(EndUserErrorCode.END_USER_MANAGE_FORBIDDEN);
    }

    /** 停用落在项目级 status，并在同一用例中关闭项目内全部有效设备关系。 */
    @Test
    void suspendDisablesRole() {
        when(appUserRoleRepository.updateStatus(projectId, appUserId, AppUserRole.Status.DISABLED))
                .thenReturn(1);

        service.suspend(projectId, appUserId);

        verify(appUserRepository).lockByIdAndTenant(owningTenantId, appUserId);
        verify(appUserRoleRepository).updateStatus(projectId, appUserId, AppUserRole.Status.DISABLED);
        verify(appUserDeviceRepository).closeActiveByProjectAndUser(projectId, appUserId);
    }

    /** 目标用户在本项目没有角色时返回 60005。 */
    @Test
    void suspendMissingRoleReturnsNotFound() {
        when(appUserRoleRepository.updateStatus(projectId, appUserId, AppUserRole.Status.DISABLED))
                .thenReturn(0);

        assertThatThrownBy(() -> service.suspend(projectId, appUserId))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(EndUserErrorCode.END_USER_ROLE_NOT_FOUND);
        verify(appUserDeviceRepository, never()).closeActiveByProjectAndUser(any(), any());
    }

    /** 设备关系关闭失败必须向上抛出，使 Spring 事务回滚先前的角色状态更新。 */
    @Test
    void suspendPropagatesBindingClosureFailure() {
        when(appUserRoleRepository.updateStatus(projectId, appUserId, AppUserRole.Status.DISABLED))
                .thenReturn(1);
        doThrow(new IllegalStateException("database unavailable"))
                .when(appUserDeviceRepository).closeActiveByProjectAndUser(projectId, appUserId);

        assertThatThrownBy(() -> service.suspend(projectId, appUserId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("database unavailable");
    }

    /** 恢复已停用角色。 */
    @Test
    void restoreActivatesRole() {
        when(appUserRoleRepository.updateStatus(projectId, appUserId, AppUserRole.Status.ACTIVE))
                .thenReturn(1);

        service.restore(projectId, appUserId);

        verify(appUserRepository).lockByIdAndTenant(owningTenantId, appUserId);
        verify(appUserRoleRepository).updateStatus(projectId, appUserId, AppUserRole.Status.ACTIVE);
    }

    /** 改角色只改 role 列，不改 status 列。 */
    @Test
    void updateRoleChangesRole() {
        when(appUserRoleRepository.updateRole(projectId, appUserId, EndUserRole.MAINTAINER))
                .thenReturn(1);

        service.updateRole(projectId, appUserId, EndUserRole.MAINTAINER);

        verify(appUserRepository).lockByIdAndTenant(owningTenantId, appUserId);
        verify(appUserRoleRepository).updateRole(projectId, appUserId, EndUserRole.MAINTAINER);
    }

    /** 目标用户在本项目没有角色时改角色返回 60005。 */
    @Test
    void updateRoleMissingRoleReturnsNotFound() {
        when(appUserRoleRepository.updateRole(projectId, appUserId, EndUserRole.MAINTAINER))
                .thenReturn(0);

        assertThatThrownBy(() -> service.updateRole(projectId, appUserId, EndUserRole.MAINTAINER))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(EndUserErrorCode.END_USER_ROLE_NOT_FOUND);
    }

    /** OPERATOR / VIEWER 无权改角色。 */
    @Test
    void updateRoleNonManagerIsRejected() {
        when(projectService.requireRoleInProject(projectId)).thenReturn(ProjectRole.OPERATOR);

        assertThatThrownBy(() -> service.updateRole(projectId, appUserId, EndUserRole.MAINTAINER))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(EndUserErrorCode.END_USER_MANAGE_FORBIDDEN);
    }

    /** 归档拒绝必须发生在用户锁和任何角色副作用之前。 */
    @Test
    void archivedProjectRejectsBeforeUserLock() {
        doThrow(new BusinessException(ProjectErrorCode.PROJECT_READ_ONLY)).when(lifecycle)
                .requireActiveForWrite(owningTenantId, projectId);
        assertThatThrownBy(() -> service.suspend(projectId, appUserId))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(ProjectErrorCode.PROJECT_READ_ONLY);
        verify(appUserRepository, never()).lockByIdAndTenant(any(), any());
        verify(appUserRoleRepository, never()).updateStatus(any(), any(), any());
    }

    /** 等待项目许可期间失去管理角色，不能继续消费第一次授权快照。 */
    @Test
    void membershipIsRecheckedAfterProjectPermit() {
        when(projectService.requireRoleInProject(projectId)).thenReturn(ProjectRole.ADMIN, ProjectRole.VIEWER);
        assertThatThrownBy(() -> service.restore(projectId, appUserId))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode())
                .isEqualTo(EndUserErrorCode.END_USER_MANAGE_FORBIDDEN);
        verify(appUserRepository, never()).lockByIdAndTenant(any(), any());
    }

    /** @return 归属租户里的一名终端用户（领域投影，供 findBy 返回） */
    private static AppUser user() {
        return new AppUser(UUID.randomUUID(), UUID.randomUUID(), "alice", "{bcrypt}x", null,
                AppUser.Status.ACTIVE, null, Instant.now());
    }
}
