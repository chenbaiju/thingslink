package com.things.link.device.application;

import com.things.link.device.domain.Device;
import com.things.link.device.domain.DeviceErrorCode;
import com.things.link.device.domain.DeviceRepository;
import com.things.link.device.domain.DeviceShadow;
import com.things.link.device.domain.DeviceShadowRepository;
import com.things.link.project.application.ProjectService;
import com.things.link.project.application.ProjectLifecycleAccessService;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.error.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

/** 设备影子应用服务。desired 使用乐观锁并发控制，reported 由上行链路直接写入。 */
@Service
public class DeviceShadowService {
    private final DeviceShadowRepository repository;
    private final DeviceRepository deviceRepository;
    private final ProjectService projectService;
    /** 原事务项目持续写许可。 */ private final ProjectLifecycleAccessService lifecycle;
    /** JSON 解析器；先于 PostgreSQL jsonb 转换给调用方稳定的 400 错误。 */ private final ObjectMapper objectMapper;

    /**
     * @param repository 影子仓储
     * @param deviceRepository 设备仓储
     * @param projectService 项目成员与真实归属端口
     * @param lifecycle 原事务项目持续写许可
     * @param objectMapper JSON解析器
     */
    public DeviceShadowService(DeviceShadowRepository repository, DeviceRepository deviceRepository,
                               ProjectService projectService, ProjectLifecycleAccessService lifecycle, ObjectMapper objectMapper) {
        this.repository = repository; this.deviceRepository = deviceRepository; this.projectService = projectService;
        this.lifecycle = lifecycle; this.objectMapper = objectMapper;
    }

    /** 读取设备影子；ACTIVE首次访问持久化空影子，冻结项目返回不落库的空快照。 */
    @Transactional
    public DeviceShadow get(UUID projectId, UUID deviceId) {
        requireMember(projectId);
        Device device = requireDevice(projectId, deviceId);
        DeviceShadow existing = repository.findByDevice(projectId, deviceId).orElse(null);
        if (existing != null) return existing;
        UUID ownerTenant = projectService.requireProjectTenant(projectId);
        if (!lifecycle.lockActiveForWrite(ownerTenant, projectId)) {
            // 锁等待期间成员资格可能变化；先重新授权，不能向已失权账号泄露设备仍存在。
            requireMember(projectId);
            // 归档或与删除并发时仍返回只读空快照，不能让GET制造持久副作用。
            return new DeviceShadow(deviceId, device.tenantId(), projectId, null, null, 0,
                    java.time.Instant.now());
        }
        requireMember(projectId);
        device = requireDevice(projectId, deviceId);
        existing = repository.findByDevice(projectId, deviceId).orElse(null);
        if (existing != null) return existing;
        DeviceShadow shadow = new DeviceShadow(deviceId, device.tenantId(), projectId, null, null, 0,
                java.time.Instant.now());
        repository.createIfAbsent(shadow);
        return repository.findByDevice(projectId, deviceId)
                .orElseThrow(() -> new IllegalStateException("影子初始化后不可见"));
    }

    /** 更新 desired 期望状态，乐观锁保护。调用方应持有当前影子的 version。 */
    @Transactional
    public DeviceShadow updateDesired(UUID projectId, UUID deviceId, String desired, int version) {
        requireManager(projectId);
        UUID ownerTenant = projectService.requireProjectTenant(projectId);
        lifecycle.requireActiveForWrite(ownerTenant, projectId);
        requireManager(projectId);
        requireDevice(projectId, deviceId);
        validateObject(desired);
        if (!repository.updateDesired(projectId, deviceId, desired, version))
            throw new BusinessException(DeviceErrorCode.SHADOW_VERSION_CONFLICT);
        return repository.findByDevice(projectId, deviceId)
                .orElseThrow(() -> new IllegalStateException("影子更新后立即消失"));
    }

    /** 影子顶层必须是对象，后续属性合并逻辑依赖键值结构。 */
    private void validateObject(String desired) {
        try {
            JsonNode node = objectMapper.readTree(desired);
            if (node == null || !node.isObject()) throw new BusinessException(DeviceErrorCode.SHADOW_JSON_INVALID);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(DeviceErrorCode.SHADOW_JSON_INVALID);
        }
    }

    /** @return 项目范围内的持久设备；不存在时保持原30020。 */
    private Device requireDevice(UUID projectId, UUID deviceId) {
        return deviceRepository.findById(projectId, deviceId)
                .orElseThrow(() -> new BusinessException(DeviceErrorCode.DEVICE_NOT_FOUND));
    }

    private ProjectRole requireMember(UUID projectId) {
        return projectService.requireRoleInProject(projectId);
    }

    private void requireManager(UUID projectId) {
        ProjectRole role = requireMember(projectId);
        if (role != ProjectRole.OWNER && role != ProjectRole.ADMIN)
            throw new BusinessException(DeviceErrorCode.DEVICE_WRITE_FORBIDDEN);
    }
}
