package com.things.link.support.storage;

import java.time.Duration;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * 单次对象上传共享的单调截止与外部取消信号。
 *
 * <p>业务编排器在尝试开始时创建本对象，并把同一实例同时传给归档生成与对象上传。
 * {@link System#nanoTime()} 只比较经过时长，不受系统时钟校正影响。</p>
 */
public final class ObjectUploadControl implements BooleanSupplier {

    /** 单调时钟截止值。 */
    private final long deadlineNanos;
    /** 租约失权或停机等外部取消信号。 */
    private final BooleanSupplier cancellation;

    /**
     * @param deadlineNanos 单调时钟截止值
     * @param cancellation 外部取消信号
     */
    private ObjectUploadControl(long deadlineNanos, BooleanSupplier cancellation) {
        this.deadlineNanos = deadlineNanos;
        this.cancellation = cancellation;
    }

    /**
     * 从当前单调时刻建立控制对象。
     *
     * @param timeout 整个尝试允许的剩余时长
     * @param cancellation 外部取消信号
     * @return 可由生成器和存储适配器共同观察的控制对象
     */
    public static ObjectUploadControl start(Duration timeout, BooleanSupplier cancellation) {
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(cancellation, "cancellation");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("对象上传尝试时限必须为正数");
        }
        return new ObjectUploadControl(Math.addExact(System.nanoTime(), timeout.toNanos()), cancellation);
    }

    /** @return 单调截止是否已经到达 */
    public boolean timedOut() {
        return remainingNanos() == 0L;
    }

    /** @return 租约失权或停机是否要求取消 */
    public boolean externallyCancelled() {
        return cancellation.getAsBoolean();
    }

    /** @return 截止前剩余纳秒，已到期时为零 */
    public long remainingNanos() {
        return Math.max(0L, deadlineNanos - System.nanoTime());
    }

    /** @return 到期或外部取消时为true，供原有流式生成回调直接复用 */
    @Override
    public boolean getAsBoolean() {
        return timedOut() || externallyCancelled();
    }
}
