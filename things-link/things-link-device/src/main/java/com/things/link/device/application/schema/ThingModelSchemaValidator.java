package com.things.link.device.application.schema;

import tools.jackson.databind.JsonNode;

/**
 * 物模型 JSON Schema 校验端口。
 *
 * <p>S2 保存定义与 S3 设备消息校验必须复用同一入口，避免控制台接受的契约在数据面被另一套规则拒绝。</p>
 */
public interface ThingModelSchemaValidator {
    /** 同一递归内核支持的两个显式 Profile；禁止调用方靠未知关键字行为猜测语义。 */
    enum Profile {
        /** 既有命令 Schema 的向前兼容子集。 */
        COMMAND_COMPAT,
        /** X-01 冻结的属性 OBJECT/LIST 严格子集。 */
        PROPERTY_COMPOSITE_V1
    }

    /**
     * 校验并规范化一份 JSON Schema 定义。
     * @param schema 原始 Schema 文本
     * @return 去除首尾空白后的 Schema
     * @throws InvalidThingModelSchemaException 定义不是受支持的 JSON Schema
     */
    default String validateDefinition(String schema) {
        return validateDefinition(schema, Profile.COMMAND_COMPAT);
    }

    /**
     * 按明确 Profile 校验并返回规范 JSON；相同语义的键顺序与空白必须得到相同文本。
     * @param schema 原始 Schema 文本
     * @param profile 校验 Profile
     * @return UTF-8 规范 JSON
     */
    String validateDefinition(String schema, Profile profile);

    /**
     * 校验严格属性 Profile 并同时钉住顶层类型，避免调用方用嵌套节点的 type 字符串误判根类型。
     * @param schema 原始 Schema @param expectedRootType object 或 array @return 规范 Schema
     */
    String validateCompositeDefinition(String schema, String expectedRootType);

    /**
     * 按已冻结的物模型 Schema 校验一个命令请求或响应实例。
     *
     * @param schema 已通过定义校验的 JSON Schema；空值表示不约束载荷
     * @param instance 待校验 JSON 值
     * @throws InvalidThingModelSchemaException 实例不满足 Schema 时抛出
     */
    default void validateInstance(String schema, JsonNode instance) {
        validateInstance(schema, instance, Profile.COMMAND_COMPAT);
    }

    /** @param schema 已校验 Schema @param instance 待校验值 @param profile 校验 Profile */
    void validateInstance(String schema, JsonNode instance, Profile profile);
}
