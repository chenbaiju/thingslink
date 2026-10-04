package com.things.link.device.application;

import com.things.link.device.domain.DeviceErrorCode;
import com.things.link.device.domain.DevicePropertyDefinition;
import com.things.link.device.domain.DevicePropertyDefinitionRepository;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 属性定义应用服务，统一承担权限、发布冻结和数据类型约束校验。 */
@Service
public class DevicePropertyDefinitionService {
    /** 属性仓储。 */ private final DevicePropertyDefinitionRepository repository;
    /** 类型仓储。 */ private final DeviceTypeRepository typeRepository;
    /** 项目成员服务。 */ private final ProjectService projectService;
    /** 命令与复合属性共用的 Schema 递归内核。 */ private final ThingModelSchemaValidator schemaValidator;

    /** @param repository 属性仓储 @param typeRepository 类型仓储 @param projectService 项目服务 */
    public DevicePropertyDefinitionService(DevicePropertyDefinitionRepository repository,
                                           DeviceTypeRepository typeRepository, ProjectService projectService,
                                           ThingModelSchemaValidator schemaValidator) {
        this.repository = repository; this.typeRepository = typeRepository; this.projectService = projectService;
        this.schemaValidator = schemaValidator;
    }

    /** @param projectId 项目 ID @param deviceTypeId 类型 ID @return 属性定义列表 */
    @Transactional(readOnly = true)
    public List<DevicePropertyDefinition> list(UUID projectId, UUID deviceTypeId) {
        requireMember(projectId); requireType(projectId, deviceTypeId);
        return repository.findByDeviceType(projectId, deviceTypeId);
    }

    /** 创建草稿设备类型的属性定义。 */
    @Transactional
    public DevicePropertyDefinition create(UUID projectId, UUID deviceTypeId, String propertyKey, String name,
                                           DevicePropertyDefinition.AccessType accessType,
                                           DevicePropertyDefinition.DataType dataType, String unit,
                                           Integer decimalPlaces, BigDecimal minimumValue, BigDecimal maximumValue,
                                           List<String> enumOptions, String onLabel, String offLabel, String schema,
                                           int sortOrder) {
        requireManager(projectId); requireDraft(requireTypeForUpdate(projectId, deviceTypeId));
        TenantScope scope = TenantContext.current().orElseThrow(() -> new IllegalStateException("没有租户上下文"));
        TypeConfig config = normalize(dataType, unit, decimalPlaces, minimumValue, maximumValue,
                enumOptions, onLabel, offLabel, schema);
        DevicePropertyDefinition value = new DevicePropertyDefinition(Uuid7.generate(), scope.tenantId(), projectId,
                deviceTypeId, propertyKey.strip(), name.strip(), accessType, dataType, config.unit(),
                config.decimalPlaces(), config.minimumValue(), config.maximumValue(), config.enumOptions(),
                config.onLabel(), config.offLabel(), config.schema(), sortOrder, Instant.now());
        try { repository.create(value); } catch (DuplicateKeyException exception) { throw conflict(); }
        return value;
    }

    /** B-X1a 前标量调用兼容入口；复合类型必须调用携带 Schema 的重载。 */
    public DevicePropertyDefinition create(UUID projectId, UUID deviceTypeId, String propertyKey, String name,
                                           DevicePropertyDefinition.AccessType accessType,
                                           DevicePropertyDefinition.DataType dataType, String unit,
                                           Integer decimalPlaces, BigDecimal minimumValue, BigDecimal maximumValue,
                                           List<String> enumOptions, String onLabel, String offLabel, int sortOrder) {
        return create(projectId, deviceTypeId, propertyKey, name, accessType, dataType, unit, decimalPlaces,
                minimumValue, maximumValue, enumOptions, onLabel, offLabel, null, sortOrder);
    }

    /** 修改草稿设备类型的属性定义。 */
    @Transactional
    public DevicePropertyDefinition update(UUID projectId, UUID deviceTypeId, UUID id, String propertyKey,
                                           String name, DevicePropertyDefinition.AccessType accessType,
                                           DevicePropertyDefinition.DataType dataType, String unit,
                                           Integer decimalPlaces, BigDecimal minimumValue, BigDecimal maximumValue,
                                           List<String> enumOptions, String onLabel, String offLabel, String schema,
                                           int sortOrder) {
        requireManager(projectId); requireDraft(requireTypeForUpdate(projectId, deviceTypeId));
        DevicePropertyDefinition current = repository.findById(projectId, deviceTypeId, id)
                .orElseThrow(() -> new BusinessException(DeviceErrorCode.PROPERTY_DEFINITION_NOT_FOUND));
        TypeConfig config = normalize(dataType, unit, decimalPlaces, minimumValue, maximumValue,
                enumOptions, onLabel, offLabel, schema);
        DevicePropertyDefinition value = new DevicePropertyDefinition(current.id(), current.tenantId(), projectId,
                deviceTypeId, propertyKey.strip(), name.strip(), accessType, dataType, config.unit(),
                config.decimalPlaces(), config.minimumValue(), config.maximumValue(), config.enumOptions(),
                config.onLabel(), config.offLabel(), config.schema(), sortOrder, current.createdAt());
        try {
            if (!repository.update(value)) throw new BusinessException(DeviceErrorCode.PROPERTY_DEFINITION_NOT_FOUND);
        } catch (DuplicateKeyException exception) { throw conflict(); }
        return value;
    }

    /** B-X1a 前标量调用兼容入口；只补空 Schema，不改变既有标量更新语义。 */
    public DevicePropertyDefinition update(UUID projectId, UUID deviceTypeId, UUID id, String propertyKey,
                                           String name, DevicePropertyDefinition.AccessType accessType,
                                           DevicePropertyDefinition.DataType dataType, String unit,
                                           Integer decimalPlaces, BigDecimal minimumValue, BigDecimal maximumValue,
                                           List<String> enumOptions, String onLabel, String offLabel, int sortOrder) {
        return update(projectId, deviceTypeId, id, propertyKey, name, accessType, dataType, unit, decimalPlaces,
                minimumValue, maximumValue, enumOptions, onLabel, offLabel, null, sortOrder);
    }

    /** 软删除草稿设备类型的属性定义。 */
    @Transactional
    public void delete(UUID projectId, UUID deviceTypeId, UUID id) {
        requireManager(projectId); requireDraft(requireTypeForUpdate(projectId, deviceTypeId));
        if (!repository.softDelete(projectId, deviceTypeId, id)) {
            throw new BusinessException(DeviceErrorCode.PROPERTY_DEFINITION_NOT_FOUND);
        }
    }

    /** 清除与当前数据类型无关的幽灵配置，并校验真正影响消息合法性的约束。 */
    private TypeConfig normalize(DevicePropertyDefinition.DataType type, String unit, Integer decimalPlaces,
                                        BigDecimal minimumValue, BigDecimal maximumValue, List<String> enumOptions,
                                        String onLabel, String offLabel, String schema) {
        if (type == DevicePropertyDefinition.DataType.OBJECT || type == DevicePropertyDefinition.DataType.LIST) {
            if (schema == null || schema.isBlank())
                throw new BusinessException(DeviceErrorCode.PROPERTY_DEFINITION_SCHEMA_INVALID);
            try {
                String expected = type == DevicePropertyDefinition.DataType.OBJECT ? "object" : "array";
                String normalized = schemaValidator.validateCompositeDefinition(schema, expected);
                return new TypeConfig(null, null, null, null, null, null, null, normalized);
            } catch (InvalidThingModelSchemaException exception) {
                throw new BusinessException(DeviceErrorCode.PROPERTY_DEFINITION_SCHEMA_INVALID);
            }
        }
        if (type == DevicePropertyDefinition.DataType.NUMBER) {
            if (decimalPlaces != null && (decimalPlaces < 0 || decimalPlaces > 10)
                    || minimumValue != null && maximumValue != null && minimumValue.compareTo(maximumValue) > 0) {
                throw new BusinessException(DeviceErrorCode.PROPERTY_DEFINITION_CONSTRAINT_INVALID);
            }
            requireNoSchema(schema);
            return new TypeConfig(blankToNull(unit), decimalPlaces, minimumValue, maximumValue, null, null, null, null);
        }
        if (type == DevicePropertyDefinition.DataType.ENUM) {
            List<String> normalized = enumOptions == null ? List.of() : enumOptions.stream()
                    .map(String::strip).filter(value -> !value.isEmpty()).distinct().toList();
            if (normalized.isEmpty()) throw new BusinessException(DeviceErrorCode.PROPERTY_DEFINITION_CONSTRAINT_INVALID);
            requireNoSchema(schema);
            return new TypeConfig(null, null, null, null, normalized, null, null, null);
        }
        if (type == DevicePropertyDefinition.DataType.SWITCH) {
            requireNoSchema(schema);
            return new TypeConfig(null, null, null, null, null, blankToNull(onLabel), blankToNull(offLabel), null);
        }
        requireNoSchema(schema);
        return new TypeConfig(null, null, null, null, null, null, null, null);
    }

    /** 标量属性携带 Schema 会产生两份约束来源，必须拒绝而不是静默清理。 */
    private static void requireNoSchema(String schema) {
        if (schema != null && !schema.isBlank())
            throw new BusinessException(DeviceErrorCode.PROPERTY_DEFINITION_SCHEMA_INVALID);
    }

    /** @param value 可空文本 @return 去空格后文本，空白转换为空 */
    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value.strip(); }
    /** @return 稳定的唯一冲突错误 */
    private static BusinessException conflict() {
        return new BusinessException(DeviceErrorCode.PROPERTY_DEFINITION_KEY_CONFLICT);
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

    /** 数据类型专属配置。 */
    private record TypeConfig(String unit, Integer decimalPlaces, BigDecimal minimumValue, BigDecimal maximumValue,
                              List<String> enumOptions, String onLabel, String offLabel, String schema) { }
}
