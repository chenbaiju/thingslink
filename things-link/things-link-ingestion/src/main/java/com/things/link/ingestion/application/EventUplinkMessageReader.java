package com.things.link.ingestion.application;

import com.things.link.shared.message.EventUplinkMessage;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** 独立严格事件读取器，不继承属性兼容字段、缺省版本或通用映射器宽松配置。 */
public final class EventUplinkMessageReader {
    /** 正文精确闭集，事件键和归属绝不能来自正文。 */
    private static final Set<String> FIELDS = Set.of("messageId", "modelVersion", "occurredAt", "params");
    /** 四位年RFC3339文本形状；真实日期和UTC闰秒由严格时间解析复核。 */
    private static final Pattern RFC3339 = Pattern.compile(
            "[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01])[Tt]"
            + "([01][0-9]|2[0-3]):[0-5][0-9]:([0-5][0-9]|60)(\\.[0-9]{1,9})?"
            + "([Zz]|[+-]([01][0-9]|2[0-3]):[0-5][0-9])");
    /** 数字保留原十进制scale及大整数，拒绝重复解码键和尾随JSON。 */
    private final ObjectReader reader = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
            .build().readerFor(Map.class);

    /** 只保存正文已有业务语义，不引入身份字段。 */
    public record Report(UUID messageId, String modelVersion, Instant occurredAt, Map<String, Object> params) { }

    /**
     * @param bytes 原始MQTT正文，最多64KiB且必须是合法UTF-8
     * @return 已严格校验的闭合正文
     * @throws InvalidUplinkMessageException 同一字节重放仍无法恢复的固定解析拒绝
     */
    @SuppressWarnings("unchecked")
    public Report read(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > EventUplinkMessage.MAX_RAW_BYTES)
            throw new InvalidUplinkMessageException("EVENT_PAYLOAD_INVALID");
        try {
            String json = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            Map<String, Object> root = reader.readValue(json);
            if (root == null || !root.keySet().equals(FIELDS)
                    || !(root.get("params") instanceof Map<?, ?> params))
                throw new IllegalArgumentException();
            String idText = text(root, "messageId"), modelVersion = text(root, "modelVersion"),
                    occurredAt = text(root, "occurredAt");
            if (!idText.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
                throw new IllegalArgumentException();
            UUID id = UUID.fromString(idText);
            if (id.version() != 7 || id.variant() != 2)
                throw new IllegalArgumentException();
            EventUplinkMessage.validateModelVersion(modelVersion);
            return new Report(id, modelVersion, parseOccurredAt(occurredAt),
                    EventUplinkMessage.immutableParams((Map<String, Object>) params));
        } catch (CharacterCodingException | RuntimeException exception) {
            // 不附Jackson异常或原正文，DLQ异常头也不得回显参数和凭据。
            throw new InvalidUplinkMessageException("EVENT_PAYLOAD_INVALID");
        }
    }

    /** 保留RFC3339到23:59的合法偏移，不把JDK的18小时偏移上限冒充入站合同。 */
    private static Instant parseOccurredAt(String raw) {
        if (!RFC3339.matcher(raw).matches()) throw new IllegalArgumentException();
        boolean utcText = raw.endsWith("Z") || raw.endsWith("z");
        int offsetAt = utcText ? raw.length() - 1 : raw.length() - 6;
        int offsetSeconds = utcText ? 0 : (Integer.parseInt(raw.substring(offsetAt + 1, offsetAt + 3)) * 60
                + Integer.parseInt(raw.substring(offsetAt + 4))) * 60 * (raw.charAt(offsetAt) == '-' ? -1 : 1);
        String local = raw.substring(0, offsetAt).replace('t', 'T');
        boolean leap = local.substring(17, 19).equals("60");
        if (leap) local = local.substring(0, 17) + "59" + local.substring(19);
        // 所有偏移的闰秒均以UTC23:59:60判断，不能接受仅本地钟面满足的假闰秒。
        Instant instant = OffsetDateTime.parse(local + "Z").toInstant().minusSeconds(offsetSeconds);
        var utc = instant.atOffset(ZoneOffset.UTC);
        if (leap && (utc.getHour() != 23 || utc.getMinute() != 59 || utc.getSecond() != 59)) throw new IllegalArgumentException();
        return instant;
    }

    /** @return 严格文本字段，禁止Jackson自动把数字、null转成字符串 */
    private static String text(Map<String, Object> root, String field) {
        if (!(root.get(field) instanceof String text)) throw new IllegalArgumentException();
        return text;
    }
}
