package com.things.link.device.application;

import com.things.link.device.application.schema.InvalidThingModelSchemaException;
import com.things.link.device.application.schema.ThingModelSchemaValidator;
import com.things.link.device.domain.DeviceErrorCode;
import com.things.link.device.domain.DeviceType;
import com.things.link.device.domain.DeviceTypeRepository;
import com.things.link.device.domain.ThingModelVersion;
import com.things.link.device.domain.ThingModelVersionRepository;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.id.Uuid7;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 物模型不可变版本发布端口，集中执行 Profile、版本线路和保守兼容性校验。 */
@Service
public class ThingModelVersionService {
    /** 版本仓储。 */ private final ThingModelVersionRepository repository;
    /** 设备类型仓储。 */ private final DeviceTypeRepository typeRepository;
    /** 唯一 Schema 递归内核。 */ private final ThingModelSchemaValidator schemaValidator;
    /** JSON 解析器。 */ private final ObjectMapper objectMapper;

    /** @param repository 版本仓储 @param typeRepository 类型仓储 @param schemaValidator Schema 端口 @param objectMapper JSON 解析器 */
    public ThingModelVersionService(ThingModelVersionRepository repository, DeviceTypeRepository typeRepository,
                                    ThingModelSchemaValidator schemaValidator, ObjectMapper objectMapper) {
        this.repository = repository; this.typeRepository = typeRepository;
        this.schemaValidator = schemaValidator; this.objectMapper = objectMapper;
    }

    /**
     * 发布完整模型快照；不接收局部补丁，避免版本摘要依赖读取时拼装。
     * @param projectId 项目 ID @param deviceTypeId 类型 ID @param versionNumber major.minor.patch
     * @param requestedLevel 调用方声明变化级别 @param rawSnapshot 属性/事件/命令完整快照
     * @return 不可变发布版本
     */
    @Transactional
    public ThingModelVersion publish(UUID projectId, UUID deviceTypeId, String versionNumber,
                                     ThingModelVersion.ChangeLevel requestedLevel, JsonNode rawSnapshot) {
        DeviceType type = typeRepository.findByIdForUpdate(projectId, deviceTypeId)
                .orElseThrow(() -> new BusinessException(DeviceErrorCode.DEVICE_TYPE_NOT_FOUND));
        if (type.status() != DeviceType.Status.PUBLISHED)
            throw new BusinessException(DeviceErrorCode.THING_MODEL_VERSION_LINE_INVALID);
        int[] next = parseVersion(versionNumber);
        String canonical = validateAndCanonicalize(rawSnapshot);
        ThingModelVersion previous = repository.findLatest(projectId, deviceTypeId).orElse(null);
        ThingModelVersion.ChangeLevel required = classify(previous, canonical);
        if (requestedLevel.ordinal() < required.ordinal() || !followsLine(previous, next, requestedLevel))
            throw new BusinessException(DeviceErrorCode.THING_MODEL_VERSION_LINE_INVALID);
        try {
            return repository.create(Uuid7.generate(), type.tenantId(), projectId, deviceTypeId,
                    versionNumber, requestedLevel, canonical, Instant.now());
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(DeviceErrorCode.THING_MODEL_VERSION_LINE_INVALID);
        }
    }

    /** 校验快照固定三段和每个复合属性 Schema，并递归排序对象键。 */
    private String validateAndCanonicalize(JsonNode snapshot) {
        if (snapshot == null || !snapshot.isObject())
            throw new BusinessException(DeviceErrorCode.THING_MODEL_VERSION_LINE_INVALID);
        snapshot.propertyNames().forEach(name -> {
            if (!Set.of("properties", "events", "commands").contains(name))
                throw new BusinessException(DeviceErrorCode.THING_MODEL_VERSION_LINE_INVALID);
        });
        for (String section : List.of("properties", "events", "commands")) {
            if (!snapshot.has(section) || !snapshot.get(section).isObject())
                throw new BusinessException(DeviceErrorCode.THING_MODEL_VERSION_LINE_INVALID);
        }
        snapshot.get("properties").properties().forEach(entry -> validateProperty(entry.getValue()));
        return canonicalize(snapshot).toString();
    }

    /** 复合定义必须带严格 Schema，标量不得夹带另一份解释来源。 */
    private void validateProperty(JsonNode property) {
        if (!property.isObject() || !property.has("dataType") || !property.get("dataType").isString())
            throw new BusinessException(DeviceErrorCode.THING_MODEL_VERSION_LINE_INVALID);
        String dataType = property.get("dataType").asString();
        JsonNode schema = property.get("schema");
        if (Set.of("OBJECT", "LIST").contains(dataType)) {
            if (schema == null || !schema.isObject())
                throw new BusinessException(DeviceErrorCode.PROPERTY_DEFINITION_SCHEMA_INVALID);
            try {
                schemaValidator.validateCompositeDefinition(
                        schema.toString(), dataType.equals("OBJECT") ? "object" : "array");
            } catch (InvalidThingModelSchemaException exception) {
                throw new BusinessException(DeviceErrorCode.PROPERTY_DEFINITION_SCHEMA_INVALID);
            }
        } else if (!Set.of("NUMBER", "TEXT", "SWITCH", "ENUM").contains(dataType) || schema != null) {
            throw new BusinessException(DeviceErrorCode.PROPERTY_DEFINITION_SCHEMA_INVALID);
        }
    }

    /** 保守分类：纯新增为 MINOR，任何删除或既有定义变化均为 MAJOR，完全相同为 PATCH。 */
    private ThingModelVersion.ChangeLevel classify(ThingModelVersion previous, String currentText) {
        if (previous == null) return ThingModelVersion.ChangeLevel.MAJOR;
        JsonNode oldValue = objectMapper.readTree(previous.modelSnapshot());
        JsonNode current = objectMapper.readTree(currentText);
        if (oldValue.equals(current)) return ThingModelVersion.ChangeLevel.PATCH;
        for (String section : List.of("properties", "events", "commands")) {
            JsonNode oldSection = oldValue.get(section);
            JsonNode newSection = current.get(section);
            for (var entry : oldSection.properties()) {
                if (!entry.getValue().equals(newSection.get(entry.getKey())))
                    return ThingModelVersion.ChangeLevel.MAJOR;
            }
        }
        return ThingModelVersion.ChangeLevel.MINOR;
    }

    /** 版本数字必须与变化级别一致且严格向前；回滚通过设备绑定，不重发旧版本。 */
    private static boolean followsLine(ThingModelVersion previous, int[] next, ThingModelVersion.ChangeLevel level) {
        if (previous == null) return next[0] == 1 && next[1] == 0 && next[2] == 0 && level == ThingModelVersion.ChangeLevel.MAJOR;
        int[] old = parseVersion(previous.versionNumber());
        return switch (level) {
            case PATCH -> next[0] == old[0] && next[1] == old[1] && next[2] == old[2] + 1;
            case MINOR -> next[0] == old[0] && next[1] == old[1] + 1 && next[2] == 0;
            case MAJOR -> next[0] == old[0] + 1 && next[1] == 0 && next[2] == 0;
        };
    }

    /** 严格解析无前导零非负语义版本。 */
    private static int[] parseVersion(String value) {
        if (value == null || !value.matches("(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)"))
            throw new BusinessException(DeviceErrorCode.THING_MODEL_VERSION_LINE_INVALID);
        String[] parts = value.split("\\.");
        try {
            return new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2])};
        } catch (NumberFormatException exception) {
            throw new BusinessException(DeviceErrorCode.THING_MODEL_VERSION_LINE_INVALID);
        }
    }

    /** 递归排序对象键但保持数组顺序，供数据库 jsonb 再规范化并计算版本化摘要。 */
    private JsonNode canonicalize(JsonNode node) {
        if (node.isObject()) {
            ObjectNode result = objectMapper.createObjectNode();
            List<String> names = new ArrayList<>(); node.propertyNames().forEach(names::add);
            names.stream().sorted().forEach(name -> result.set(name, canonicalize(node.get(name))));
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = objectMapper.createArrayNode(); node.forEach(value -> result.add(canonicalize(value)));
            return result;
        }
        return node.deepCopy();
    }
}
