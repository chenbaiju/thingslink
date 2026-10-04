package com.things.link.device.application;

import com.things.link.device.domain.*;
import com.things.link.project.application.ProjectService;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.error.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeviceConnectionServiceTests {
    @Mock private DeviceConnectionRepository repository;
    @Mock private DeviceRepository deviceRepository;
    @Mock private ProjectService projectService;
    private DeviceConnectionService service;
    private UUID projectId, deviceId;

    @BeforeEach void setUp() {
        service = new DeviceConnectionService(repository, deviceRepository, projectService);
        projectId = UUID.randomUUID(); deviceId = UUID.randomUUID();
    }

    @Test void memberCanListConnections() {
        when(projectService.requireRoleInProject(projectId)).thenReturn(ProjectRole.VIEWER);
        when(deviceRepository.findById(projectId, deviceId)).thenReturn(Optional.of(
                new Device(deviceId, UUID.randomUUID(), projectId, null, null, "d", "d", null, Device.Status.INACTIVE, null, null, Instant.now())));
        when(repository.findByDevice(projectId, deviceId)).thenReturn(List.of());
        assertThat(service.list(projectId, deviceId)).isEmpty();
        verify(repository).findByDevice(projectId, deviceId);
    }

    @Test void nonExistentDeviceRejected() {
        when(projectService.requireRoleInProject(projectId)).thenReturn(ProjectRole.VIEWER);
        when(deviceRepository.findById(projectId, deviceId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.list(projectId, deviceId))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(DeviceErrorCode.DEVICE_NOT_FOUND));
    }
}
