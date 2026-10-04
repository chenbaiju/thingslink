package com.things.link.device.infrastructure.schema;

import com.things.link.device.application.schema.InvalidThingModelSchemaException;
import com.things.link.device.application.schema.ThingModelSchemaValidator;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** JSON Schema 定义校验器测试，固定 S2 支持范围并为 S3 复用提供回归保护。 */
class JacksonThingModelSchemaValidatorTests {
    /** 被测校验器。 */
    private final JacksonThingModelSchemaValidator validator =
            new JacksonThingModelSchemaValidator(new ObjectMapper());

    /** 合法对象 Schema 可包含属性、必填项及数组元素定义。 */
    @Test void acceptsSupportedObjectSchema() {
        String schema = """
                {"type":"object","required":["delay"],"properties":{
                  "delay":{"type":"integer"},"targets":{"type":"array","items":{"type":"string"}}
                }}
                """;
        assertThat(validator.validateDefinition(schema)).contains("properties");
    }

    /** 普通 JSON 不等于 JSON Schema，根节点必须是对象。 */
    @Test void rejectsNonObjectRoot() {
        assertThatThrownBy(() -> validator.validateDefinition("[]"))
                .isInstanceOf(InvalidThingModelSchemaException.class);
    }

    /** 非法 JSON 在进入 PostgreSQL jsonb 转换前即被拒绝。 */
    @Test void rejectsMalformedJson() {
        assertThatThrownBy(() -> validator.validateDefinition("{broken"))
                .isInstanceOf(InvalidThingModelSchemaException.class);
    }

    /** 已知关键字的值类型错误必须拒绝，避免保存后数据面无法解释。 */
    @Test void rejectsInvalidKeywordValue() {
        assertThatThrownBy(() -> validator.validateDefinition("{\"type\":\"object\",\"required\":true}"))
                .isInstanceOf(InvalidThingModelSchemaException.class);
    }

    /** 严格 Profile 规范化对象键，输入空白和键顺序不改变摘要输入。 */
    @Test void canonicalizesCompositeSchema() {
        String first = validator.validateDefinition("""
                {"maxProperties":2,"properties":{"b":{"type":"boolean"},"a":{"type":"number"}},
                 "type":"object","additionalProperties":false}
                """, ThingModelSchemaValidator.Profile.PROPERTY_COMPOSITE_V1);
        String second = validator.validateDefinition("""
                {"type":"object","additionalProperties":false,"properties":{
                 "a":{"type":"number"},"b":{"type":"boolean"}},"maxProperties":2}
                """, ThingModelSchemaValidator.Profile.PROPERTY_COMPOSITE_V1);
        assertThat(first).isEqualTo(second);
    }

    /** 未实现关键字必须 fail-closed，不能制造“控制面已校验、数据面未执行”的假安全。 */
    @Test void rejectsUnknownCompositeKeyword() {
        assertThatThrownBy(() -> validator.validateDefinition("""
                {"type":"array","maxItems":2,"items":{"type":"string","pattern":".*"}}
                """, ThingModelSchemaValidator.Profile.PROPERTY_COMPOSITE_V1))
                .isInstanceOf(InvalidThingModelSchemaException.class)
                .hasMessageContaining("不支持关键字");
    }

    /** 属性声明类型必须与 Schema 根类型一致，避免 OBJECT/LIST 共用校验器时串线。 */
    @Test void rejectsCompositeRootTypeMismatch() {
        assertThatThrownBy(() -> validator.validateCompositeDefinition("""
                {"type":"array","maxItems":2,"items":{"type":"boolean"}}
                """, "object"))
                .isInstanceOf(InvalidThingModelSchemaException.class)
                .hasMessageContaining("根类型");
    }

    /** 16 KiB 按规范 UTF-8 字节执行，四字节 emoji 不能按 Java char 数绕过。 */
    @Test void enforcesCanonicalUtf8ValueLimit() {
        String schema = validator.validateDefinition("""
                {"type":"object","additionalProperties":false,"maxProperties":1,
                 "required":["text"],"properties":{"text":{"type":"string","maxLength":4096}}}
                """, ThingModelSchemaValidator.Profile.PROPERTY_COMPOSITE_V1);
        ObjectMapper mapper = new ObjectMapper();
        JsonNode accepted = mapper.createObjectNode().put("text", "中".repeat(4096));
        validator.validateInstance(schema, accepted, ThingModelSchemaValidator.Profile.PROPERTY_COMPOSITE_V1);
        JsonNode rejected = mapper.createObjectNode().put("text", "😀".repeat(4096));
        assertThat(rejected.toString().getBytes(StandardCharsets.UTF_8).length)
                .isGreaterThan(JacksonThingModelSchemaValidator.COMPOSITE_MAX_VALUE_BYTES);
        assertThatThrownBy(() -> validator.validateInstance(
                schema, rejected, ThingModelSchemaValidator.Profile.PROPERTY_COMPOSITE_V1))
                .isInstanceOf(InvalidThingModelSchemaException.class)
                .hasMessageContaining("16 KiB");
    }

    /** 最坏合法深度、64 成员与 256 元素在边界内通过，任一再增加必须被拒绝。 */
    @Test void freezesDepthMemberAndArrayCapacityBoundaries() {
        String schema = nestedArraySchema(7);
        String normalized = validator.validateDefinition(schema, ThingModelSchemaValidator.Profile.PROPERTY_COMPOSITE_V1);
        JsonNode value = nestedArrayValue(7);
        validator.validateInstance(normalized, value, ThingModelSchemaValidator.Profile.PROPERTY_COMPOSITE_V1);
        assertThatThrownBy(() -> validator.validateDefinition(nestedArraySchema(8),
                ThingModelSchemaValidator.Profile.PROPERTY_COMPOSITE_V1))
                .isInstanceOf(InvalidThingModelSchemaException.class)
                .hasMessageContaining("8 层");
        assertThatThrownBy(() -> validator.validateDefinition("""
                {"type":"array","maxItems":257,"items":{"type":"boolean"}}
                """, ThingModelSchemaValidator.Profile.PROPERTY_COMPOSITE_V1))
                .isInstanceOf(InvalidThingModelSchemaException.class);
    }

    /** 构造根节点在内共八层的同构数组 Schema。 */
    private static String nestedArraySchema(int nestedArrays) {
        String child = "{\"type\":\"boolean\"}";
        for (int index = 0; index < nestedArrays; index++) {
            child = "{\"type\":\"array\",\"minItems\":1,\"maxItems\":256,\"items\":" + child + "}";
        }
        return child;
    }

    /** 构造与 {@link #nestedArraySchema(int)} 同深度的最小实例。 */
    private static JsonNode nestedArrayValue(int nestedArrays) {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode child = mapper.booleanNode(true);
        for (int index = 0; index < nestedArrays; index++) {
            ArrayNodeHolder holder = new ArrayNodeHolder(mapper.createArrayNode());
            holder.value().add(child);
            child = holder.value();
        }
        return child;
    }

    /** 只为避免在循环中暴露 Jackson 可变数组的临时持有者。 */
    private record ArrayNodeHolder(tools.jackson.databind.node.ArrayNode value) { }
}
