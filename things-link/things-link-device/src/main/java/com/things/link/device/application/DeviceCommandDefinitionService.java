package com.things.link.device.application;

import com.things.link.device.domain.DeviceCommandDefinition;
import com.things.link.device.domain.DeviceCommandDefinitionRepository;
import com.things.link.device.domain.DeviceErrorCode;
import com.things.link.device.domain.DeviceType;
import com.things.link.device.domain.DeviceTypeRepository;
import com.things.link.device.application.schema.InvalidThingModelSchemaException;
import com.things.link.device.application.schema.ThingModelSchemaValidator;
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
import java.util.List;
import java.util.UUID;

/** 命令定义应用服务，在同一事务内完成创建、更新与软删除。 */
@Service
public class DeviceCommandDefinitionService {
    /** 命令仓储。 */ private final DeviceCommandDefinitionRepository repository;
    /** 类型仓储。 */ private final DeviceTypeRepository typeRepository;
    /** 项目成员服务。 */ private final ProjectService projectService;
    /** 物模型共用 Schema 校验器。 */ private final ThingModelSchemaValidator schemaValidator;

    /** @param repository 命令仓储 @param typeRepository 类型仓储 @param projectService 项目服务 */
    public DeviceCommandDefinitionService(DeviceCommandDefinitionRepository repository,
                                        DeviceTypeRepository typeRepository, ProjectService projectService,
                                        ThingModelSchemaValidator schemaValidator) {
        this.repository = repository; this.typeRepository = typeRepository; this.projectService = projectService;
        this.schemaValidator = schemaValidator;
    }

    /** @param projectId 项目 ID @param deviceTypeId 类型 ID @return 命令列表 */
    @Transactional(readOnly = true)
    public List<DeviceCommandDefinition> list(UUID projectId, UUID deviceTypeId) {
        requireMember(projectId); requireType(projectId, deviceTypeId);
        return repository.findByDeviceType(projectId, deviceTypeId);
    }

    /** 创建命令定义。 */
    @Transactional
    public DeviceCommandDefinition create(UUID projectId, UUID deviceTypeId, String commandKey, String name,
                                        String description, String inputSchema, String outputSchema,
                                        int timeoutSeconds, int sortOrder) {
        requireManager(projectId); requireDraft(requireTypeForUpdate(projectId, deviceTypeId));
        TenantScope scope = TenantContext.current().orElseThrow(() -> new IllegalStateException("没有租户上下文"));
        DeviceCommandDefinition value = new DeviceCommandDefinition(Uuid7.generate(), scope.tenantId(), projectId,
                deviceTypeId, commandKey.strip(), name.strip(), blankToNull(description),
                validateSchema(inputSchema), validateSchema(outputSchema), timeoutSeconds, sortOrder, Instant.now());
        try { repository.create(value); } catch (DuplicateKeyException exception) { throw conflict(exception); }
        return value;
    }

    /** 修改命令定义。 */
    @Transactional
    public DeviceCommandDefinition update(UUID projectId, UUID deviceTypeId, UUID id, String commandKey, String name,
                                        String description, String inputSchema, String outputSchema,
                                        int timeoutSeconds, int sortOrder) {
        requireManager(projectId); requireDraft(requireTypeForUpdate(projectId, deviceTypeId));
        DeviceCommandDefinition current = repository.findById(projectId, deviceTypeId, id)
                .orElseThrow(() -> new BusinessException(DeviceErrorCode.COMMAND_DEFINITION_NOT_FOUND));
        DeviceCommandDefinition value = new DeviceCommandDefinition(id, current.tenantId(), projectId, deviceTypeId,
                commandKey.strip(), name.strip(), blankToNull(description),
                validateSchema(inputSchema), validateSchema(outputSchema), timeoutSeconds, sortOrder, current.createdAt());
        try {
            if (!repository.update(value)) throw new BusinessException(DeviceErrorCode.COMMAND_DEFINITION_NOT_FOUND);
        } catch (DuplicateKeyException exception) { throw conflict(exception); }
        return value;
    }

    /** 软删除草稿类型中的命令定义。 */
    @Transactional
    public void delete(UUID projectId, UUID deviceTypeId, UUID id) {
        requireManager(projectId); requireDraft(requireTypeForUpdate(projectId, deviceTypeId));
        if (!repository.softDelete(projectId, deviceTypeId, id))
            throw new BusinessException(DeviceErrorCode.COMMAND_DEFINITION_NOT_FOUND);
    }

    /** 将数据库唯一冲突翻译为命令键重复错误。 */
    private static BusinessException conflict(DuplicateKeyException exception) {
        return new BusinessException(DeviceErrorCode.COMMAND_DEFINITION_KEY_CONFLICT);
    }
    /** @param value 可空文本 @return 去空格值 */
    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value.strip(); }
    /** @param value 可空 Schema @return 已校验 Schema */
    private String validateSchema(String value) {
        String normalized = blankToNull(value); if (normalized == null) return null;
        try { return schemaValidator.validateDefinition(normalized); }
        catch (InvalidThingModelSchemaException exception) { throw new BusinessException(DeviceErrorCode.COMMAND_DEFINITION_SCHEMA_INVALID); }
    }
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
