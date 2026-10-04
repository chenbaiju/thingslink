package com.things.link.rule.application;

import com.things.link.rule.domain.RuleErrorCode;
import com.things.link.shared.error.BusinessException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;

/** 有界keyset游标仅表达位置；scope绑定项目、实体及筛选，绝不作为授权凭证。 */
public final class RuleManagementCursor {
    /** 无状态JSON编解码。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 禁止实例化。 */
    private RuleManagementCursor() { }
    /** 对列表条件归一化并拒绝无界参数。 */
    public static String name(String name, boolean scene) {
        String result = name == null ? "" : name.strip();
        if (result.length() > 128) throw invalid(scene);
        return result;
    }
    /** 对状态闭集校验。 */
    public static String status(String value, boolean scene) {
        String result = value == null ? "" : value;
        if (!Set.of("", "DRAFT", "ACTIVE", "PAUSED").contains(result)) throw invalid(scene);
        return result;
    }
    /** limit不截断非法值，拒绝越界。 */
    public static int limit(int limit, boolean scene) {
        if (limit < 1 || limit > 100) throw invalid(scene);
        return limit;
    }
    /** 构造无歧义的查询绑定键。 */
    public static String scope(UUID project, String kind, String name, String status) {
        return MAPPER.writeValueAsString(java.util.List.of(project.toString(), kind, name, status));
    }
    /** 解码目录位置或版本号，完整校验类型与scope。 */
    public static Position decode(String cursor, String scope, boolean history, boolean scene) {
        if (cursor == null) return new Position(null, null, null);
        try {
            if (cursor.isBlank() || cursor.length() > 2048) throw invalid(scene);
            JsonNode node = MAPPER.readTree(Base64.getUrlDecoder().decode(cursor));
            if (!node.isObject() || node.size() != 3 || !node.path("v").isIntegralNumber() || !node.path("v").canConvertToInt() || node.path("v").intValue() != 1
                    || !node.path("scope").isString() || !scope.equals(node.get("scope").stringValue())) throw invalid(scene);
            JsonNode key = node.get("key");
            if (history) {
                if (key == null || !key.isIntegralNumber() || !key.canConvertToLong() || key.longValue() < 1) throw invalid(scene);
                return new Position(null, null, key.longValue());
            }
            if (key == null || !key.isArray() || key.size() != 2 || !key.get(0).isString() || !key.get(1).isString()) throw invalid(scene);
            return new Position(Instant.parse(key.get(0).stringValue()), UUID.fromString(key.get(1).stringValue()), null);
        } catch (RuntimeException exception) { throw invalid(scene); }
    }
    /** 编码已由数据库读取的稳定位置。 */
    public static String encode(String scope, Object key) {
        var node = MAPPER.createObjectNode().put("v", 1).put("scope", scope);
        node.set("key", MAPPER.valueToTree(key));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(node.toString().getBytes(StandardCharsets.UTF_8));
    }
    /** 使用领域参数错误，避免解析异常形成500。 */
    private static BusinessException invalid(boolean scene) {
        return new BusinessException(scene ? RuleErrorCode.SCENE_INVALID : RuleErrorCode.RULE_INVALID);
    }
    /** @param time 创建时间 @param id 同时间排序ID @param version 历史版本号 */
    public record Position(Instant time, UUID id, Long version) { }
}
