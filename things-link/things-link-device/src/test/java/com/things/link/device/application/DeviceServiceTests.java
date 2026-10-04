package com.things.link.device.application;

import com.things.link.device.domain.Device;
import com.things.link.device.domain.DeviceErrorCode;
import com.things.link.device.domain.DeviceRepository;
import com.things.link.device.domain.DeviceSearchQuery;
import com.things.link.project.application.ProjectService;
import com.things.link.project.application.EffectiveQuotaPolicy;
import com.things.link.project.application.EffectiveQuotaPolicyProvider;
import com.things.link.project.application.ProjectQuotaService;
import com.things.link.project.application.QuotaStatus;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.page.CursorPage;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

/** 设备服务单元测试。 */
@ExtendWith(MockitoExtension.class)
class DeviceServiceTests {
    @Mock private DeviceRepository repository;
    @Mock private ProjectService projectService;
    @Mock private EffectiveQuotaPolicyProvider quotaPolicyProvider;
    @Mock private ProjectQuotaService projectQuotaService;
    /** 软删拓扑使用真实应用端口，单测只隔离该依赖。 */
    @Mock private DeviceTopologyService topologyService;
    /** 类型角色保护的真实锁与冲突由数据库测试覆盖，本类保持设备服务依赖隔离。 */
    @Mock private DeviceTopologyRoleGuard roleGuard;
    @Mock private DeviceAccessControlService accessControl;
    @Mock private DeviceAccessTypeGuard accessTypes;
    private DeviceService service;
    private UUID projectId;

    @BeforeEach void setUp() {
        service = new DeviceService(repository, projectService,
                quotaPolicyProvider, projectQuotaService, topologyService, roleGuard, accessControl, accessTypes);
        projectId = UUID.randomUUID();
        TenantContext.set(new TenantScope(UUID.randomUUID(), projectId, UUID.randomUUID()));
    }

    @AfterEach void clear() { TenantContext.clear(); }

    @Test void ownerCreatesDevice() {
        when(projectService.requireRoleInProject(projectId)).thenReturn(ProjectRole.OWNER);
        allowDeviceQuota();
        Device device = service.create(projectId, null, "sensor_01", "温度传感器", null, "机房A");
        assertThat(device.deviceKey()).isEqualTo("sensor_01");
        assertThat(device.status()).isEqualTo(Device.Status.INACTIVE);
        verify(repository).create(device);
    }

    /** 配额策略与设备投影都返回 owner tenant 的正常共享池。 */
    private void allowDeviceQuota() {
        UUID ownerTenantId = UUID.randomUUID();
        EffectiveQuotaPolicy policy = mock(EffectiveQuotaPolicy.class);
        when(policy.tenantId()).thenReturn(ownerTenantId);
        when(quotaPolicyProvider.resolveTrustedProject(projectId)).thenReturn(policy);
        when(projectQuotaService.deviceQuotaStatus(projectId)).thenReturn(QuotaStatus.NORMAL);
    }

    /** 设备共享硬限拒绝新增，但不会删除或断开已有设备。 */
    @Test void deviceHardLimitRejectsCreate() {
        when(projectService.requireRoleInProject(projectId)).thenReturn(ProjectRole.OWNER);
        allowDeviceQuota();
        when(projectQuotaService.deviceQuotaStatus(projectId)).thenReturn(QuotaStatus.HARD_LIMIT);

        assertThatThrownBy(() -> service.create(projectId, null, "sensor_02", "传感器", null, null))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(DeviceErrorCode.DEVICE_QUOTA_EXCEEDED));
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.never()).create(org.mockito.ArgumentMatchers.any());
    }

    @Test void viewerCannotCreate() {
        when(projectService.requireRoleInProject(projectId)).thenReturn(ProjectRole.VIEWER);
        assertThatThrownBy(() -> service.create(projectId, null, "sensor_01", "传感器", null, null))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(DeviceErrorCode.DEVICE_WRITE_FORBIDDEN));
    }

    @Test void nonMemberCannotList() {
        when(projectService.requireRoleInProject(projectId)).thenThrow(
                new BusinessException(com.things.link.project.domain.ProjectErrorCode.PROJECT_NOT_FOUND));
        assertThatThrownBy(() -> service.list(projectId))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(
                                com.things.link.project.domain.ProjectErrorCode.PROJECT_NOT_FOUND));
    }

    @Test void viewerCanList() {
        when(projectService.requireRoleInProject(projectId)).thenReturn(ProjectRole.VIEWER);
        when(repository.search(org.mockito.ArgumentMatchers.any(DeviceSearchQuery.class),
                org.mockito.ArgumentMatchers.isNull())).thenReturn(CursorPage.last(List.of()));
        assertThat(service.list(projectId)).isEmpty();
    }

    /** 旧数组接口不得静默截断第 201 条，否则控制台会把不完整数据误当全量事实。 */
    @Test void legacyListRejectsMoreThanTwoHundredDevices() {
        when(projectService.requireRoleInProject(projectId)).thenReturn(ProjectRole.VIEWER);
        when(repository.search(org.mockito.ArgumentMatchers.any(DeviceSearchQuery.class),
                org.mockito.ArgumentMatchers.isNull())).thenReturn(CursorPage.of(List.of(), "next"));

        assertThatThrownBy(() -> service.list(projectId))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(
                                DeviceErrorCode.LEGACY_LIST_LIMIT_EXCEEDED));
    }

    @Test void cannotBindDeviceTypeFromAnotherProject() {
        UUID foreignTypeId = UUID.randomUUID();
        when(projectService.requireRoleInProject(projectId)).thenReturn(ProjectRole.OWNER);
        when(roleGuard.findTypeForControlPlane(projectId, foreignTypeId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.create(projectId, foreignTypeId, "sensor_01", "传感器", null, null))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(DeviceErrorCode.DEVICE_TYPE_NOT_FOUND));
    }
}
