package com.things.link.support.trace;

import org.slf4j.MDC;

import java.util.UUID;

/**
 * 请求链路 ID 的读写入口。
 *
 * <p>架构文档第 13 节：<b>设备消息、命令、规则、告警全链路携带 traceId 和
 * messageId。</b> traceId 是整个系统排障的主线索 —— 一次设备上报会横跨 HTTP 入口、
 * Kafka、多个消费者和数据库写入，没有它就只能靠时间戳猜测因果关系。
 *
 * <p>存储在 SLF4J 的 {@link MDC} 中，这样日志格式里加一个 {@code %X{traceId}}
 * 就能让每一行日志自动带上，不需要业务代码显式打印。
 */
public final class TraceContext {

    /** 允许的外部 traceId 长度上限，防止超长输入撑爆日志与 Kafka header。 */
    private static final int MAX_TRACE_ID_LENGTH = 64;

    /** MDC 键名。与 logback 配置中的 {@code %X{traceId}} 对应，改名要同步改配置。 */
    public static final String TRACE_ID_KEY = "traceId";

    /**
     * 响应头名称。
     *
     * <p>把 traceId 回传给客户端，用户报障时能直接提供它。这是「用户说系统出错了」
     * 到「运维定位到具体那一次请求」之间最短的路径。
     */
    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    private TraceContext() {
        // 工具类，不允许实例化
    }

    /**
     * 生成一个新的 traceId。
     *
     * <p>用 32 位十六进制无分隔形式，与 W3C Trace Context 的 trace-id 格式一致，
     * 便于将来接入 OpenTelemetry 时不必改动已有日志与存量数据。
     *
     * @return 32 位十六进制字符串
     */
    public static String generate() {
        UUID uuid = UUID.randomUUID();
        return "%016x%016x".formatted(uuid.getMostSignificantBits(), uuid.getLeastSignificantBits());
    }

    /**
     * 绑定 traceId 到当前线程。仅由框架层调用。
     *
     * @param traceId 链路 ID
     */
    public static void set(String traceId) {
        MDC.put(TRACE_ID_KEY, traceId);
    }

    /**
     * 取当前 traceId。
     *
     * @return 当前链路 ID；不在请求上下文中时返回 null
     */
    public static String current() {
        return MDC.get(TRACE_ID_KEY);
    }

    /**
     * 接受安全的上游 traceId，否则生成新值。
     *
     * <p>HTTP 与 Kafka 都属于不可信协议边界，共用这一个校验器才能避免两边规则漂移。
     * 只允许十六进制与短横线，拒绝换行等日志注入字符。</p>
     *
     * @param incoming 上游传入值，可为空
     * @return 可安全写入 MDC 与消息头的 traceId
     */
    public static String resolve(String incoming) {
        if (incoming == null || incoming.isBlank() || incoming.length() > MAX_TRACE_ID_LENGTH) {
            return generate();
        }
        for (int index = 0; index < incoming.length(); index++) {
            char character = incoming.charAt(index);
            boolean allowed = character >= '0' && character <= '9'
                    || character >= 'a' && character <= 'f'
                    || character >= 'A' && character <= 'F'
                    || character == '-';
            if (!allowed) {
                return generate();
            }
        }
        return incoming;
    }

    /**
     * 清除绑定。必须在 finally 块中调用 —— Web 容器复用线程，
     * 不清理会让下一个请求的日志带上上一个请求的 traceId。
     */
    public static void clear() {
        MDC.remove(TRACE_ID_KEY);
    }

}
