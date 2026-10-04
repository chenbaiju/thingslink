package com.things.link.device.application;

import com.things.link.device.domain.*;
import com.things.link.project.application.ProjectService;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.error.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** 设备连接记录服务。S3-4 先提供读取接口；S3-5 EMQX 回调落地后写入。 */
@Service
public class DeviceConnectionService {
    private final DeviceConnectionRepository repository;
    private final DeviceRepository deviceRepository;
    private final ProjectService projectService;

    public DeviceConnectionService(DeviceConnectionRepository repository, DeviceRepository deviceRepository,
                                   ProjectService projectService) {
        this.repository = repository; this.deviceRepository = deviceRepository; this.projectService = projectService;
    }

    @Transactional(readOnly = true)
    public List<DeviceConnection> list(UUID projectId, UUID deviceId) {
        requireMember(projectId); requireDevice(projectId, deviceId);
        return repository.findByDevice(projectId, deviceId);
    }

    private void requireDevice(UUID projectId, UUID deviceId) {
        deviceRepository.findById(projectId, deviceId)
                .orElseThrow(() -> new BusinessException(DeviceErrorCode.DEVICE_NOT_FOUND));
    }

    private ProjectRole requireMember(UUID projectId) {
        return projectService.requireRoleInProject(projectId);
    }
}
