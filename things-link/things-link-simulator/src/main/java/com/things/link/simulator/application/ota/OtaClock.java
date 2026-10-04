package com.things.link.simulator.application.ota;

import java.time.Duration;
import java.time.Instant;

/**
 * 可注入时钟端口。
 *
 * <p>把 {@code now()} 与 {@code sleep()} 同时抽象出来，是为了让场景既是确定性的、又真的不睡觉：
 * 测试用推进式假时钟把退避与期限压缩成零耗时，同时仍能断言「发生了退避」和「预算用尽」，
 * 而不必靠 {@code Thread.sleep} 猜时间。生产实现（{@code SystemOtaClock}）直接使用真实墙钟。</p>
 */
public interface OtaClock {

    /**
     * 返回当前时刻。
     *
     * @return 当前 UTC 时刻
     */
    Instant now();

    /**
     * 等待给定时长。
     *
     * <p>测试时钟只推进自身时间、不真正阻塞；生产实现会真实休眠。被中断时必须恢复中断位并抛出，
     * 避免断电/停机场景把中断吞掉后继续刷写。</p>
     *
     * @param duration 等待时长，必须为非负
     */
    void sleep(Duration duration);
}
