package com.things.link.telemetry.application;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 事件专属版本一键集，不携带授权或跨请求提交快照。 */
public final class EventHistoryCursor {
    /** 原始过滤及最后一个持久排序键组成封闭信封。 */
    private static final Set<String> FIELDS = Set.of("v", "projectId", "deviceId", "eventKey", "level", "thingModelVersionId", "from", "to", "occurredAt", "messageId");
    /** 固定解析器拒绝重复解码字段，不受业务命名策略影响。 */
    private static final JsonMapper JSON = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    /** 无填充的规范URL编码。 */
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private EventHistoryCursor() { }
    /** 只保存发生时间与消息ID，不从客户端读取任何权限。 */
    public record Anchor(Instant occurredAt, UUID messageId) { }

    /** 当前资源及全部原始过滤必须与游标一致；空串、非规范文本和非微秒锚点固定拒绝。 */
    public static Anchor decode(String cursor, UUID project, UUID device, EventHistoryQuery query) {
        if (cursor == null) return null;
        if (cursor.isEmpty() || cursor.length() > 8192 || !cursor.matches("[A-Za-z0-9_-]+")) throw EventHistoryQuery.invalid();
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(cursor);
            if (!ENCODER.encodeToString(bytes).equals(cursor)) throw EventHistoryQuery.invalid();
            JsonNode node = JSON.readTree(bytes);
            if (node == null || !node.isObject() || !Set.copyOf(node.propertyNames()).equals(FIELDS)
                    || !node.get("v").isIntegralNumber() || !node.get("v").asString().equals("1")) throw EventHistoryQuery.invalid();
            if (!project.toString().equals(text(node, "projectId")) || !device.toString().equals(text(node, "deviceId"))
                    || !java.util.Objects.equals(query.eventKey(), nullable(node, "eventKey"))
                    || !java.util.Objects.equals(query.level(), nullable(node, "level"))
                    || !java.util.Objects.equals(query.thingModelVersionId(), nullable(node, "thingModelVersionId"))
                    || !java.util.Objects.equals(query.from(), nullable(node, "from")) || !java.util.Objects.equals(query.to(), nullable(node, "to"))) throw EventHistoryQuery.invalid();
            String raw = text(node, "occurredAt");
            Instant time = Instant.parse(raw);
            if (!time.toString().equals(raw) || time.getNano() % 1000 != 0
                    || time.isBefore(Instant.parse("-0001-12-31T00:00:00Z"))
                    || !time.isBefore(Instant.parse("+10000-01-02T00:00:00Z"))) throw EventHistoryQuery.invalid();
            UUID message = EventHistoryQuery.uuid(text(node, "messageId"));
            return new Anchor(time, message);
        } catch (com.things.link.shared.error.BusinessException exception) { throw exception; }
        catch (RuntimeException exception) { throw EventHistoryQuery.invalid(); }
    }
    /** 对最后返回的持久事实编码，limit不属于过滤意图。 */
    public static String encode(UUID project, UUID device, EventHistoryQuery query, Instant occurredAt, UUID messageId) {
        if (occurredAt.getNano() % 1000 != 0) throw new IllegalStateException("事件游标必须来自持久微秒时刻");
        var node = JSON.createObjectNode().put("v", 1).put("projectId", project.toString()).put("deviceId", device.toString())
                .put("eventKey", query.eventKey()).put("level", query.level()).put("thingModelVersionId", query.thingModelVersionId())
                .put("from", query.from()).put("to", query.to()).put("occurredAt", occurredAt.toString()).put("messageId", messageId.toString());
        String encoded = ENCODER.encodeToString(JSON.writeValueAsString(node).getBytes(StandardCharsets.UTF_8));
        if (encoded.length() > 8192) throw new IllegalStateException("事件游标超过合同大小限制");
        return encoded;
    }
    /** 必须是文本字段，不宽松转换数值。 */
    private static String text(JsonNode node, String key) { if (!node.get(key).isString()) throw EventHistoryQuery.invalid(); return node.get(key).asString(); }
    /** 缺省过滤明确为null，其他类型均拒绝。 */
    private static String nullable(JsonNode node, String key) { return node.get(key).isNull() ? null : text(node, key); }
}
