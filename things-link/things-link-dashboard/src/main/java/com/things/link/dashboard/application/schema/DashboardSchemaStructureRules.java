package com.things.link.dashboard.application.schema;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.Set;

/** 看板Schema内部共享的封闭结构、严格类型和默认注入规则。 */
final class DashboardSchemaStructureRules {
    /** 工具类不允许实例化。 */
    private DashboardSchemaStructureRules() {
    }

    /**
     * 要求节点为对象并拒绝未知字段。
     *
     * @param node 待校验节点
     * @param path 稳定JSON路径
     * @param allowedFields 允许字段闭集
     * @return 对象节点
     */
    static ObjectNode requireObject(JsonNode node, String path, Set<String> allowedFields) {
        ObjectNode object = requireObject(node, path);
        object.propertyNames().forEach(name -> {
            if (!allowedFields.contains(name)) {
                throw reject(DashboardSchemaValidationException.Reason.UNKNOWN_FIELD,
                        path, "包含合同未声明字段");
            }
        });
        return object;
    }

    /**
     * 要求节点为对象，字段闭集由调用层分支稍后提供。
     *
     * @param node 待校验节点
     * @param path 稳定JSON路径
     * @return 对象节点
     */
    static ObjectNode requireObject(JsonNode node, String path) {
        if (node == null || !node.isObject()) {
            throw reject(DashboardSchemaValidationException.Reason.TYPE_MISMATCH, path, "必须是对象");
        }
        return (ObjectNode) node;
    }

    /**
     * 递归拒绝任意字段或集合元素显式使用JSON null。
     *
     * @param node 当前节点
     * @param path 当前JSON路径
     */
    static void rejectNulls(JsonNode node, String path) {
        if (node.isNull()) {
            throw reject(DashboardSchemaValidationException.Reason.NULL_NOT_ALLOWED, path, "不接受null");
        }
        if (node.isObject()) {
            // 未知字段名属于外部输入，异常消息只保留结构位置，避免控制字符或超长名称进入日志。
            node.properties().forEach(entry -> rejectNulls(entry.getValue(), path + ".*"));
        } else if (node.isArray()) {
            for (int index = 0; index < node.size(); index++) rejectNulls(node.get(index), path + "[" + index + "]");
        }
    }

    /**
     * 返回必填字段，缺失与显式null由不同稳定原因表示。
     *
     * @param node 所属对象
     * @param name 字段名
     * @param path 所属对象路径
     * @return 字段节点
     */
    static JsonNode required(ObjectNode node, String name, String path) {
        JsonNode value = node.get(name);
        if (value == null) {
            throw reject(DashboardSchemaValidationException.Reason.REQUIRED_FIELD_MISSING,
                    path + "." + name, "为必填字段");
        }
        if (value.isNull()) {
            throw reject(DashboardSchemaValidationException.Reason.NULL_NOT_ALLOWED,
                    path + "." + name, "不接受null");
        }
        return value;
    }

    /**
     * 要求必填字段为字符串，不执行宽松转换。
     *
     * @param node 所属对象
     * @param name 字段名
     * @param path 所属对象路径
     * @return 原始字符串
     */
    static String requireString(ObjectNode node, String name, String path) {
        JsonNode value = required(node, name, path);
        if (!value.isString()) {
            throw reject(DashboardSchemaValidationException.Reason.TYPE_MISMATCH,
                    path + "." + name, "必须是字符串");
        }
        return value.asString();
    }

    /**
     * 要求节点为数组，不把标量或对象包装成集合。
     *
     * @param node 待校验节点
     * @param path 稳定JSON路径
     * @return 数组节点
     */
    static ArrayNode requireArray(JsonNode node, String path) {
        if (node == null || !node.isArray()) {
            throw reject(DashboardSchemaValidationException.Reason.TYPE_MISMATCH, path, "必须是数组");
        }
        return (ArrayNode) node;
    }

    /**
     * 要求必填字段为布尔值，不接受0/1或字符串。
     *
     * @param node 所属对象
     * @param name 字段名
     * @param path 所属对象路径
     * @return 原始布尔值
     */
    static boolean requireBoolean(ObjectNode node, String name, String path) {
        JsonNode value = required(node, name, path);
        if (!value.isBoolean()) {
            throw reject(DashboardSchemaValidationException.Reason.TYPE_MISMATCH,
                    path + "." + name, "必须是boolean");
        }
        return value.asBoolean();
    }

    /**
     * 要求必填字段使用JSON整数词法。
     *
     * @param node 所属对象
     * @param name 字段名
     * @param path 所属对象路径
     * @return 可安全表示的long整数
     */
    static long requireInteger(ObjectNode node, String name, String path) {
        JsonNode value = required(node, name, path);
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw reject(DashboardSchemaValidationException.Reason.TYPE_MISMATCH,
                    path + "." + name, "必须是JSON整数");
        }
        return value.asLong();
    }

    /**
     * 为缺失字段注入字符串默认值，保留用户显式填写的合法默认值。
     *
     * @param node 所属对象
     * @param name 字段名
     * @param value 唯一默认值
     */
    static void putDefault(ObjectNode node, String name, String value) {
        if (!node.has(name)) node.put(name, value);
    }

    /**
     * 为缺失字段注入整数默认值。
     *
     * @param node 所属对象
     * @param name 字段名
     * @param value 唯一默认值
     */
    static void putDefault(ObjectNode node, String name, int value) {
        if (!node.has(name)) node.put(name, value);
    }

    /**
     * 为缺失字段注入布尔默认值。
     *
     * @param node 所属对象
     * @param name 字段名
     * @param value 唯一默认值
     */
    static void putDefault(ObjectNode node, String name, boolean value) {
        if (!node.has(name)) node.put(name, value);
    }

    /**
     * 为缺失字段注入空数组默认值。
     *
     * @param node 所属对象
     * @param name 字段名
     */
    static void defaultArray(ObjectNode node, String name) {
        if (!node.has(name)) node.set(name, node.arrayNode());
    }

    /** 创建结构规则稳定异常。 */
    private static DashboardSchemaValidationException reject(
            DashboardSchemaValidationException.Reason reason, String path, String detail) {
        return new DashboardSchemaValidationException(reason, path, detail);
    }
}
