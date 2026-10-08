package com.things.link.device.application;

import com.things.link.device.domain.DeviceErrorCode;
import com.things.link.shared.error.BusinessException;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** 事件定义与实例的唯一设备域校验内核；不读取当前可变事件表，不记录原参数。 */
final class DeviceEventSchema {
    /** 本片仅有四种事件标量。 */
    private static final Set<String> TYPES = Set.of("NUMBER", "TEXT", "SWITCH", "ENUM");
    /** 允许冻结的三个事件级别。 */
    private static final Set<String> LEVELS = Set.of("INFO", "WARNING", "ERROR");
    /** 数值绝对上界按十进制比较，避免二进制转换放宽边界。 */
    private static final BigDecimal MAX_NUMBER = new BigDecimal("1e308");

    /** 内核只提供确定性静态校验。 */
    private DeviceEventSchema() { }

    /** @param events 完整不可变事件段，旧模型保持原文但摄入时仍须能合法解释 */
    static void validateDefinitions(JsonNode events) {
        require(events != null && events.isObject());
        events.properties().forEach(entry -> {
            require(validKey(entry.getKey()));
            JsonNode event = entry.getValue();
            require(fields(event, Set.of("level", "parameters")) && event.size() == 2);
            require(event.path("level").isString() && LEVELS.contains(event.path("level").asString()));
            JsonNode parameters = event.get("parameters");
            require(parameters != null && parameters.isObject() && parameters.size() <= 100);
            parameters.properties().forEach(parameter -> validateParameter(parameter.getKey(), parameter.getValue()));
        });
    }

    /** @param key 参数标识 @param definition 冻结参数定义 */
    private static void validateParameter(String key, JsonNode definition) {
        require(validKey(key) && fields(definition, Set.of("dataType", "required", "enum")));
        require(definition.path("dataType").isString() && TYPES.contains(definition.path("dataType").asString()));
        require(definition.path("required").isBoolean());
        if ("ENUM".equals(definition.path("dataType").asString())) {
            JsonNode options = definition.get("enum");
            require(options != null && options.isArray() && !options.isEmpty() && options.size() <= 100);
            Set<String> seen = new HashSet<>();
            options.forEach(option -> {
                require(option.isString() && validText(option.asString(), 64) && !option.asString().isEmpty());
                require(seen.add(option.asString()));
            });
        } else require(!definition.has("enum"));
    }

    /** @param event 原模型中的单一事件 @param params 设备实际原参数，缺失可选参数不补值 */
    static void validateParameters(JsonNode event, Map<String, Object> params) {
        require(params != null && params.size() <= 100);
        JsonNode definitions = event.get("parameters");
        definitions.properties().forEach(entry -> {
            if (entry.getValue().path("required").asBoolean()) require(params.containsKey(entry.getKey()));
        });
        params.forEach((key, value) -> {
            require(validKey(key) && value != null);
            JsonNode definition = definitions.get(key);
            require(definition != null);
            switch (definition.path("dataType").asString()) {
                case "NUMBER" -> require(validNumber(value));
                case "TEXT" -> require(value instanceof String text && validText(text, 4096));
                case "SWITCH" -> require(value instanceof Boolean);
                case "ENUM" -> {
                    require(value instanceof String text && validText(text, 64));
                    boolean found = false;
                    for (JsonNode option : definition.get("enum")) {
                        if (option.asString().equals(value)) found = true;
                    }
                    require(found);
                }
                default -> throw invalid();
            }
        });
    }

    /** @param value 原JSON数值或内部JSON兼容数值 @return 是否满足有限值、精度、尺度及绝对值预算 */
    private static boolean validNumber(Object value) {
        BigDecimal number;
        try {
            number = switch (value) {
                case BigDecimal decimal -> decimal;
                case BigInteger integer -> new BigDecimal(integer);
                case Byte integer -> BigDecimal.valueOf(integer.longValue());
                case Short integer -> BigDecimal.valueOf(integer.longValue());
                case Integer integer -> BigDecimal.valueOf(integer.longValue());
                case Long integer -> BigDecimal.valueOf(integer);
                case Double decimal when Double.isFinite(decimal) -> BigDecimal.valueOf(decimal);
                case Float decimal when Float.isFinite(decimal) -> new BigDecimal(decimal.toString());
                default -> null;
            };
        } catch (NumberFormatException exception) {
            return false;
        }
        return number != null && number.precision() <= 38
                && number.scale() >= -308 && number.scale() <= 308 && number.abs().compareTo(MAX_NUMBER) <= 0;
    }

    /** @param value 文本原值 @param maximumCodePoints 字符预算 @return 可存入PG且不含孤立代理项、不超预算 */
    static boolean validText(String value, int maximumCodePoints) {
        // PG的jsonb/text无法表示空字符，参数及冻结枚举选项在触及数据库前统一拒绝。
        if (value == null || value.indexOf('\0') >= 0
                || value.codePointCount(0, value.length()) > maximumCodePoints) return false;
        for (int index = 0; index < value.length(); index++) {
            char unit = value.charAt(index);
            if (Character.isHighSurrogate(unit)) {
                if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) return false;
            } else if (Character.isLowSurrogate(unit)) return false;
        }
        return true;
    }

    /** @param value 参数或模型事件键 @return 沿用模型定义字符集，不偷偷重命名旧定义 */
    private static boolean validKey(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{1,64}");
    }

    /** @param node 严格对象 @param allowed 唯一允许字段 @return 没有额外或非对象形态 */
    private static boolean fields(JsonNode node, Set<String> allowed) {
        if (node == null || !node.isObject()) return false;
        for (String key : node.propertyNames()) if (!allowed.contains(key)) return false;
        return true;
    }

    /** @param condition 必须满足的定义或实例条件 */
    private static void require(boolean condition) {
        if (!condition) throw invalid();
    }

    /** @return 固定错误，不附原报文、参数名或敏感数值 */
    private static BusinessException invalid() {
        return new BusinessException(DeviceErrorCode.EVENT_REPORT_INVALID);
    }
}
