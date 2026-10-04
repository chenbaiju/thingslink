package com.things.link.device.application;

import com.things.link.device.domain.*;
import com.things.link.project.application.ProjectService;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import com.things.link.support.cache.CacheInvalidationPublisher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeviceCredentialServiceTests {
    @Mock private DeviceCredentialRepository repository;
    @Mock private DeviceRepository deviceRepository;
    @Mock private DeviceTypeRepository typeRepository;
    @Mock private ProjectService projectService;
    @Mock private ProjectLifecycleAccessService lifecycle;
    @Mock private CacheInvalidationPublisher cacheInvalidationPublisher;
    @Mock private DeviceAccessControlService accessControl;
    private DeviceCredentialService service;
    private UUID projectId, deviceId;

    @BeforeEach void setUp() {
        service = new DeviceCredentialService(repository, deviceRepository, typeRepository,
                projectService, lifecycle, cacheInvalidationPublisher, accessControl);
        projectId = UUID.randomUUID(); deviceId = UUID.randomUUID();
        TenantContext.set(new TenantScope(UUID.randomUUID(), projectId, UUID.randomUUID()));
    }

    @AfterEach void clear() { TenantContext.clear(); }

    @Test void ownerGeneratesCredentialWithPlainSecret() {
        allowManager();
        DeviceCredential c = service.generate(projectId, deviceId);
        assertThat(c.authType()).isEqualTo(DeviceCredential.AuthType.ACCESS_TOKEN);
        assertThat(c.plainSecret()).hasSize(64); // 32 bytes = 64 hex chars
        assertThat(c.credentialHash()).hasSize(64); // SHA-256 = 64 hex chars
        verify(repository).revokeByDeviceAndType(projectId, deviceId, DeviceCredential.AuthType.ACCESS_TOKEN);
        verify(repository).create(any());
        verify(repository).incrementCredentialVersion(projectId, deviceId);
        verify(accessControl).invalidateCredentials(TenantContext.require().tenantId(), projectId, deviceId);
    }


    /** 跨租户协作者签发的新凭据必须使用项目真实归属，而不是调用者JWT tenant。 */
    @Test void generatedCredentialUsesProjectOwnerTenant() {
        UUID ownerTenant = UUID.randomUUID();
        when(projectService.requireRoleInProject(projectId)).thenReturn(ProjectRole.ADMIN);
        when(projectService.requireProjectTenant(projectId)).thenReturn(ownerTenant);
        when(deviceRepository.findById(projectId, deviceId)).thenReturn(Optional.of(
                new Device(deviceId, ownerTenant, projectId, null, null, "sensor_01", "传感器",
                        null, Device.Status.INACTIVE, null, null, Instant.now())));
        service.generate(projectId, deviceId);
        verify(repository).create(org.mockito.ArgumentMatchers.argThat(value -> ownerTenant.equals(value.tenantId())));
    }

    @Test void plainSecretIsUniquePerGeneration() {
        allowManager();
        String s1 = service.generate(projectId, deviceId).plainSecret();
        String s2 = service.generate(projectId, deviceId).plainSecret();
        assertThat(s1).isNotEqualTo(s2);
    }

    @Test void viewerCannotGenerate() {
        when(projectService.requireRoleInProject(projectId)).thenReturn(ProjectRole.VIEWER);
        assertThatThrownBy(() -> service.generate(projectId, deviceId))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(DeviceErrorCode.DEVICE_WRITE_FORBIDDEN));
    }

    @Test void nonExistentDeviceRejected() {
        when(projectService.requireRoleInProject(projectId)).thenReturn(ProjectRole.OWNER);
        when(projectService.requireProjectTenant(projectId)).thenReturn(TenantContext.current().orElseThrow().tenantId());
        when(deviceRepository.findById(projectId, deviceId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.generate(projectId, deviceId))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(DeviceErrorCode.DEVICE_NOT_FOUND));
    }

    /** 撤销不能只失效新连接认证缓存，还必须安排断开旧会话。 */
    @Test void revokeSchedulesExistingSessionTermination() {
        allowManager();
        UUID credentialId = UUID.randomUUID();
        when(repository.softDelete(projectId, deviceId, credentialId)).thenReturn(true);

        service.revoke(projectId, deviceId, credentialId);

        verify(repository).incrementCredentialVersion(projectId, deviceId);
        verify(accessControl).invalidateCredentials(TenantContext.require().tenantId(), projectId, deviceId);
    }

    /** 子设备经网关间接接入，签发独立接入凭据被拒绝。 */
    @Test void subDeviceCannotGenerateCredential() {
        when(projectService.requireRoleInProject(projectId)).thenReturn(ProjectRole.OWNER);
        when(projectService.requireProjectTenant(projectId)).thenReturn(TenantContext.current().orElseThrow().tenantId());
        UUID typeId = UUID.randomUUID();
        when(deviceRepository.findById(projectId, deviceId)).thenReturn(Optional.of(
                new Device(deviceId, UUID.randomUUID(), projectId, typeId, null, "sub_01", "子设备",
                        null, Device.Status.INACTIVE, null, null, Instant.now())));
        when(typeRepository.findById(projectId, typeId)).thenReturn(Optional.of(
                new DeviceType(typeId, UUID.randomUUID(), projectId, "sub", "子设备",
                        DeviceType.DeviceKind.SUB_DEVICE, DeviceType.PayloadProtocol.STANDARD,
                        DeviceType.NetworkType.ZIGBEE, 1, DeviceType.Status.DRAFT, null, null, Instant.now())));
        assertThatThrownBy(() -> service.generate(projectId, deviceId))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(DeviceErrorCode.SUB_DEVICE_CREDENTIAL_FORBIDDEN));
    }

    private void allowManager() {
        when(projectService.requireRoleInProject(projectId)).thenReturn(ProjectRole.OWNER);
        when(projectService.requireProjectTenant(projectId)).thenReturn(TenantContext.current().orElseThrow().tenantId());
        when(deviceRepository.findById(projectId, deviceId)).thenReturn(Optional.of(
                new Device(deviceId, UUID.randomUUID(), projectId, null, null, "sensor_01", "传感器",
                        null, Device.Status.INACTIVE, null, null, Instant.now())));
    }
}
