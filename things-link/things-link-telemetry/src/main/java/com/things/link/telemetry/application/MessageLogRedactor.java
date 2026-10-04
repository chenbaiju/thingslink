package com.things.link.telemetry.application;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 调试数据的凭据脱敏（接入合同 §7.2 的硬性规则）。
 *
 * <p>规则一：凭据类字段一律**整字段移除**（不是打码）。调试面是给人看的，打码后的字段仍会泄露长度与部分内容，
 * 而"整字段移除"让"这里曾经有凭据"这件事本身都不必暴露。规则二：认证头、Cookie、Broker 回调密钥同样移除。
 * 规则三：未在物模型中声明的自定义字段按原样保留——它们属于业务载荷，不是秘密（§7.2）。</p>
 *
 * <p>写入与读取各执行一次：写入时脱敏保证凭据**从不落库**；读取时再脱敏一次，覆盖脱敏器上线之前写入的历史行。
 * 两层都做不是冗余——历史行只能靠读取层兜住，而落库层决定了泄露面的大小。</p>
 */
@Component
public class MessageLogRedactor {

    /** 命中即整字段移除的键名（小写比较），含常见拼写与下划线／连字符变体。 */
    private static final Map<String, Boolean> SECRET_KEYS = secretKeys();

    /** 统一 JSON 映射器。 */
    private final ObjectMapper objectMapper;

    /**
     * @param objectMapper 统一 JSON 映射器
     */
    public MessageLogRedactor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 对摘要做递归脱敏。
     *
     * <p>非 JSON 文本原样返回：业务载荷按契约必须是 JSON 对象，未声明的形态不做猜测式改写，避免把正常内容改坏。</p>
     *
     * @param summary 载荷摘要
     * @return 脱敏后的摘要
     */
    public String redact(String summary) {
        if (summary == null || summary.isBlank()) {
            return summary;
        }
        try {
            JsonNode parsed = objectMapper.readTree(summary);
            if (!parsed.isObject() && !parsed.isArray()) {
                return summary;
            }
            JsonNode redacted = redactNode(parsed);
            return objectMapper.writeValueAsString(redacted);
        } catch (RuntimeException exception) {
            return summary;
        }
    }

    /** 递归脱敏：对象按键名移除，数组逐元素处理。 */
    private JsonNode redactNode(JsonNode node) {
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            java.util.List<String> remove = new java.util.ArrayList<>();
            object.propertyNames().forEach(name -> {
                if (isSecretKey(name)) {
                    remove.add(name);
                }
            });
            remove.forEach(object::remove);
            object.propertyNames().forEach(name -> object.set(name, redactNode(object.get(name))));
            return object;
        }
        if (node.isArray()) {
            for (int index = 0; index < node.size(); index++) {
                ((tools.jackson.databind.node.ArrayNode) node).set(index, redactNode(node.get(index)));
            }
            return node;
        }
        return node;
    }

    /** 键名是否属于凭据类（整字段移除）。 */
    private static boolean isSecretKey(String name) {
        if (name == null) {
            return false;
        }
        String normalized = name.toLowerCase(Locale.ROOT);
        return SECRET_KEYS.containsKey(normalized)
                || normalized.endsWith("_secret")
                || normalized.endsWith("-secret")
                || normalized.endsWith("_password")
                || normalized.endsWith("_token");
    }

    /** 冻结的凭据类键名清单。 */
    private static Map<String, Boolean> secretKeys() {
        Map<String, Boolean> keys = new LinkedHashMap<>();
        for (String key : new String[] {"secret", "credential", "credentials", "password", "passwd", "token",
                "access_token", "access-token", "refresh_token", "refresh-token", "api_key", "apikey",
                "api-key", "authorization", "cookie", "set-cookie", "x-tc-device-secret", "tc-credential",
                "broker_key", "broker-key", "callback_key", "callback-key", "client_secret", "client-secret",
                // HTTP/JSON常用驼峰别名经小写归一化后的精确闭集，不模糊删除业务字段。
                "accesstoken", "refreshtoken", "clientsecret", "brokerkey", "callbackkey",
                "setcookie", "xtcdevicesecret", "tccredential"}) {
            keys.put(key, Boolean.TRUE);
        }
        return keys;
    }
}
