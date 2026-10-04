package com.things.link.simulator.infrastructure.ota;

import com.things.link.simulator.application.ota.OtaClock;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * 使用真实墙钟与 {@link Thread#sleep} 的时钟实现，供真实设备场景使用。
 *
 * <p>测试不应使用本实现：本片的验收要求是「确定性且不真睡」，测试必须注入会推进自身时间的假时钟，
 * 否则「退避发生了」「预算用尽」这类断言就变成了看运气。</p>
 */
public final class SystemOtaClock implements OtaClock {

    /** 创建真实时钟。 */
    public SystemOtaClock() {
    }

    /**
     * @return 当前 UTC 时刻
     */
    @Override
    public Instant now() {
        return Instant.now();
    }

    /**
     * 真实休眠；中断时恢复中断位并抛出，避免设备把停机信号吞掉后继续刷写。
     *
     * @param duration 等待时长，必须为非负
     */
    @Override
    public void sleep(Duration duration) {
        Objects.requireNonNull(duration, "duration");
        if (duration.isNegative()) {
            throw new IllegalArgumentException("sleep 时长不能为负数");
        }
        if (duration.isZero()) {
            return;
        }
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("OTA 等待被中断", failure);
        }
    }
}
