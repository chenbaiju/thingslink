package com.things.link.device.application;

import com.things.link.device.domain.DeviceErrorCode;
import com.things.link.device.domain.DeviceEventDefinition;
import com.things.link.device.domain.DeviceEventDefinitionRepository;
import com.things.link.device.domain.DevicePropertyDefinition;
import com.things.link.device.domain.DeviceType;
import com.things.link.device.domain.DeviceTypeRepository;
import com.things.link.project.application.ProjectService;
import com.things.link.shared.authz.ProjectRole;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.id.Uuid7;
import com.things.link.shared.tenant.TenantContext;
import com.things.link.shared.tenant.TenantScope;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/** 事件定义应用服务，保证事件主体和参数 Schema 在同一事务内变更。 */
@Service
public class DeviceEventDefinitionService {
    /** 事件仓储。 */ private final DeviceEventDefinitionRepository repository;
    /** 类型仓储。 */ private final DeviceTypeRepository typeRepository;
    /** 项目成员服务。 */ private final ProjectService projectService;

    /** @param repository 事件仓储 @param typeRepository 类型仓储 @param projectService 项目服务 */
    public DeviceEventDefinitionService(DeviceEventDefinitionRepository repository,
                                        DeviceTypeRepository typeRepository, ProjectService projectService) {
        this.repository = repository; this.typeRepository = typeRepository; this.projectService = projectService;
    }

    /** @param projectId 项目 ID @param deviceTypeId 类型 ID @return 事件列表 */
    @Transactional(readOnly = true)
    public List<DeviceEventDefinition> list(UUID projectId, UUID deviceTypeId) {
        requireMember(projectId); requireType(projectId, deviceTypeId);
        return repository.findByDeviceType(projectId, deviceTypeId);
    }

    /** 创建事件定义。 */
    @Transactional
    public DeviceEventDefinition create(UUID projectId, UUID deviceTypeId, String eventKey, String name,
                                        DeviceEventDefinition.Level level, String description, int sortOrder,
                                        List<DeviceEventDefinition.ParameterDraft> parameters) {
        requireManager(projectId); requireDraft(requireTypeForUpdate(projectId, deviceTypeId));
        TenantScope scope = TenantContext.current().orElseThrow(() -> new IllegalStateException("没有租户上下文"));
        DeviceEventDefinition value = new DeviceEventDefinition(Uuid7.generate(), scope.tenantId(), projectId,
                deviceTypeId, eventKey.strip(), name.strip(), level, blankToNull(description), sortOrder,
                normalizeParameters(parameters), Instant.now());
        try { repository.create(value); } catch (DuplicateKeyException exception) { throw conflict(exception); }
        return value;
    }

    /** 修改事件定义及完整参数 Schema。 */
    @Transactional
    public DeviceEventDefinition update(UUID projectId, UUID deviceTypeId, UUID id, String eventKey, String name,
                                        DeviceEventDefinition.Level level, String description, int sortOrder,
                                        List<DeviceEventDefinition.ParameterDraft> parameters) {
        requireManager(projectId); requireDraft(requireTypeForUpdate(projectId, deviceTypeId));
        DeviceEventDefinition current = repository.findById(projectId, deviceTypeId, id)
                .orElseThrow(() -> new BusinessException(DeviceErrorCode.EVENT_DEFINITION_NOT_FOUND));
        DeviceEventDefinition value = new DeviceEventDefinition(id, current.tenantId(), projectId, deviceTypeId,
                eventKey.strip(), name.strip(), level, blankToNull(description), sortOrder,
                normalizeParameters(parameters), current.createdAt());
        try {
            if (!repository.update(value)) throw new BusinessException(DeviceErrorCode.EVENT_DEFINITION_NOT_FOUND);
        } catch (DuplicateKeyException exception) { throw conflict(exception); }
        return value;
    }

    /** 软删除草稿类型中的事件定义。 */
    @Transactional
    public void delete(UUID projectId, UUID deviceTypeId, UUID id) {
        requireManager(projectId); requireDraft(requireTypeForUpdate(projectId, deviceTypeId));
        if (!repository.softDelete(projectId, deviceTypeId, id))
            throw new BusinessException(DeviceErrorCode.EVENT_DEFINITION_NOT_FOUND);
    }

    /** 校验参数键唯一和枚举选项，并生成新的参数 ID。 */
    private static List<DeviceEventDefinition.Parameter> normalizeParameters(
            List<DeviceEventDefinition.ParameterDraft> parameters) {
        if (parameters == null) return List.of();
        if (parameters.size() > 100) throw new BusinessException(DeviceErrorCode.EVENT_DEFINITION_PARAMETER_INVALID);
        HashSet<String> keys = new HashSet<>();
        return parameters.stream().map(parameter -> {
            if (parameter == null || parameter.parameterKey() == null || parameter.dataType() == null
                    || !java.util.Set.of(DevicePropertyDefinition.DataType.NUMBER, DevicePropertyDefinition.DataType.TEXT,
                    DevicePropertyDefinition.DataType.SWITCH, DevicePropertyDefinition.DataType.ENUM).contains(parameter.dataType())) {
                throw new BusinessException(DeviceErrorCode.EVENT_DEFINITION_PARAMETER_INVALID);
            }
            String key = parameter.parameterKey().strip();
            if (!key.matches("[A-Za-z0-9_-]{1,64}") || !keys.add(key))
                throw new BusinessException(DeviceErrorCode.EVENT_DEFINITION_PARAMETER_INVALID);
            List<String> options = null;
            if (parameter.dataType() == DevicePropertyDefinition.DataType.ENUM) {
                if (parameter.enumOptions() != null && (parameter.enumOptions().size() > 100
                        || parameter.enumOptions().stream().anyMatch(java.util.Objects::isNull)))
                    throw new BusinessException(DeviceErrorCode.EVENT_DEFINITION_PARAMETER_INVALID);
                options = parameter.enumOptions() == null ? List.of() : parameter.enumOptions().stream()
                        .map(String::strip).filter(value -> !value.isEmpty()).distinct().toList();
                if (options.isEmpty() || options.stream().anyMatch(option -> !DeviceEventSchema.validText(option, 64)))
                    throw new BusinessException(DeviceErrorCode.EVENT_DEFINITION_PARAMETER_INVALID);
            }
            return new DeviceEventDefinition.Parameter(Uuid7.generate(), key, parameter.name().strip(),
                    parameter.dataType(), parameter.required(), options, parameter.sortOrder());
        }).toList();
    }

    /** 将数据库唯一冲突转换为事件键或参数错误。 */
    private static BusinessException conflict(DuplicateKeyException exception) {
        String message = exception.getMostSpecificCause().getMessage();
        return new BusinessException(message != null
                && message.contains("dev_event_parameter_definition_event_key_uk")
                ? DeviceErrorCode.EVENT_DEFINITION_PARAMETER_INVALID : DeviceErrorCode.EVENT_DEFINITION_KEY_CONFLICT);
    }
    /** @param value 可空文本 @return 去空格值 */
    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value.strip(); }
    /** @param projectId 项目 ID @param id 类型 ID @return 类型 */
    private DeviceType requireType(UUID projectId, UUID id) {
        return typeRepository.findById(projectId, id)
                .orElseThrow(() -> new BusinessException(DeviceErrorCode.DEVICE_TYPE_NOT_FOUND));
    }
    /** @param projectId 项目 ID @param id 类型 ID @return 已锁定类型 */
    private DeviceType requireTypeForUpdate(UUID projectId, UUID id) { return typeRepository.findByIdForUpdate(projectId, id).orElseThrow(() -> new BusinessException(DeviceErrorCode.DEVICE_TYPE_NOT_FOUND)); }
    /** @param type 类型 */
    private static void requireDraft(DeviceType type) {
        if (type.status() != DeviceType.Status.DRAFT)
            throw new BusinessException(DeviceErrorCode.DEVICE_TYPE_PUBLISHED_IMMUTABLE);
    }
    /** @param projectId 项目 ID @return 成员角色 */
    private ProjectRole requireMember(UUID projectId) {
        return projectService.roleInProject(projectId)
                .orElseThrow(() -> new BusinessException(DeviceErrorCode.DEVICE_TYPE_NOT_FOUND));
    }
    /** @param projectId 项目 ID */
    private void requireManager(UUID projectId) {
        ProjectRole role = requireMember(projectId);
        if (role != ProjectRole.OWNER && role != ProjectRole.ADMIN)
            throw new BusinessException(DeviceErrorCode.DEVICE_TYPE_WRITE_FORBIDDEN);
    }
}
