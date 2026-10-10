package com.things.link.enduser.application;

import com.things.link.enduser.domain.AppDeviceDetails;
import com.things.link.enduser.domain.AppDeviceReadRepository;
import com.things.link.device.application.AppDeviceDataPlaneService;
import com.things.link.enduser.domain.AppUserDevice;
import com.things.link.enduser.domain.AppUserDeviceRepository;
import com.things.link.enduser.domain.AppUserRole;
import com.things.link.enduser.domain.AppUserRoleRepository;
import com.things.link.enduser.domain.EndUserRole;
import com.things.link.enduser.domain.EndUserErrorCode;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.page.CursorPage;
import com.things.link.telemetry.application.AppCommandResult;
import com.things.link.telemetry.application.AppPropertyHistory;
import com.things.link.telemetry.application.AppTelemetryDataPlaneService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.springframework.dao.QueryTimeoutException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * App 设备数据面授权编排的单元测试（S11-2b）。
 *
 * <p>钉住生命周期写许可、两层授权矩阵与委托关系，数据面SQL由device/telemetry测试覆盖。
 * 项目获准写入后，原项目角色只做进入项目门禁，设备关系角色继续决定控制权限。
 */
class AppDeviceAccessServiceTests {

    private AppUserRoleRepository roleRepository;
    private AppUserDeviceRepository bindingRepository;
    private AppDeviceDataPlaneService deviceDataPlane;
    private AppTelemetryDataPlaneService telemetryDataPlane;
    /** 生命周期门禁单独验证分类，本类钉住门禁必须先于原授权及命令副作用。 */
    private AppProjectWriteGuard projectWriteGuard;
    private AppDeviceAccessService service;
    private AppDeviceReadRepository deviceReads;

    private UUID tenantId;
    private UUID projectId;
    private UUID appUserId;
    private UUID deviceId;
    private UUID otherDeviceId;

    /** 每例独立端口，写许可不影响原有读取授权矩阵。 */
    @BeforeEach
    void setUp() {
        roleRepository = mock(AppUserRoleRepository.class);
        bindingRepository = mock(AppUserDeviceRepository.class);
        deviceDataPlane = mock(AppDeviceDataPlaneService.class);
        telemetryDataPlane = mock(AppTelemetryDataPlaneService.class);
        projectWriteGuard = mock(AppProjectWriteGuard.class);
        deviceReads = mock(AppDeviceReadRepository.class);
        service = new AppDeviceAccessService(roleRepository, bindingRepository, deviceDataPlane, telemetryDataPlane,
                projectWriteGuard, deviceReads);

        tenantId = UUID.randomUUID();
        projectId = UUID.randomUUID();
        appUserId = UUID.randomUUID();
        deviceId = UUID.randomUUID();
        otherDeviceId = UUID.randomUUID();
    }

    /** 目录读取遵循控制关系，项目写门禁只由真正提交执行。 */
    @Test
    void commandCatalogRequiresControlRelationWithoutWriting() {
        activeRole();
        for (AppUserDevice.RelationRole relation : List.of(AppUserDevice.RelationRole.PRIMARY,
                AppUserDevice.RelationRole.MEMBER)) {
            when(bindingRepository.findByProjectAndUser(projectId, appUserId))
                    .thenReturn(List.of(binding(deviceId, relation)));
            service.requireCommandCatalogAccess(projectId, appUserId, deviceId);
        }
        when(bindingRepository.findByProjectAndUser(projectId, appUserId))
                .thenReturn(List.of(binding(deviceId, AppUserDevice.RelationRole.READ_ONLY)));
        assertErrorCode(60011, () -> service.requireCommandCatalogAccess(projectId, appUserId, deviceId));
        verifyNoInteractions(projectWriteGuard, deviceDataPlane, telemetryDataPlane);
    }

    /** 目录不得绕过原角色门禁或泄漏未绑定设备。 */
    @Test
    void commandCatalogRejectsInvalidRoleAndUnboundDevice() {
        when(roleRepository.findByProjectAndUser(projectId, appUserId))
                .thenReturn(Optional.of(role(AppUserRole.Status.DISABLED)));
        assertErrorCode(60009, () -> service.requireCommandCatalogAccess(projectId, appUserId, deviceId));
        activeRole();
        when(bindingRepository.findByProjectAndUser(projectId, appUserId)).thenReturn(List.of());
        assertErrorCode(60010, () -> service.requireCommandCatalogAccess(projectId, appUserId, deviceId));
        verifyNoInteractions(projectWriteGuard, deviceDataPlane, telemetryDataPlane);
    }

    // ---------------------------------------------------------------- 角色门禁

    @Test
    void disabledRoleDeniesRead() {
        when(roleRepository.findByProjectAndUser(projectId, appUserId))
                .thenReturn(Optional.of(role(AppUserRole.Status.DISABLED)));

        assertErrorCode(60009, () -> service.detail(tenantId, projectId, appUserId, deviceId));
        // 角色闸被拒后不得触碰设备绑定与数据面端口
        verifyNoInteractions(bindingRepository, deviceDataPlane, telemetryDataPlane, deviceReads);
    }

    @Test
    void missingRoleDeniesRead() {
        when(roleRepository.findByProjectAndUser(projectId, appUserId)).thenReturn(Optional.empty());

        assertErrorCode(60009, () -> service.list(tenantId, projectId, appUserId, null, 20, null, null));
        verifyNoInteractions(bindingRepository, deviceDataPlane, telemetryDataPlane, deviceReads);
    }

    // ---------------------------------------------------------------- 读：任意关系角色可读

    @Test
    void readOnlyRelationCanReadDetailAndHistory() {
        activeRole();
        when(bindingRepository.findByProjectAndUser(projectId, appUserId))
                .thenReturn(List.of(binding(deviceId, AppUserDevice.RelationRole.READ_ONLY)));
        when(deviceReads.detail(tenantId, projectId, appUserId, deviceId)).thenReturn(Optional.of(device()));
        when(telemetryDataPlane.history(eq(projectId), eq(deviceId), any(), any(), any(), any(), any()))
                .thenReturn(new AppPropertyHistory("RAW", "RAW", "AVG", List.of()));

        assertThat(service.detail(tenantId, projectId, appUserId, deviceId)).isNotNull();
        assertThat(service.history(projectId, appUserId, deviceId, "temperature",
                Instant.now().minusSeconds(60), Instant.now(), "RAW", "AVG")).isNotNull();
    }

    // ---------------------------------------------------------------- 控制：关系角色唯一闸

    /** 获得项目许可后仍不得绕过设备READ_ONLY关系的控制限制。 */
    @Test
    void readOnlyRelationDeniesControl() {
        activeRole();
        when(bindingRepository.findByProjectAndUser(projectId, appUserId))
                .thenReturn(List.of(binding(deviceId, AppUserDevice.RelationRole.READ_ONLY)));

        assertErrorCode(60011, () -> service.submit(tenantId, projectId, appUserId, deviceId,
                "key-1", "restart", null));
        verify(projectWriteGuard).requireWritable(tenantId, projectId);
        verifyNoInteractions(telemetryDataPlane);
    }

    /** 可信租户和项目先取得许可，原角色、绑定与命令受理仍顺序执行。 */
    @Test
    void observerRoleWithPrimaryRelationCanControl() {
        // 项目角色 OBSERVER 不参与控制判定：只要有 PRIMARY 关系就放行
        when(roleRepository.findByProjectAndUser(projectId, appUserId))
                .thenReturn(Optional.of(role(AppUserRole.Status.ACTIVE, EndUserRole.OBSERVER)));
        when(bindingRepository.findByProjectAndUser(projectId, appUserId))
                .thenReturn(List.of(binding(deviceId, AppUserDevice.RelationRole.PRIMARY)));
        AppCommandResult expected = new AppCommandResult(
                UUID.randomUUID(), "ACCEPTED", "restart", null, Instant.now(), null, null);
        when(telemetryDataPlane.submit(projectId, deviceId, "key-1", "restart", null, appUserId))
                .thenReturn(expected);

        assertThat(service.submit(tenantId, projectId, appUserId, deviceId, "key-1", "restart", null))
                .isSameAs(expected);
        InOrder order = inOrder(projectWriteGuard, roleRepository, bindingRepository, telemetryDataPlane);
        order.verify(projectWriteGuard).requireWritable(tenantId, projectId);
        order.verify(roleRepository).findByProjectAndUser(projectId, appUserId);
        order.verify(bindingRepository).findByProjectAndUser(projectId, appUserId);
        order.verify(telemetryDataPlane).submit(projectId, deviceId, "key-1", "restart", null, appUserId);
    }

    /** 确定的归档或失效拒绝发生在角色读取、绑定检查及任何命令副作用之前。 */
    @ParameterizedTest
    @ValueSource(ints = {60009, 60022})
    void projectDenialStopsAllDownstreamAccess(int code) {
        BusinessException failure = new BusinessException(code == 60022
                ? EndUserErrorCode.PROJECT_READ_ONLY : EndUserErrorCode.END_USER_ACCESS_INVALID);
        doThrow(failure).when(projectWriteGuard).requireWritable(tenantId, projectId);

        assertThatThrownBy(() -> service.submit(tenantId, projectId, appUserId, deviceId,
                "key-1", "restart", null)).isSameAs(failure);

        verifyNoInteractions(roleRepository, bindingRepository, deviceDataPlane, telemetryDataPlane);
    }

    /** SQL超时不是确定的业务拒绝，必须保留首因并停止下游调用。 */
    @Test
    void projectDatabaseFailurePropagatesWithoutDownstreamAccess() {
        QueryTimeoutException failure = new QueryTimeoutException("项目写许可等待超时");
        doThrow(failure).when(projectWriteGuard).requireWritable(tenantId, projectId);

        assertThatThrownBy(() -> service.submit(tenantId, projectId, appUserId, deviceId,
                "key-1", "restart", null)).isSameAs(failure);

        verifyNoInteractions(roleRepository, bindingRepository, deviceDataPlane, telemetryDataPlane);
    }

    // ---------------------------------------------------------------- 未绑定 / 不存在统一 404

    @Test
    void unboundDeviceDeniesRead() {
        activeRole();
        when(bindingRepository.findByProjectAndUser(projectId, appUserId))
                .thenReturn(List.of(binding(otherDeviceId, AppUserDevice.RelationRole.PRIMARY)));

        assertErrorCode(60010, () -> service.detail(tenantId, projectId, appUserId, deviceId));
        verifyNoInteractions(deviceDataPlane, telemetryDataPlane);
    }

    @Test
    void closedBindingDeniesRead() {
        activeRole();
        when(bindingRepository.findByProjectAndUser(projectId, appUserId))
                .thenReturn(List.of(new AppUserDevice(UUID.randomUUID(), tenantId, projectId, appUserId,
                        deviceId, AppUserDevice.RelationRole.PRIMARY, AppUserDevice.Status.CLOSED, Instant.now())));

        assertErrorCode(60010, () -> service.detail(tenantId, projectId, appUserId, deviceId));
    }

    @Test
    void deviceSoftDeletedMapsToNotFound() {
        activeRole();
        when(bindingRepository.findByProjectAndUser(projectId, appUserId))
                .thenReturn(List.of(binding(deviceId, AppUserDevice.RelationRole.PRIMARY)));
        when(deviceReads.detail(tenantId, projectId, appUserId, deviceId)).thenReturn(Optional.empty());

        assertErrorCode(60010, () -> service.detail(tenantId, projectId, appUserId, deviceId));
    }

    // ---------------------------------------------------------------- 列表只传绑定设备

    @Test
    void listPassesTrustedScopeAndFiltersWithoutExpandingBindings() {
        activeRole();
        when(deviceReads.list(tenantId, projectId, appUserId, null, 20, "灯", "ONLINE"))
                .thenReturn(CursorPage.last(List.of()));
        service.list(tenantId, projectId, appUserId, null, 20, "灯", "ONLINE");
        verify(deviceReads).list(tenantId, projectId, appUserId, null, 20, "灯", "ONLINE");
        verifyNoInteractions(bindingRepository, deviceDataPlane);
    }

    // ---------------------------------------------------------------- 夹具

    private void activeRole() {
        when(roleRepository.findByProjectAndUser(projectId, appUserId))
                .thenReturn(Optional.of(role(AppUserRole.Status.ACTIVE)));
    }

    private AppUserRole role(AppUserRole.Status status) {
        return role(status, EndUserRole.OPERATOR);
    }

    private AppUserRole role(AppUserRole.Status status, EndUserRole role) {
        return new AppUserRole(UUID.randomUUID(), tenantId, projectId, appUserId, role, status, Instant.now());
    }

    private AppUserDevice binding(UUID targetDeviceId, AppUserDevice.RelationRole relationRole) {
        return new AppUserDevice(UUID.randomUUID(), tenantId, projectId, appUserId, targetDeviceId,
                relationRole, AppUserDevice.Status.ACTIVE, Instant.now());
    }

    private AppDeviceDetails device() {
        return new AppDeviceDetails(deviceId, "dev-1", "设备", null, "ONLINE", null, Instant.now(), Instant.now(), "通用类型", null);
    }

    private void assertErrorCode(int expectedCode, Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).errorCode().code())
                .isEqualTo(expectedCode);
    }
}
