package com.things.link.telemetry.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** S4-3 命令受理、派发失败与终态低基数指标，不使用项目或设备标签。 */
@Component
public class DeviceCommandMetrics {
    /** 设备响应超时终态原因标签值。 */
    public static final String REASON_RESPONSE_TIMEOUT = "response_timeout";
    /** 派发重试耗尽终态原因标签值（三次均未交给 Broker）。 */
    public static final String REASON_DISPATCH_RETRY_EXHAUSTED = "dispatch_retry_exhausted";

    /** API 到 Outbox 完成的受理延迟。 */ private final Timer acceptance;
    /** 单次下行派发失败数（EMQX 不可达/凭据缺失/超时/拒绝）。 */ private final Counter dispatchFailure;
    /** 待发到期恢复次数，不把未知交付解释为设备离线。 */ private final Counter pendingTimeout;
    /** 设备响应超时终态数。 */ private final Counter terminalResponseTimeout;
    /** 派发重试耗尽终态数。 */ private final Counter terminalDispatchRetryExhausted;

    /** @param registry Micrometer 注册表 */
    /** 单设备待发命令超预算计数（§6 每设备 100 条：超出只打点，绝不丢单）。 */
    public static final String PENDING_OVER_BUDGET = "device.access.command.pending.over_budget";

    /** 最近扫描观测到的最老待发年龄，尚未观测不报零。 */
    private final java.util.concurrent.atomic.AtomicReference<Double> oldestPendingAge =
            new java.util.concurrent.atomic.AtomicReference<>(Double.NaN);
    /** 待发超预算计数器。 */
    private final Counter pendingOverBudget;

    public DeviceCommandMetrics(MeterRegistry registry) {
        this.pendingOverBudget = registry.counter(PENDING_OVER_BUDGET);
        io.micrometer.core.instrument.Gauge.builder("thingslink.command.pending_oldest_age_seconds", oldestPendingAge,
                value -> value.get()).register(registry);
        pendingTimeout = registry.counter("thingslink.command.pending_timeout");
        acceptance = Timer.builder("thingslink.command.acceptance")
                .description("设备命令从应用服务进入到命令事实与Outbox写入完成的延迟")
                // 架构文档 13.1 的 P95 必须可跨实例聚合，客户端本地 percentile 无法满足该语义。
                .publishPercentileHistogram()
                .serviceLevelObjectives(Duration.ofMillis(50), Duration.ofMillis(100),
                        Duration.ofMillis(200), Duration.ofMillis(500), Duration.ofSeconds(1))
                .register(registry);
        dispatchFailure = Counter.builder("thingslink.command.dispatch_failure")
                .description("平台下行派发失败（EMQX 不可达/凭据缺失/超时/拒绝）的尝试数").register(registry);
        // TIMED_OUT 终态按原因拆分，避免平台派发失败污染「设备响应超时」告警（D-037）。
        terminalResponseTimeout = Counter.builder("thingslink.command.terminal")
                .description("设备命令终态数，按原因拆分")
                .tag("reason", REASON_RESPONSE_TIMEOUT).register(registry);
        terminalDispatchRetryExhausted = Counter.builder("thingslink.command.terminal")
                .description("设备命令终态数，按原因拆分")
                .tag("reason", REASON_DISPATCH_RETRY_EXHAUSTED).register(registry);
    }

    /** 扫描线程报告数据库权威待发年龄。 */
    public void observeOldestPendingAge(double seconds) { oldestPendingAge.set(seconds); }
    /** @param elapsed 受理耗时 */ public void recordAccepted(Duration elapsed) { acceptance.record(elapsed); }
    /** 记录一次平台下行派发失败。 */ public void recordDispatchFailure() { dispatchFailure.increment(); }
    /** 记录一次PENDING到期恢复尝试。 */ public void recordPendingTimeout() { pendingTimeout.increment(); }
    /** 记录一次单设备待发量超预算；调用方必须仍然保留命令。 */
    public void recordPendingOverBudget() { pendingOverBudget.increment(); }
    /** @param reason 终态原因标签，只允许两个冻结值 */
    public void recordTerminal(String reason) {
        switch (reason) {
            case REASON_RESPONSE_TIMEOUT -> terminalResponseTimeout.increment();
            case REASON_DISPATCH_RETRY_EXHAUSTED -> terminalDispatchRetryExhausted.increment();
            default -> throw new IllegalArgumentException("未知命令终态原因: " + reason);
        }
    }
}
