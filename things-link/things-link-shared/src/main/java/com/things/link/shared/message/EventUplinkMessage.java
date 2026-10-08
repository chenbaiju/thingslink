package com.things.link.shared.message;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.DateTimeException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * ADR0235独立MQTT事件信封，方向与分类固定为UP/EVENT，不进入属性规则链。
 *
 * @param messageId 设备提供的UUIDv7，消费者不得重新生成
 * @param tenantId 接入层确权的租户
 * @param projectId 接入层确权的项目
 * @param deviceId 已认证的连接设备，网关仅能上报自身事件
 * @param protocol 固定MQTT
 * @param eventKey 仅从七层Topic取得的事件键
 * @param modelVersion 显式声明的不可变物模型语义版本
 * @param occurredAt 设备发生时刻
 * @param receivedAt Broker冻结的可信接收时刻
 * @param traceId 接入层链路标识
 * @param rawBytes 原始正文长度，不能用规范JSON长度替代
 * @param params 四种标量的不可变参数，可为空，完整值仅用于校验与私有摘要
 */
public record EventUplinkMessage(UUID messageId, UUID tenantId, UUID projectId, UUID deviceId,
        TransportProtocol protocol, String eventKey, String modelVersion, Instant occurredAt,
        Instant receivedAt, String traceId, int rawBytes, Map<String, Object> params) {
    /** 原始正文与参数规模的冻结边界。 */
    public static final int MAX_RAW_BYTES = 65_536;
    /** 最大事件参数数目。 */
    public static final int MAX_PARAMS = 100;
    /** NUMBER绝对值上界，保持十进制而非浮点比较。 */
    private static final BigDecimal MAX_NUMBER = new BigDecimal("1e308");

    /** 四位RFC日期可映射的最早UTC时刻，预留一天允许合法offset跨年。 */
    private static final Instant MIN_OCCURRED_AT = Instant.parse("0000-01-01T00:00:00Z").minusSeconds(86_400);
    /** 四位RFC日期可映射的UTC上界，同样预留一天且不包含该边界。 */
    private static final Instant MAX_OCCURRED_AT = Instant.parse("+10000-01-01T00:00:00Z").plusSeconds(86_400);

    /** 冻结信封，不接受可变容器或未定义的参数类型。 */
    public EventUplinkMessage {
        if (messageId == null || messageId.version() != 7 || messageId.variant() != 2
                || tenantId == null || projectId == null || deviceId == null)
            throw new IllegalArgumentException("事件身份不符合契约");
        if (protocol != TransportProtocol.MQTT || eventKey == null
                || !eventKey.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}"))
            throw new IllegalArgumentException("事件协议或Topic键不符合契约");
        validateModelVersion(modelVersion);
        if (occurredAt == null || receivedAt == null || traceId == null || traceId.isBlank()
                || rawBytes <= 0 || rawBytes > MAX_RAW_BYTES)
            throw new IllegalArgumentException("事件可信接收元数据不符合契约");
        if (occurredAt.isBefore(MIN_OCCURRED_AT) || !occurredAt.isBefore(MAX_OCCURRED_AT))
            throw new IllegalArgumentException("事件发生时刻超出四位日期存储边界");
        try {
            if (occurredAt.isAfter(receivedAt.plusSeconds(300)))
                throw new IllegalArgumentException("事件发生时刻超出可信接收窗口");
        } catch (DateTimeException exception) {
            throw new IllegalArgumentException("事件可信接收时刻超出边界");
        }
        params = immutableParams(params);
    }

    /** @param version 显式语义版本，每段最多65535且不得有前导零 */
    public static void validateModelVersion(String version) {
        if (version == null || !version.matches("(0|[1-9][0-9]{0,4})\\.(0|[1-9][0-9]{0,4})\\.(0|[1-9][0-9]{0,4})"))
            throw new IllegalArgumentException("事件必须声明规范物模型版本");
        for (String part : version.split("\\."))
            if (Integer.parseInt(part) > 65_535)
                throw new IllegalArgumentException("事件物模型版本超出边界");
    }

    /** @param source 原参数对象 @return 不含可变嵌套容器的不可变标量快照 */
    public static Map<String, Object> immutableParams(Map<String, Object> source) {
        if (source == null || source.size() > MAX_PARAMS)
            throw new IllegalArgumentException("事件参数规模不符合契约");
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key == null || !key.matches("[A-Za-z0-9_-]{1,64}"))
                throw new IllegalArgumentException("事件参数键不符合契约");
            if (value instanceof String text) validateText(text);
            else if (value instanceof Number number) validateNumber(number);
            else if (!(value instanceof Boolean))
                throw new IllegalArgumentException("事件仅允许非空四种标量参数");
            result.put(key, value);
        });
        return Collections.unmodifiableMap(result);
    }

    /** @param text 原文保留的PG可存储文本，拒绝空字符、超长和孤立代理项 */
    private static void validateText(String text) {
        // 空字符会使确定的非法载荷在jsonb摘要处成为数据库异常，必须在严格信封层固定拒绝。
        if (text.indexOf('\0') >= 0)
            throw new IllegalArgumentException("事件文本含不可存储空字符");
        if (text.codePointCount(0, text.length()) > 4_096)
            throw new IllegalArgumentException("事件文本超出边界");
        for (int index = 0; index < text.length(); index++) {
            char value = text.charAt(index);
            if (Character.isHighSurrogate(value)) {
                if (++index >= text.length() || !Character.isLowSurrogate(text.charAt(index)))
                    throw new IllegalArgumentException("事件文本含孤立代理项");
            } else if (Character.isLowSurrogate(value))
                throw new IllegalArgumentException("事件文本含孤立代理项");
        }
    }

    /** @param value 不可变有限数字，禁止可变Number及越界十进制 */
    private static void validateNumber(Number value) {
        if (!(value instanceof BigDecimal || value instanceof BigInteger || value instanceof Byte
                || value instanceof Short || value instanceof Integer || value instanceof Long
                || value instanceof Float || value instanceof Double))
            throw new IllegalArgumentException("事件数字类型不符合契约");
        final BigDecimal decimal;
        try { decimal = new BigDecimal(value.toString()); }
        catch (NumberFormatException exception) { throw new IllegalArgumentException("事件数字必须有限"); }
        if (decimal.abs().compareTo(MAX_NUMBER) > 0 || decimal.precision() > 38
                || decimal.scale() < -308 || decimal.scale() > 308)
            throw new IllegalArgumentException("事件数字超出冻结边界");
    }
}
