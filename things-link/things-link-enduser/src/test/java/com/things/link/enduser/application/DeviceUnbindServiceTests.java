package com.things.link.enduser.application;

import com.things.link.enduser.domain.AppUserDevice;
import com.things.link.enduser.domain.AppUserDeviceRepository;
import com.things.link.enduser.domain.AppUserRole;
import com.things.link.enduser.domain.AppUserRoleRepository;
import com.things.link.enduser.domain.EndUserErrorCode;
import com.things.link.enduser.domain.EndUserRole;
import com.things.link.project.application.ProjectService;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.support.audit.AuditLogEntry;
import com.things.link.support.audit.AuditLogService;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.QueryTimeoutException;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 解绑权限、幂等和审计的纯应用层合同（G2-A1d L1）。 */
class DeviceUnbindServiceTests {

    /** App 项目角色仓储。 */
    private AppUserRoleRepository roleRepository;
    /** 设备关系仓储。 */
    private AppUserDeviceRepository bindingRepository;
    /** 控制台项目服务。 */
    private ProjectService projectService;
    /** 审计服务。 */
    private AuditLogService auditLogService;
    /** App自身解绑门禁，不能混入控制台管理员授权链。 */
    private AppProjectWriteGuard projectWriteGuard;
    /** 控制台路由与App JWT身份进入受RLS事实前的集中范围入口。 */
    private TransactionLocalRlsScope transactionLocalRlsScope;
    /** 被测服务。 */
    private DeviceUnbindService service;

    /** 测试租户。 */
    private UUID tenantId;
    /** 测试项目。 */
    private UUID projectId;
    /** 测试 App 用户。 */
    private UUID appUserId;
    /** 测试设备。 */
    private UUID deviceId;
    /** 控制台账号。 */
    private UUID accountId;
    /** 测试关系。 */
    private AppUserDevice binding;

    /** 建立 happy-path 默认桩。 */
    @BeforeEach
    void setUp() {
        roleRepository = mock(AppUserRoleRepository.class);
        bindingRepository = mock(AppUserDeviceRepository.class);
        projectService = mock(ProjectService.class);
        auditLogService = mock(AuditLogService.class);
        projectWriteGuard = mock(AppProjectWriteGuard.class);
        transactionLocalRlsScope = mock(TransactionLocalRlsScope.class);
        service = new DeviceUnbindService(roleRepository, bindingRepository, projectService,
                auditLogService, transactionLocalRlsScope, projectWriteGuard);

        tenantId = UUID.randomUUID();
        projectId = UUID.randomUUID();
        appUserId = UUID.randomUUID();
        deviceId = UUID.randomUUID();
        accountId = UUID.randomUUID();
        binding = new AppUserDevice(UUID.randomUUID(), tenantId, projectId, appUserId, deviceId,
                AppUserDevice.RelationRole.PRIMARY, AppUserDevice.Status.CLOSED, Instant.now());
        when(roleRepository.findByProjectAndUser(projectId, appUserId))
                .thenReturn(Optional.of(new AppUserRole(
                        UUID.randomUUID(), tenantId, projectId, appUserId, EndUserRole.OBSERVER,
                        AppUserRole.Status.ACTIVE, Instant.now())));
        when(bindingRepository.closeActive(projectId, appUserId, deviceId))
                .thenReturn(Optional.of(binding));
        when(projectService.requireRoleInProject(projectId)).thenReturn(ProjectRole.OWNER);
        when(projectService.requireRoutingContext(projectId))
                .thenReturn(new ProjectService.ProjectRoutingContext(tenantId, "unbind-project"));
    }

    /** 防止失败测试把 ThreadLocal 污染给后续用例。 */
    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    /** 先取得项目许可再沿原角色、解绑、审计顺序执行，审计使用App身份。 */
    @Test
    void selfUnbindClosesAndAuditsAppActor() {
        service.unbindSelf(tenantId, projectId, appUserId, deviceId);

        ArgumentCaptor<AuditLogEntry> audit = ArgumentCaptor.forClass(AuditLogEntry.class);
        InOrder order = inOrder(transactionLocalRlsScope, projectWriteGuard, roleRepository,
                bindingRepository, auditLogService);
        order.verify(transactionLocalRlsScope).establish(tenantId, projectId);
        order.verify(projectWriteGuard).requireWritable(tenantId, projectId);
        order.verify(roleRepository).findByProjectAndUser(projectId, appUserId);
        order.verify(bindingRepository).closeActive(projectId, appUserId, deviceId);
        order.verify(auditLogService).record(audit.capture());
        assertThat(audit.getValue().actorAccountId()).isNull();
        assertThat(audit.getValue().targetId()).isEqualTo(binding.id());
        assertThat(audit.getValue().action()).isEqualTo("enduser.device.unbound.self");
        assertThat(audit.getValue().details().get("actorType")).isEqualTo("APP_USER");
        assertThat(audit.getValue().details().get("actorId")).isEqualTo(appUserId);
        assertThat(audit.getValue().details().get("relationRole")).isEqualTo("PRIMARY");
    }

    /** 生命周期拒绝早于关系状态与审计写入，且不能越过门禁查询角色。 */
    @ParameterizedTest
    @EnumSource(value = EndUserErrorCode.class, names = {"PROJECT_READ_ONLY", "END_USER_ACCESS_INVALID"})
    void projectDenialStopsSelfUnbindBeforeAnyDownstreamAccess(EndUserErrorCode code) {
        BusinessException failure = new BusinessException(code);
        doThrow(failure).when(projectWriteGuard).requireWritable(tenantId, projectId);

        assertThatThrownBy(() -> service.unbindSelf(tenantId, projectId, appUserId, deviceId))
                .isSameAs(failure);

        verifyNoInteractions(roleRepository, bindingRepository, auditLogService, projectService);
    }

    /** 项目锁故障保持原数据库异常，解绑和审计均不允许继续。 */
    @Test
    void projectDatabaseFailureStopsSelfUnbindBeforeAnyDownstreamAccess() {
        QueryTimeoutException failure = new QueryTimeoutException("解绑项目许可等待超时");
        doThrow(failure).when(projectWriteGuard).requireWritable(tenantId, projectId);

        assertThatThrownBy(() -> service.unbindSelf(tenantId, projectId, appUserId, deviceId))
                .isSameAs(failure);

        verifyNoInteractions(roleRepository, bindingRepository, auditLogService, projectService);
    }

    /** 已关闭或不存在的关系按成功无动作处理，不能重复写审计。 */
    @Test
    void repeatedSelfUnbindIsIdempotent() {
        when(bindingRepository.closeActive(projectId, appUserId, deviceId))
                .thenReturn(Optional.empty());

        service.unbindSelf(tenantId, projectId, appUserId, deviceId);

        verify(auditLogService, never()).record(any());
    }

    /** 项目角色停用时必须在关系写入前 fail-closed。 */
    @Test
    void disabledRoleCannotSelfUnbind() {
        when(roleRepository.findByProjectAndUser(projectId, appUserId))
                .thenReturn(Optional.of(new AppUserRole(
                        UUID.randomUUID(), tenantId, projectId, appUserId, EndUserRole.APP_ADMIN,
                        AppUserRole.Status.DISABLED, Instant.now())));

        assertThatThrownBy(() -> service.unbindSelf(tenantId, projectId, appUserId, deviceId))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(EndUserErrorCode.END_USER_ACCESS_INVALID);
        verify(bindingRepository, never()).closeActive(any(), any(), any());
    }

    /** OWNER/ADMIN 管理员解绑使用控制台账号列留审计。 */
    @Test
    void managerUnbindClosesAndAuditsAccountActor() {
        TenantContext.set(new TenantScope(tenantId, projectId, accountId));

        service.unbindByManager(projectId, appUserId, deviceId);

        verifyNoInteractions(projectWriteGuard);
        verify(transactionLocalRlsScope).establish(tenantId, projectId);
        ArgumentCaptor<AuditLogEntry> audit = ArgumentCaptor.forClass(AuditLogEntry.class);
        verify(auditLogService).record(audit.capture());
        assertThat(audit.getValue().actorAccountId()).isEqualTo(accountId);
        assertThat(audit.getValue().action()).isEqualTo("enduser.device.unbound.manager");
        assertThat(audit.getValue().details().get("actorType")).isEqualTo("ACCOUNT");
    }

    /** 普通控制台项目成员不具备管理员解绑能力。 */
    @Test
    void operatorCannotUnbindOtherUser() {
        TenantContext.set(new TenantScope(tenantId, projectId, accountId));
        when(projectService.requireRoleInProject(projectId)).thenReturn(ProjectRole.OPERATOR);

        assertThatThrownBy(() -> service.unbindByManager(projectId, appUserId, deviceId))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).errorCode())
                .isEqualTo(EndUserErrorCode.END_USER_MANAGE_FORBIDDEN);
        verify(bindingRepository, never()).closeActive(any(), any(), any());
        verify(auditLogService, never()).record(any());
        verifyNoInteractions(projectWriteGuard);
    }
}
