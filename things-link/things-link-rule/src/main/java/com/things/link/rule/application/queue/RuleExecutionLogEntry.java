package com.things.link.rule.application.queue;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 一次生产规则执行 attempt 的最小不可变日志。
 *
 * <p>该事实故意不携带 payload、源码和异常正文；固定状态与结果码足以支撑 S8-2 的运行追踪，同时避免把设备数据
 * 或 guest 错误泄漏到长期事实。</p>
 *
 * @param tenantId 归属租户标识
 * @param key 不可变执行身份
 * @param attempt 执行尝试序号
 * @param status 封闭终态
 * @param resultCode SUCCESS 或 {@link RuleExecutionFailure} 名称
 * @param duration 完整执行耗时
 * @param inputBytes 输入 payload UTF-8 字节数
 * @param outputBytes 成功输出 UTF-8 字节数，失败为零
 * @param createdAt 完成 UTC 时刻
 * @param deviceId 已确权消息设备；旧构造入口为空，不猜测回填
 */
public record RuleExecutionLogEntry(
        UUID tenantId,
        RuleExecutionKey key,
        int attempt,
        Status status,
        String resultCode,
        Duration duration,
        int inputBytes,
        int outputBytes,
        Instant createdAt,
        UUID deviceId) {

    /** 保留旧调用形态，旧设备身份未知，生产协调器使用完整构造器。 */
    public RuleExecutionLogEntry(UUID tenantId, RuleExecutionKey key, int attempt, Status status,
            String resultCode, Duration duration, int inputBytes, int outputBytes, Instant createdAt) {
        this(tenantId,key,attempt,status,resultCode,duration,inputBytes,outputBytes,createdAt,null);
    }

    /** 校验日志只能表达有限 attempt 和非负计量。 */
    public RuleExecutionLogEntry {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(resultCode, "resultCode");
        Objects.requireNonNull(duration, "duration");
        Objects.requireNonNull(createdAt, "createdAt");
        if (attempt < 1 || attempt > 3 || duration.isNegative() || inputBytes < 0 || outputBytes < 0) {
            throw new IllegalArgumentException("规则执行日志 attempt、耗时与字节数必须处于冻结范围");
        }
        if (!resultCode.matches("^[A-Z][A-Z0-9_]{0,63}$")) {
            throw new IllegalArgumentException("规则执行结果码必须使用封闭大写标识");
        }
    }

    /** 生产规则执行日志的封闭终态。 */
    public enum Status {
        /** 变换结果已完成可靠交接。 */
        SUCCESS,
        /** 暂态失败已发布到有限 retry Topic。 */
        RETRY_SCHEDULED,
        /** 永久失败或尝试耗尽已发布到规则 DLQ。 */
        DEAD_LETTER
    }
}
