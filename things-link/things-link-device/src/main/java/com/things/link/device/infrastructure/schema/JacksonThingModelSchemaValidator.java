package com.things.link.device.infrastructure.schema;

import com.things.link.device.application.schema.InvalidThingModelSchemaException;
import com.things.link.device.application.schema.ThingModelSchemaValidator;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 基于 Jackson 的 JSON Schema 定义校验器。
 *
 * <p>S2 固定支持 Draft 2020-12 的结构校验关键字子集；未知关键字允许保留以保持向前兼容，
 * 但平台实际消费的关键字必须具有正确类型。S3 数据校验扩展该端口时不得另建解析实现。</p>
 */
@Component
public class JacksonThingModelSchemaValidator implements ThingModelSchemaValidator {
    /** 防止租户用极深 Schema 消耗解析线程栈；该上限只约束定义嵌套，不约束设备载荷。 */
    private static final int MAX_DEPTH = 64;
    /** X-01 4.4：设备热路径只允许八层复合嵌套。 */
    public static final int COMPOSITE_MAX_DEPTH = 8;
    /** X-01 4.4：单个对象最多 64 个成员。 */
    public static final int COMPOSITE_MAX_PROPERTIES = 64;
    /** X-01 4.4：同构数组最多 256 项。 */
    public static final int COMPOSITE_MAX_ITEMS = 256;
    /** X-01 4.4：单字符串按 Unicode 码点最多 4096。 */
    public static final int COMPOSITE_MAX_STRING_CODE_POINTS = 4096;
    /** X-01 4.4：规范化复合值不得超过 16 KiB。 */
    public static final int COMPOSITE_MAX_VALUE_BYTES = 16 * 1024;
    /** X-01 4.4：控制面 Schema 不得超过 64 KiB。 */
    public static final int COMPOSITE_MAX_SCHEMA_BYTES = 64 * 1024;
    /** Profile 明确允许的关键字；未知约束 fail-closed，避免“保存但不执行”。 */
    private static final Set<String> COMPOSITE_KEYWORDS = Set.of(
            "type", "properties", "required", "additionalProperties", "maxProperties",
            "items", "minItems", "maxItems", "enum", "minimum", "maximum", "minLength", "maxLength");
    /** 复合成员键与顶层 propertyKey 使用同一安全字符集。 */
    private static final Pattern PROPERTY_KEY = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    /** JSON Schema 允许的标准基础类型。 */
    private static final Set<String> TYPES = Set.of("null", "boolean", "object", "array", "number", "integer", "string");
    /** JSON 解析器。 */
    private final ObjectMapper objectMapper;

    /** @param objectMapper Spring 统一配置的 JSON 解析器 */
    public JacksonThingModelSchemaValidator(ObjectMapper objectMapper) { this.objectMapper = objectMapper; }

    /** {@inheritDoc} */
    @Override
    public String validateDefinition(String schema, Profile profile) {
        String normalized = schema.strip();
        try {
            JsonNode root = objectMapper.readTree(normalized);
            if (root == null || !root.isObject()) {
                throw new InvalidThingModelSchemaException("Schema 根节点必须是对象");
            }
            if (profile == Profile.PROPERTY_COMPOSITE_V1) {
                requireByteLimit(normalized, COMPOSITE_MAX_SCHEMA_BYTES, "复合 Schema 超过 64 KiB 上限");
                validateCompositeSchemaNode(root, 1, true);
            } else {
                validateSchemaNode(root, 0);
            }
            return canonicalize(root).toString();
        } catch (InvalidThingModelSchemaException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new InvalidThingModelSchemaException("Schema 不是合法 JSON", exception);
        }
    }

    /** {@inheritDoc} */
    @Override
    public void validateInstance(String schema, JsonNode instance, Profile profile) {
        if (schema == null || schema.isBlank()) return;
        JsonNode definition;
        try {
            definition = objectMapper.readTree(schema);
        } catch (RuntimeException exception) {
            throw new InvalidThingModelSchemaException("已保存的 Schema 不是合法 JSON", exception);
        }
        JsonNode value = instance == null ? objectMapper.nullNode() : instance;
        if (profile == Profile.PROPERTY_COMPOSITE_V1) {
            String canonicalValue = canonicalize(value).toString();
            requireByteLimit(canonicalValue, COMPOSITE_MAX_VALUE_BYTES, "复合属性值超过 16 KiB 上限");
            validateCompositeInstanceNode(definition, value, 1);
            return;
        }
        validateInstanceNode(definition, value, 0);
    }

    /** {@inheritDoc} */
    @Override
    public String validateCompositeDefinition(String schema, String expectedRootType) {
        String normalized = validateDefinition(schema, Profile.PROPERTY_COMPOSITE_V1);
        JsonNode root = objectMapper.readTree(normalized);
        if (!Set.of("object", "array").contains(expectedRootType)
                || !expectedRootType.equals(root.get("type").asString()))
            throw new InvalidThingModelSchemaException("复合属性根类型与 dataType 不一致");
        return normalized;
    }

    /** 严格校验 X-01 PROPERTY_COMPOSITE_V1 定义。 */
    private void validateCompositeSchemaNode(JsonNode node, int depth, boolean root) {
        if (depth > COMPOSITE_MAX_DEPTH) throw new InvalidThingModelSchemaException("复合 Schema 嵌套超过 8 层上限");
        node.propertyNames().forEach(keyword -> {
            if (!COMPOSITE_KEYWORDS.contains(keyword))
                throw new InvalidThingModelSchemaException("复合 Schema 包含不支持关键字: " + keyword);
        });
        JsonNode type = node.get("type");
        if (type == null || !type.isString() || !Set.of("object", "array", "number", "string", "boolean").contains(type.asString()))
            throw new InvalidThingModelSchemaException("复合 Schema 的 type 必须是受支持的单一类型");
        if (root && !Set.of("object", "array").contains(type.asString()))
            throw new InvalidThingModelSchemaException("复合属性根类型只能是 object 或 array");
        switch (type.asString()) {
            case "object" -> validateCompositeObjectSchema(node, depth);
            case "array" -> validateCompositeArraySchema(node, depth);
            case "string" -> validateCompositeStringSchema(node);
            case "number" -> validateCompositeNumberSchema(node);
            default -> requireOnly(node, Set.of("type"));
        }
    }

    /** OBJECT 必须封闭、非空且显式声明不超过平台硬上限的 maxProperties。 */
    private void validateCompositeObjectSchema(JsonNode node, int depth) {
        requireOnly(node, Set.of("type", "properties", "required", "additionalProperties", "maxProperties"));
        JsonNode properties = node.get("properties");
        JsonNode additional = node.get("additionalProperties");
        JsonNode maximum = node.get("maxProperties");
        if (properties == null || !properties.isObject() || properties.isEmpty())
            throw new InvalidThingModelSchemaException("OBJECT properties 必须是非空对象");
        if (properties.size() > COMPOSITE_MAX_PROPERTIES)
            throw new InvalidThingModelSchemaException("OBJECT 定义成员超过 64 个上限");
        if (additional == null || !additional.isBoolean() || additional.asBoolean())
            throw new InvalidThingModelSchemaException("OBJECT 必须显式 additionalProperties=false");
        if (maximum == null || !maximum.isIntegralNumber() || maximum.asInt() < 1
                || maximum.asInt() > COMPOSITE_MAX_PROPERTIES || maximum.asInt() < properties.size())
            throw new InvalidThingModelSchemaException("OBJECT maxProperties 不合法");
        properties.properties().forEach(entry -> {
            if (!PROPERTY_KEY.matcher(entry.getKey()).matches())
                throw new InvalidThingModelSchemaException("OBJECT 成员键不合法");
            if (!entry.getValue().isObject()) throw new InvalidThingModelSchemaException("OBJECT 成员 Schema 必须是对象");
            validateCompositeSchemaNode(entry.getValue(), depth + 1, false);
        });
        validateRequired(node.get("required"), properties);
    }

    /** LIST 只允许单一 items Schema 和有界同构数组。 */
    private void validateCompositeArraySchema(JsonNode node, int depth) {
        requireOnly(node, Set.of("type", "items", "minItems", "maxItems"));
        JsonNode items = node.get("items");
        JsonNode minimum = node.get("minItems");
        JsonNode maximum = node.get("maxItems");
        if (items == null || !items.isObject()) throw new InvalidThingModelSchemaException("LIST items 必须是单一 Schema");
        if (maximum == null || !maximum.isIntegralNumber() || maximum.asInt() < 0
                || maximum.asInt() > COMPOSITE_MAX_ITEMS)
            throw new InvalidThingModelSchemaException("LIST maxItems 不合法");
        if (minimum != null && (!minimum.isIntegralNumber() || minimum.asInt() < 0 || minimum.asInt() > maximum.asInt()))
            throw new InvalidThingModelSchemaException("LIST minItems 不合法");
        validateCompositeSchemaNode(items, depth + 1, false);
    }

    /** 字符串约束不得超过平台码点硬上限。 */
    private static void validateCompositeStringSchema(JsonNode node) {
        requireOnly(node, Set.of("type", "enum", "minLength", "maxLength"));
        requireBoundedRange(node, "minLength", "maxLength", COMPOSITE_MAX_STRING_CODE_POINTS);
        JsonNode values = node.get("enum");
        if (values != null) {
            if (!values.isArray() || values.isEmpty()) throw new InvalidThingModelSchemaException("字符串 enum 必须是非空数组");
            Set<String> unique = new HashSet<>();
            values.forEach(value -> {
                if (!value.isString() || value.asString().isEmpty() || !unique.add(value.asString()))
                    throw new InvalidThingModelSchemaException("字符串 enum 必须非空且不重复");
            });
        }
    }

    /** 数值上下界必须均为有限数且顺序有效。 */
    private static void validateCompositeNumberSchema(JsonNode node) {
        requireOnly(node, Set.of("type", "minimum", "maximum"));
        JsonNode minimum = node.get("minimum");
        JsonNode maximum = node.get("maximum");
        if (minimum != null && !minimum.isNumber() || maximum != null && !maximum.isNumber())
            throw new InvalidThingModelSchemaException("数值边界必须是 JSON number");
        if (minimum != null && maximum != null && minimum.decimalValue().compareTo(maximum.decimalValue()) > 0)
            throw new InvalidThingModelSchemaException("minimum 不能大于 maximum");
    }

    /** 实例先过总字节限制，再按同一递归定义校验，整值失败而非部分接受。 */
    private void validateCompositeInstanceNode(JsonNode schema, JsonNode value, int depth) {
        if (depth > COMPOSITE_MAX_DEPTH) throw new InvalidThingModelSchemaException("复合属性值嵌套超过 8 层上限");
        String type = schema.get("type").asString();
        if (!matchesSingleType(type, value) || value.isNull())
            throw new InvalidThingModelSchemaException("复合属性值类型不符合 Schema");
        if (value.isObject()) {
            if (value.size() > schema.get("maxProperties").asInt())
                throw new InvalidThingModelSchemaException("复合属性对象成员超限");
            JsonNode required = schema.get("required");
            if (required != null) required.forEach(field -> {
                if (!value.has(field.asString())) throw new InvalidThingModelSchemaException("复合属性缺少必填成员");
            });
            JsonNode properties = schema.get("properties");
            value.properties().forEach(entry -> {
                JsonNode childSchema = properties.get(entry.getKey());
                if (childSchema == null) throw new InvalidThingModelSchemaException("复合属性包含未声明成员");
                validateCompositeInstanceNode(childSchema, entry.getValue(), depth + 1);
            });
        } else if (value.isArray()) {
            requireRange(schema, "minItems", "maxItems", value.size());
            value.forEach(item -> validateCompositeInstanceNode(schema.get("items"), item, depth + 1));
        } else if (value.isString()) {
            int codePoints = value.asString().codePointCount(0, value.asString().length());
            if (codePoints > COMPOSITE_MAX_STRING_CODE_POINTS)
                throw new InvalidThingModelSchemaException("复合属性字符串超过 4096 码点");
            validateStringInstance(schema, value.asString());
            JsonNode values = schema.get("enum");
            if (values != null && !contains(values, value)) throw new InvalidThingModelSchemaException("复合属性值不在枚举中");
        } else if (value.isNumber()) {
            if (!Double.isFinite(value.asDouble()))
                throw new InvalidThingModelSchemaException("复合属性数值必须是有限 JSON number");
            validateNumberInstance(schema, value);
        }
    }

    /** required 只能引用 properties，且自身不得重复。 */
    private static void validateRequired(JsonNode required, JsonNode properties) {
        if (required == null) return;
        if (!required.isArray()) throw new InvalidThingModelSchemaException("required 必须是数组");
        Set<String> unique = new HashSet<>();
        required.forEach(value -> {
            if (!value.isString() || !properties.has(value.asString()) || !unique.add(value.asString()))
                throw new InvalidThingModelSchemaException("required 包含未知或重复成员");
        });
    }

    /** 严格 Profile 节点不得携带不适用于其类型的关键字。 */
    private static void requireOnly(JsonNode node, Set<String> allowed) {
        node.propertyNames().forEach(keyword -> {
            if (!allowed.contains(keyword)) throw new InvalidThingModelSchemaException("关键字不适用于当前类型: " + keyword);
        });
    }

    /** 校验字符串长度区间并钉住平台上限。 */
    private static void requireBoundedRange(JsonNode node, String minimumName, String maximumName, int upperBound) {
        JsonNode minimum = node.get(minimumName);
        JsonNode maximum = node.get(maximumName);
        if (minimum != null && (!minimum.isIntegralNumber() || minimum.asInt() < 0)
                || maximum != null && (!maximum.isIntegralNumber() || maximum.asInt() < 0 || maximum.asInt() > upperBound)
                || minimum != null && maximum != null && minimum.asInt() > maximum.asInt())
            throw new InvalidThingModelSchemaException("字符串长度约束不合法");
    }

    /** UTF-8 字节上限使用规范文本计算，转义和多字节字符不能绕过。 */
    private static void requireByteLimit(String json, int limit, String message) {
        if (json.getBytes(StandardCharsets.UTF_8).length > limit) throw new InvalidThingModelSchemaException(message);
    }

    /** 递归排序对象键；数组顺序和重复元素保持不变。 */
    private JsonNode canonicalize(JsonNode node) {
        if (node.isObject()) {
            ObjectNode result = objectMapper.createObjectNode();
            List<String> names = new ArrayList<>();
            node.propertyNames().forEach(names::add);
            names.stream().sorted().forEach(name -> result.set(name, canonicalize(node.get(name))));
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = objectMapper.createArrayNode();
            node.forEach(value -> result.add(canonicalize(value)));
            return result;
        }
        return node.deepCopy();
    }

    /** 校验 S2 已承诺支持的实例约束子集；未知关键字继续保持向前兼容。 */
    private void validateInstanceNode(JsonNode schema, JsonNode value, int depth) {
        if (depth > MAX_DEPTH) throw new InvalidThingModelSchemaException("命令载荷嵌套超过平台上限");
        JsonNode type = schema.get("type");
        if (type != null && !matchesType(type, value))
            throw new InvalidThingModelSchemaException("命令载荷类型不符合 Schema");
        JsonNode enumValues = schema.get("enum");
        if (enumValues != null && !contains(enumValues, value))
            throw new InvalidThingModelSchemaException("命令载荷不在 Schema 枚举中");
        if (value.isObject()) validateObjectInstance(schema, value, depth);
        if (value.isArray()) validateArrayInstance(schema, value, depth);
        if (value.isString()) validateStringInstance(schema, value.asString());
        if (value.isNumber()) validateNumberInstance(schema, value);
    }

    /** 校验对象必填字段、已声明属性和 additionalProperties=false。 */
    private void validateObjectInstance(JsonNode schema, JsonNode value, int depth) {
        JsonNode required = schema.get("required");
        if (required != null) required.forEach(field -> {
            if (!field.isString() || !value.has(field.asString()))
                throw new InvalidThingModelSchemaException("命令载荷缺少必填字段");
        });
        JsonNode properties = schema.get("properties");
        if (properties != null) properties.properties().forEach(entry -> {
            JsonNode child = value.get(entry.getKey());
            if (child != null) validateInstanceNode(entry.getValue(), child, depth + 1);
        });
        JsonNode additional = schema.get("additionalProperties");
        if (additional != null && additional.isBoolean() && !additional.asBoolean() && properties != null) {
            value.propertyNames().forEach(field -> {
                if (!properties.has(field)) throw new InvalidThingModelSchemaException("命令载荷包含未声明字段");
            });
        }
    }

    /** 校验数组长度与元素 Schema。 */
    private void validateArrayInstance(JsonNode schema, JsonNode value, int depth) {
        requireRange(schema, "minItems", "maxItems", value.size());
        JsonNode items = schema.get("items");
        if (items != null && items.isObject()) value.forEach(item -> validateInstanceNode(items, item, depth + 1));
    }

    /** 校验字符串长度。 */
    private static void validateStringInstance(JsonNode schema, String value) {
        requireRange(schema, "minLength", "maxLength", value.codePointCount(0, value.length()));
    }

    /** 校验数值上下界；定义校验已保证边界关键字若存在就是数值。 */
    private static void validateNumberInstance(JsonNode schema, JsonNode value) {
        double number = value.asDouble();
        JsonNode minimum = schema.get("minimum");
        JsonNode maximum = schema.get("maximum");
        if (minimum != null && minimum.isNumber() && number < minimum.asDouble()
                || maximum != null && maximum.isNumber() && number > maximum.asDouble())
            throw new InvalidThingModelSchemaException("命令载荷数值超出 Schema 范围");
    }

    /** 判断实例是否匹配字符串或数组形式的 type。 */
    private static boolean matchesType(JsonNode type, JsonNode value) {
        if (type.isString()) return matchesSingleType(type.asString(), value);
        for (JsonNode candidate : type) if (candidate.isString() && matchesSingleType(candidate.asString(), value)) return true;
        return false;
    }

    /** JSON Schema 基础类型判定。 */
    private static boolean matchesSingleType(String type, JsonNode value) {
        return switch (type) {
            case "null" -> value.isNull();
            case "boolean" -> value.isBoolean();
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "number" -> value.isNumber();
            case "integer" -> value.isIntegralNumber();
            case "string" -> value.isString();
            default -> true;
        };
    }

    /** 判断 enum 数组是否包含完整 JSON 值。 */
    private static boolean contains(JsonNode values, JsonNode value) {
        for (JsonNode candidate : values) if (candidate.equals(value)) return true;
        return false;
    }

    /** 校验非负整数上下界。 */
    private static void requireRange(JsonNode schema, String minimumField, String maximumField, int value) {
        JsonNode minimum = schema.get(minimumField);
        JsonNode maximum = schema.get(maximumField);
        if (minimum != null && value < minimum.asInt() || maximum != null && value > maximum.asInt())
            throw new InvalidThingModelSchemaException("命令载荷长度不符合 Schema");
    }

    /** @param node 当前 Schema 节点 @param depth 当前嵌套深度 */
    private void validateSchemaNode(JsonNode node, int depth) {
        if (depth > MAX_DEPTH) throw new InvalidThingModelSchemaException("Schema 嵌套超过平台上限");
        validateType(node.get("type"));
        requireObject(node, "properties");
        requireArray(node, "required");
        requireSchemaOrBoolean(node, "additionalProperties", depth);
        requireSchema(node, "items", depth);
        requireArray(node, "enum");
        requireNonNegativeInteger(node, "minLength");
        requireNonNegativeInteger(node, "maxLength");
        requireNonNegativeInteger(node, "minItems");
        requireNonNegativeInteger(node, "maxItems");
        JsonNode properties = node.get("properties");
        if (properties != null) properties.properties().forEach(entry -> validateSchemaValue(entry.getValue(), depth + 1));
        for (String keyword : Set.of("allOf", "anyOf", "oneOf")) {
            JsonNode schemas = node.get(keyword);
            if (schemas != null) {
                if (!schemas.isArray() || schemas.isEmpty()) throw new InvalidThingModelSchemaException(keyword + " 必须是非空数组");
                schemas.forEach(child -> validateSchemaValue(child, depth + 1));
            }
        }
        requireSchema(node, "not", depth);
    }

    /** @param type type 关键字 */
    private void validateType(JsonNode type) {
        if (type == null) return;
        if (type.isString()) {
            if (!TYPES.contains(type.asString())) throw new InvalidThingModelSchemaException("未知的 Schema type");
            return;
        }
        if (!type.isArray() || type.isEmpty()) throw new InvalidThingModelSchemaException("type 必须是字符串或非空数组");
        type.forEach(value -> {
            if (!value.isString() || !TYPES.contains(value.asString()))
                throw new InvalidThingModelSchemaException("type 数组包含非法类型");
        });
    }

    /** @param parent 父节点 @param field 字段名 */
    private static void requireObject(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value != null && !value.isObject()) throw new InvalidThingModelSchemaException(field + " 必须是对象");
    }

    /** @param parent 父节点 @param field 字段名 */
    private static void requireArray(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value != null && !value.isArray()) throw new InvalidThingModelSchemaException(field + " 必须是数组");
    }

    /** @param parent 父节点 @param field 字段名 */
    private static void requireNonNegativeInteger(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        if (value != null && (!value.isIntegralNumber() || value.asLong() < 0))
            throw new InvalidThingModelSchemaException(field + " 必须是非负整数");
    }

    /** @param parent 父节点 @param field 字段名 @param depth 当前深度 */
    private void requireSchema(JsonNode parent, String field, int depth) {
        JsonNode value = parent.get(field);
        if (value != null) validateSchemaValue(value, depth + 1);
    }

    /** @param parent 父节点 @param field 字段名 @param depth 当前深度 */
    private void requireSchemaOrBoolean(JsonNode parent, String field, int depth) {
        JsonNode value = parent.get(field);
        if (value != null && !value.isBoolean()) validateSchemaValue(value, depth + 1);
    }

    /** @param value 子 Schema @param depth 当前深度 */
    private void validateSchemaValue(JsonNode value, int depth) {
        if (!value.isObject() && !value.isBoolean()) throw new InvalidThingModelSchemaException("子 Schema 必须是对象或布尔值");
        if (value.isObject()) validateSchemaNode(value, depth);
    }
}
