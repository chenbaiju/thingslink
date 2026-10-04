package com.things.link.device.application;

import com.things.link.device.domain.Device;
import com.things.link.device.domain.DeviceErrorCode;
import com.things.link.device.domain.DeviceGroup;
import com.things.link.device.domain.DeviceGroupRepository;
import com.things.link.device.domain.DeviceGroupRule;
import com.things.link.device.domain.DeviceRepository;
import com.things.link.device.domain.DeviceSearchQuery;
import com.things.link.device.domain.DeviceTag;
import com.things.link.project.application.ProjectService;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.tenant.TenantContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 设备组与标签用例；动态成员只在查询路径按白名单规则实时计算。 */
@Service
public class DeviceGroupService {
    /** 设备组、成员和标签持久化端口。 */
    private final DeviceGroupRepository repository;
    /** 项目成员与角色校验端口。 */
    private final ProjectService projectService;
    /** 设备归属校验端口。 */
    private final DeviceRepository deviceRepository;
    /** @param repository 组仓储 @param projectService 项目服务 @param deviceRepository 设备仓储 */
    public DeviceGroupService(DeviceGroupRepository repository,
                              ProjectService projectService,
                              DeviceRepository deviceRepository) {
        this.repository = repository;
        this.projectService = projectService;
        this.deviceRepository = deviceRepository;
    }

    /** @param projectId 项目 ID @return 项目所有有效设备组 */
    @Transactional(readOnly = true)
    public List<DeviceGroup> list(UUID projectId) {
        requireMember(projectId);
        return repository.findGroups(projectId);
    }

    /** 创建静态或动态设备组。 */
    @Transactional
    public DeviceGroup create(UUID projectId, String name, String description,
                              DeviceGroup.Type type, DeviceGroupRule rule) {
        requireWrite(projectId);
        validateTypeAndRule(type, rule);
        var scope = TenantContext.current()
                .orElseThrow(() -> new IllegalStateException("没有租户上下文"));
        DeviceGroup group = new DeviceGroup(Uuid7.generate(), scope.tenantId(), projectId,
                normalizeName(name), normalizeDescription(description), type, rule, Instant.now());
        try {
            repository.createGroup(group);
            return group;
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(DeviceErrorCode.DEVICE_GROUP_NAME_CONFLICT);
        }
    }

    /** 更新显示信息和动态规则；组类型不可变，避免静态成员被静默重新解释。 */
    @Transactional
    public DeviceGroup update(UUID projectId, UUID groupId, DeviceGroup.Type requestedType,
                              String name, String description, DeviceGroupRule rule) {
        requireWrite(projectId);
        DeviceGroup current = requireGroup(projectId, groupId);
        if (current.type() != requestedType) {
            throw new BusinessException(DeviceErrorCode.DEVICE_GROUP_RULE_INVALID, "设备组类型创建后不可修改");
        }
        validateTypeAndRule(current.type(), rule);
        DeviceGroup updated = new DeviceGroup(current.id(), current.tenantId(), current.projectId(),
                normalizeName(name), normalizeDescription(description), current.type(), rule, current.createdAt());
        try {
            if (!repository.updateGroup(updated)) {
                throw new BusinessException(DeviceErrorCode.DEVICE_GROUP_NOT_FOUND);
            }
            return updated;
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(DeviceErrorCode.DEVICE_GROUP_NAME_CONFLICT);
        }
    }

    /** 软删除设备组并清理其静态成员。 */
    @Transactional
    public void delete(UUID projectId, UUID groupId) {
        requireWrite(projectId);
        if (!repository.softDeleteGroup(projectId, groupId)) {
            throw new BusinessException(DeviceErrorCode.DEVICE_GROUP_NOT_FOUND);
        }
    }

    /** 原子替换静态组成员；任何无效设备都会使事务在写入前失败。 */
    @Transactional
    public void replaceMembers(UUID projectId, UUID groupId, List<UUID> deviceIds) {
        requireWrite(projectId);
        DeviceGroup group = requireGroup(projectId, groupId);
        if (group.type() != DeviceGroup.Type.STATIC) {
            throw new BusinessException(DeviceErrorCode.DEVICE_GROUP_RULE_INVALID, "动态设备组不能维护静态成员");
        }
        List<UUID> normalized = deviceIds == null ? List.of() : deviceIds.stream().distinct().toList();
        if (normalized.stream().anyMatch(id -> deviceRepository.findById(projectId, id).isEmpty())) {
            throw new BusinessException(DeviceErrorCode.DEVICE_NOT_FOUND);
        }
        repository.replaceMembers(projectId, groupId, normalized);
    }

    /** @return 静态成员或动态规则的即时结果 */
    @Transactional(readOnly = true, timeout = 3)
    public List<Device> members(UUID projectId, UUID groupId) {
        requireMember(projectId);
        DeviceGroup group = requireGroup(projectId, groupId);
        var query = new DeviceSearchQuery(projectId, null, Set.of(), Set.of(), groupId,
                null, null, null, 200);
        var page = deviceRepository.search(query, group);
        if (page.hasMore()) {
            throw new BusinessException(DeviceErrorCode.LEGACY_LIST_LIMIT_EXCEEDED);
        }
        return page.items();
    }

    /** 新增或覆盖一个设备键值标签。 */
    @Transactional
    public void putTag(UUID projectId, UUID deviceId, String key, String value) {
        requireWrite(projectId);
        requireDevice(projectId, deviceId);
        var scope = TenantContext.current()
                .orElseThrow(() -> new IllegalStateException("没有租户上下文"));
        repository.upsertTag(new DeviceTag(Uuid7.generate(), scope.tenantId(), projectId, deviceId,
                key.strip(), value.strip()));
    }

    /** @return 设备标签；设备不存在与跨项目统一返回 30020 */
    @Transactional(readOnly = true)
    public List<DeviceTag> tags(UUID projectId, UUID deviceId) {
        requireMember(projectId);
        requireDevice(projectId, deviceId);
        return repository.findTags(projectId, deviceId);
    }

    /** 删除指定键的设备标签；标签不存在仍成功，保持管理接口幂等。 */
    @Transactional
    public void deleteTag(UUID projectId, UUID deviceId, String key) {
        requireWrite(projectId);
        requireDevice(projectId, deviceId);
        repository.deleteTag(projectId, deviceId, key.strip());
    }

    /** 返回项目角色；非成员由项目服务统一伪装为项目不存在。 */
    private ProjectRole requireMember(UUID projectId) {
        return projectService.requireRoleInProject(projectId);
    }

    /** 设备组和标签写操作只允许 OWNER/ADMIN。 */
    private void requireWrite(UUID projectId) {
        ProjectRole role = requireMember(projectId);
        if (role != ProjectRole.OWNER && role != ProjectRole.ADMIN) {
            throw new BusinessException(DeviceErrorCode.DEVICE_WRITE_FORBIDDEN);
        }
    }

    /** 返回有效设备组，跨项目 ID 与不存在使用同一错误。 */
    private DeviceGroup requireGroup(UUID projectId, UUID groupId) {
        return repository.findGroup(projectId, groupId)
                .orElseThrow(() -> new BusinessException(DeviceErrorCode.DEVICE_GROUP_NOT_FOUND));
    }

    /** 校验设备归属，避免标签入口泄露其他项目设备。 */
    private void requireDevice(UUID projectId, UUID deviceId) {
        if (deviceRepository.findById(projectId, deviceId).isEmpty()) {
            throw new BusinessException(DeviceErrorCode.DEVICE_NOT_FOUND);
        }
    }

    /** 固定组类型与规则的对应关系。 */
    private static void validateTypeAndRule(DeviceGroup.Type type, DeviceGroupRule rule) {
        if (type == null || type == DeviceGroup.Type.STATIC && rule != null
                || type == DeviceGroup.Type.DYNAMIC && rule == null) {
            throw new BusinessException(DeviceErrorCode.DEVICE_GROUP_RULE_INVALID);
        }
    }

    /** 去除首尾空白并拒绝绕开 DTO 的内部空名称。 */
    private static String normalizeName(String name) {
        if (name == null || name.isBlank() || name.strip().length() > 128) {
            throw new BusinessException(DeviceErrorCode.DEVICE_GROUP_RULE_INVALID, "设备组名称不合法");
        }
        return name.strip();
    }

    /** 空说明统一保存为 null。 */
    private static String normalizeDescription(String description) {
        return description == null || description.isBlank() ? null : description.strip();
    }

}
