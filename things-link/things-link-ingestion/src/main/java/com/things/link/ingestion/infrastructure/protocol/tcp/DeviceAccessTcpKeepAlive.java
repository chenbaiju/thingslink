package com.things.link.ingestion.infrastructure.protocol.tcp;

import java.time.Duration;
import java.time.Instant;

/**
 * TCP 会话保活判据（接入合同 §3.3／§6）。
 *
 * <p>设备按平台下发的心跳周期发送 {@code HEARTBEAT}；连续 **3** 个周期没有收到任何帧，平台判定会话失效并关闭。
 * 判据抽成纯函数（注入时钟）是为了让「三个周期」这条边界可以被确定性地验证，而不是靠真实等待 30 秒来碰运气。</p>
 *
 * <p>周期下界 10 秒是冻结值：更短的心跳只会制造无意义的流量，也说明设备没有遵守平台下发值；配置低于下界属于
 * 部署错误，构造时直接拒绝而不是悄悄夹取——静默夹取会让「配置写了 1 秒」看起来生效了。</p>
 */
public final class DeviceAccessTcpKeepAlive {

    /** 心跳周期下界（接入合同 §6）。 */
    public static final Duration MIN_INTERVAL = Duration.ofSeconds(10);
    /** 三个周期必须能安全转换为Socket的毫秒int超时。 */
    public static final Duration MAX_INTERVAL = Duration.ofMillis(Integer.MAX_VALUE / 3);

    /** 判定失效所需的缺失周期数（接入合同 §3.3）。 */
    public static final int MISSED_CYCLES = 3;

    /** 心跳周期。 */
    private final Duration interval;

    /**
     * @param interval 心跳周期；不得小于 {@link #MIN_INTERVAL}
     * @throws IllegalArgumentException 周期为空或小于下界
     */
    public DeviceAccessTcpKeepAlive(Duration interval) {
        if (interval == null || interval.compareTo(MIN_INTERVAL) < 0 || interval.compareTo(MAX_INTERVAL) > 0) {
            throw new IllegalArgumentException("心跳周期不得小于 " + MIN_INTERVAL.toSeconds() + " 秒且三个周期不得超过Socket超时上界");
        }
        this.interval = interval;
    }

    /**
     * @return 心跳周期
     */
    public Duration interval() {
        return interval;
    }

    /**
     * @return 判定失效的等待窗口（3 个周期）
     */
    public Duration window() {
        return interval.multipliedBy(MISSED_CYCLES);
    }

    /**
     * @return 下发给设备的心跳周期毫秒数
     */
    public long intervalMillis() {
        return interval.toMillis();
    }

    /**
     * 判断会话是否已因缺少活动而失效。
     *
     * @param lastSeenAt 最近一次收到帧的时刻
     * @param now 判定时刻（由调用方注入，测试用虚拟时钟）
     * @return 已超过 3 个心跳周期未收到任何帧
     */
    public boolean expired(Instant lastSeenAt, Instant now) {
        return !now.isBefore(lastSeenAt.plus(window()));
    }
}
