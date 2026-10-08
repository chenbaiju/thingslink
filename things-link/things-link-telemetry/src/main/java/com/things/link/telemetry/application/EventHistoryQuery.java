package com.things.link.telemetry.application;

import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** 封闭查询及原始过滤意图；游标不把不同原文的时间过滤默认为同一请求。 */
public record EventHistoryQuery(String eventKey, String level, String thingModelVersionId,
                                String from, String to, String cursor, int limit) {
    /** 唯一开放的列表参数。 */
    private static final Set<String> FIELDS = Set.of("eventKey", "level", "thingModelVersionId", "from", "to", "cursor", "limit");
    /** 四位年及最多纳秒的明确时区时间，数据库过滤后仍按持久微秒排序。 */
    private static final Pattern TIME = Pattern.compile(
            "[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01])[Tt]"
            + "([01][0-9]|2[0-3]):[0-5][0-9]:([0-5][0-9]|60)(\\.[0-9]{1,9})?"
            + "([Zz]|[+-]([01][0-9]|2[0-3]):[0-5][0-9])");
    /** 标准UUID文本，避免UUID解析器接受缩写分段。 */
    private static final Pattern UUID_TEXT = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    /** 校验全部列表参数，空窗口也不能跳过非法输入。 */
    public static EventHistoryQuery parse(Map<String, String[]> parameters) {
        requireClosed(parameters, FIELDS);
        String key = value(parameters, "eventKey"), level = value(parameters, "level");
        String version = value(parameters, "thingModelVersionId"), from = value(parameters, "from"), to = value(parameters, "to");
        if (key != null && !key.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")) throw invalid();
        if (level != null && !Set.of("INFO", "WARNING", "ERROR").contains(level)) throw invalid();
        if (version != null) uuid(version);
        Instant lower = from == null ? null : time(from), upper = to == null ? null : time(to);
        if (lower != null && upper != null && !lower.isBefore(upper)) throw invalid();
        int limit = 20;
        String rawLimit = value(parameters, "limit");
        if (rawLimit != null) {
            if (!rawLimit.matches("[1-9][0-9]{0,2}")) throw invalid();
            limit = Integer.parseInt(rawLimit);
            if (limit > 100) throw invalid();
        }
        return new EventHistoryQuery(key, level, version, from, to, value(parameters, "cursor"), limit);
    }

    /** 详情不开放列表过滤参数。 */
    public static void requireDetailParameters(Map<String, String[]> parameters) { requireClosed(parameters, Set.of()); }
    /** 校验封闭字段、唯一值和非空原文。 */
    private static void requireClosed(Map<String, String[]> parameters, Set<String> fields) {
        if (parameters == null || !fields.containsAll(parameters.keySet()) || parameters.values().stream()
                .anyMatch(v -> v == null || v.length != 1 || v[0] == null || v[0].isBlank())) throw invalid();
    }
    /** 取未经trim的过滤原文。 */
    private static String value(Map<String, String[]> parameters, String name) { return parameters.containsKey(name) ? parameters.get(name)[0] : null; }
    /** 解析标准UUID；所有错误固定为10001，不带输入原文。 */
    public static UUID uuid(String raw) {
        if (raw == null || !UUID_TEXT.matcher(raw).matches()) throw invalid();
        return UUID.fromString(raw);
    }
    /** 严格日历及RFC偏移解析，限制四位年以保证PG可存储。 */
    public static Instant time(String raw) {
        if (raw == null || !TIME.matcher(raw).matches()) throw invalid();
        try {
            boolean utcText = raw.endsWith("Z") || raw.endsWith("z");
            int offsetAt = utcText ? raw.length() - 1 : raw.length() - 6;
            int offsetSeconds = utcText ? 0 : (Integer.parseInt(raw.substring(offsetAt + 1, offsetAt + 3)) * 60
                    + Integer.parseInt(raw.substring(offsetAt + 4))) * 60 * (raw.charAt(offsetAt) == '-' ? -1 : 1);
            String local = raw.substring(0, offsetAt).replace('t', 'T');
            boolean leap = local.substring(17, 19).equals("60");
            if (leap) local = local.substring(0, 17) + "59" + local.substring(19);
            // 显式UTC严校本地日历，再减RFC偏移，避免JDK按本地23:59错误判断带偏移闰秒。
            Instant instant = OffsetDateTime.parse(local + "Z").toInstant().minusSeconds(offsetSeconds);
            var utc = instant.atOffset(ZoneOffset.UTC);
            if (leap && (utc.getHour() != 23 || utc.getMinute() != 59 || utc.getSecond() != 59)) throw invalid();
            return instant;
        } catch (java.time.DateTimeException exception) { throw invalid(); }
    }

    /** 已校验的模型版本过滤。 */
    public UUID versionId() { return thingModelVersionId == null ? null : uuid(thingModelVersionId); }
    /** 已校验的包含起点。 */
    public Instant lower() { return from == null ? null : time(from); }
    /** 已校验的排他终点。 */
    public Instant upper() { return to == null ? null : time(to); }
    /** 所有输入拒绝保持固定分类。 */
    static BusinessException invalid() { return new BusinessException(CommonErrorCode.INVALID_PARAMETER); }
}
