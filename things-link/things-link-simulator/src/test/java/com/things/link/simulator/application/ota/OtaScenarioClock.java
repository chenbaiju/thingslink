package com.things.link.simulator.application.ota;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 推进式测试时钟：{@link #sleep(Duration)} 只把自身时间前移并记录，不真正阻塞。
 *
 * <p>这样既能断言「确实发生了退避/预算消耗」，又能让整个测试套件的等待时间为零，
 * 不依赖 {@code Thread.sleep} 的调度运气。</p>
 */
final class OtaScenarioClock implements OtaClock {

    /** 当前时刻。 */
    private Instant now;

    /** 已发生的退避记录。 */
    private final List<Duration> sleeps = new ArrayList<>();

    /**
     * @param start 起始时刻
     */
    OtaScenarioClock(Instant start) {
        this.now = start;
    }

    /**
     * @return 当前时刻
     */
    @Override
    public Instant now() {
        return now;
    }

    /**
     * 推进时间并记录，不阻塞。
     *
     * @param duration 等待时长
     */
    @Override
    public void sleep(Duration duration) {
        sleeps.add(duration);
        now = now.plus(duration);
    }

    /**
     * @return 已发生的退避时长，按发生顺序
     */
    List<Duration> sleeps() {
        return List.copyOf(sleeps);
    }

    /**
     * 手动推进时间，用于构造「预算已耗尽」场景。
     *
     * @param duration 推进时长
     */
    void advance(Duration duration) {
        now = now.plus(duration);
    }
}
