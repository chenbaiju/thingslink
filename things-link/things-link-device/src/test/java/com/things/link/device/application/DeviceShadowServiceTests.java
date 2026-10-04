package com.things.link.device.application;

import com.things.link.device.domain.*;
import com.things.link.project.application.ProjectService;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeviceShadowServiceTests {
    @Mock private DeviceShadowRepository repository;
    @Mock private DeviceRepository deviceRepository;
    @Mock private ProjectService projectService;
    @Mock private ProjectLifecycleAccessService lifecycle;
    private DeviceShadowService service;
    private UUID projectId, deviceId;

    @BeforeEach void setUp() {
        service = new DeviceShadowService(repository, deviceRepository, projectService, lifecycle, new ObjectMapper());
        projectId = UUID.randomUUID(); deviceId = UUID.randomUUID();
        TenantContext.set(new TenantScope(UUID.randomUUID(), projectId, UUID.randomUUID()));
    }

    @AfterEach void clear() { TenantContext.clear(); }

    @Test void getAutoCreatesEmptyShadowWhenNotExists() {
        allowMember();
        DeviceShadow created = new DeviceShadow(deviceId, TenantContext.current().orElseThrow().tenantId(), projectId,
                null, null, 0, Instant.now());
        when(repository.findByDevice(projectId, deviceId))
                .thenReturn(Optional.empty(), Optional.empty(), Optional.of(created));
        DeviceShadow s = service.get(projectId, deviceId);
        assertThat(s.desired()).isNull();
        assertThat(s.reported()).isNull();
        assertThat(s.version()).isZero();
        verify(repository).createIfAbsent(any());
    }

    /** 归档许可失败时缺失影子只返回持久设备归属的空快照，不落库。 */
    @Test void missingShadowOnFrozenProjectReturnsSyntheticReadOnlySnapshot() {
        UUID ownerTenant = UUID.randomUUID();
        when(projectService.requireRoleInProject(projectId)).thenReturn(ProjectRole.VIEWER);
        when(projectService.requireProjectTenant(projectId)).thenReturn(ownerTenant);
        when(deviceRepository.findById(projectId, deviceId)).thenReturn(Optional.of(
                new Device(deviceId, ownerTenant, projectId, null, null, "sensor", "传感器",
                        null, Device.Status.INACTIVE, null, null, Instant.now())));
        when(repository.findByDevice(projectId, deviceId)).thenReturn(Optional.empty());
        when(lifecycle.lockActiveForWrite(ownerTenant, projectId)).thenReturn(false);
        DeviceShadow shadow = service.get(projectId, deviceId);
        assertThat(shadow.tenantId()).isEqualTo(ownerTenant);
        assertThat(shadow.version()).isZero();
        verify(projectService, org.mockito.Mockito.times(2)).requireRoleInProject(projectId);
        verify(repository, org.mockito.Mockito.never()).createIfAbsent(any());
    }

    @Test void getReturnsExistingShadow() {
        when(projectService.requireRoleInProject(projectId)).thenReturn(ProjectRole.VIEWER);
        when(deviceRepository.findById(projectId, deviceId)).thenReturn(Optional.of(
                new Device(deviceId, UUID.randomUUID(), projectId, null, null, "sensor", "传感器",
                        null, Device.Status.INACTIVE, null, null, Instant.now())));
        DeviceShadow existing = new DeviceShadow(deviceId, UUID.randomUUID(), projectId,
                "{\"temp\":20}", "{\"temp\":19}", 1, Instant.now());
        when(repository.findByDevice(projectId, deviceId)).thenReturn(Optional.of(existing));
        assertThat(service.get(projectId, deviceId)).isEqualTo(existing);
    }

    @Test void updateDesiredSucceedsWithCorrectVersion() {
        allowManager();
        when(repository.updateDesired(projectId, deviceId, "{\"temp\":25}", 1)).thenReturn(true);
        when(repository.findByDevice(projectId, deviceId)).thenReturn(Optional.of(
                new DeviceShadow(deviceId, UUID.randomUUID(), projectId, "{\"temp\":25}", null, 2, Instant.now())));
        DeviceShadow s = service.updateDesired(projectId, deviceId, "{\"temp\":25}", 1);
        assertThat(s.version()).isEqualTo(2);
    }

    @Test void updateDesiredRejectsVersionConflict() {
        allowManager();
        when(repository.updateDesired(projectId, deviceId, "{\"temp\":25}", 0)).thenReturn(false);
        assertThatThrownBy(() -> service.updateDesired(projectId, deviceId, "{\"temp\":25}", 0))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(DeviceErrorCode.SHADOW_VERSION_CONFLICT));
    }

    @Test void updateDesiredRejectsInvalidJsonObject() {
        allowManager();
        assertThatThrownBy(() -> service.updateDesired(projectId, deviceId, "not-json", 0))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(DeviceErrorCode.SHADOW_JSON_INVALID));
        assertThatThrownBy(() -> service.updateDesired(projectId, deviceId, "[]", 0))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(DeviceErrorCode.SHADOW_JSON_INVALID));
    }

    private void allowMember() {
        UUID ownerTenant = UUID.randomUUID();
        when(projectService.requireRoleInProject(projectId)).thenReturn(ProjectRole.VIEWER);
        when(projectService.requireProjectTenant(projectId)).thenReturn(ownerTenant);
        when(lifecycle.lockActiveForWrite(ownerTenant, projectId)).thenReturn(true);
        when(deviceRepository.findById(projectId, deviceId)).thenReturn(Optional.of(
                new Device(deviceId, ownerTenant, projectId, null, null, "sensor", "传感器",
                        null, Device.Status.INACTIVE, null, null, Instant.now())));
    }

    private void allowManager() {
        UUID ownerTenant = UUID.randomUUID();
        when(projectService.requireRoleInProject(projectId)).thenReturn(ProjectRole.OWNER);
        when(projectService.requireProjectTenant(projectId)).thenReturn(ownerTenant);
        when(deviceRepository.findById(projectId, deviceId)).thenReturn(Optional.of(
                new Device(deviceId, ownerTenant, projectId, null, null, "sensor", "传感器",
                        null, Device.Status.INACTIVE, null, null, Instant.now())));
    }
}
