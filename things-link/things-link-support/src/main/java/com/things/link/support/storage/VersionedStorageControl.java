package com.things.link.support.storage;

import java.time.Duration;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** 自构造时开始计时的单次操作预算，所有前置与正文共享同一截止。 */
public final class VersionedStorageControl {
    /** 单调时钟的起始纳秒。 */
    private final long started = System.nanoTime();
    /** 允许的总耗时纳秒。 */
    private final long budget;
    /** 外部失权或主动取消信号。 */
    private final BooleanSupplier cancellation;
    /** 构造1毫秒至300秒预算，不允许无限等待。 */
    public VersionedStorageControl(Duration timeout, BooleanSupplier cancelled) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.compareTo(Duration.ofMillis(1)) < 0 || timeout.compareTo(Duration.ofSeconds(300)) > 0) {
            throw new IllegalArgumentException("版本化对象操作预算不合法");
        }
        budget = timeout.toNanos();
        cancellation = Objects.requireNonNull(cancelled, "cancelled");
    }
    /** 返回单调截止前剩余纳秒。 */
    public long remainingNanos() { return Math.max(0L, budget - (System.nanoTime() - started)); }
    /** 判断整个操作的单调预算是否耗尽。 */
    public boolean timedOut() { return remainingNanos() == 0; }
    /** 读取外部取消信号，调用方应提供非阻塞判断。 */
    public boolean cancelled() { return cancellation.getAsBoolean(); }
}
