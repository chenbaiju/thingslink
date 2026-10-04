package com.things.link.support.resilience;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/** 断路器验证连续失败、open 窗口与 half-open 单探针。 */
class FailureCircuitBreakerRegistryTests {

    /** 三次失败打开，窗口结束只允许一个探针，成功后恢复 CLOSED。 */
    @Test
    void opensAndAllowsSingleHalfOpenProbe() {
        MutableClock clock = new MutableClock(Instant.EPOCH);
        FailureCircuitBreakerRegistry registry = new FailureCircuitBreakerRegistry(
                8, Duration.ofMinutes(30), 3, Duration.ofSeconds(30), clock);
        for (int failure = 0; failure < 3; failure++) {
            registry.tryAcquire("provider").retryableFailure();
        }
        assertThat(registry.tryAcquire("provider")).isNull();

        clock.advance(Duration.ofSeconds(30));
        FailureCircuitBreakerRegistry.Permit probe = registry.tryAcquire("provider");
        assertThat(probe).isNotNull();
        assertThat(registry.tryAcquire("provider")).as("half-open 只能有一个在途探针").isNull();
        probe.success();
        assertThat(registry.tryAcquire("provider")).isNotNull();
    }

    /** 永久业务错误既不增加也不清空基础设施失败，否则四次 5xx 中夹一个 4xx 会错误掩盖熔断。 */
    @Test
    void ignoredFailureDoesNotResetClosedFailureCount() {
        MutableClock clock = new MutableClock(Instant.EPOCH);
        FailureCircuitBreakerRegistry registry = new FailureCircuitBreakerRegistry(
                8, Duration.ofMinutes(30), 3, Duration.ofSeconds(30), clock);

        registry.tryAcquire("provider").retryableFailure();
        registry.tryAcquire("provider").ignoredFailure();
        registry.tryAcquire("provider").retryableFailure();
        registry.tryAcquire("provider").retryableFailure();

        assertThat(registry.tryAcquire("provider")).isNull();
    }

    /** 未发送取消必须保留OPEN状态，只释放当前探针，不把数据库恢复冒充渠道成功。 */
    @Test
    void cancelledHalfOpenProbeKeepsFailureStateAndAllowsOnlyOneReplacement() {
        MutableClock clock = new MutableClock(Instant.EPOCH);
        FailureCircuitBreakerRegistry registry = new FailureCircuitBreakerRegistry(
                8, Duration.ofMinutes(30), 3, Duration.ofSeconds(30), clock);
        for (int failure = 0; failure < 3; failure++) registry.tryAcquire("provider").retryableFailure();
        clock.advance(Duration.ofSeconds(30));
        FailureCircuitBreakerRegistry.Permit first = registry.tryAcquire("provider");
        cancelBeforeCall(first);
        FailureCircuitBreakerRegistry.Permit replacement = registry.tryAcquire("provider");
        assertThat(replacement).isNotNull();
        assertThat(registry.tryAcquire("provider")).as("未发送不能把HALF_OPEN变成无限CLOSED资格").isNull();
        cancelBeforeCall(first);
        assertThat(registry.tryAcquire("provider")).as("重复取消旧探针不得释放后继探针").isNull();
        replacement.retryableFailure();
        assertThat(registry.tryAcquire("provider")).isNull();
        clock.advance(Duration.ofSeconds(29));
        assertThat(registry.tryAcquire("provider")).isNull();
        clock.advance(Duration.ofSeconds(1));
        FailureCircuitBreakerRegistry.Permit recovered = registry.tryAcquire("provider");
        assertThat(recovered).isNotNull();
        recovered.success();
    }

    /** 取消CLOSED许可既不清空已有失败，也不能取消另一次half-open资格。 */
    @Test
    void cancelledClosedPermitPreservesFailuresAndCannotReleaseAnotherProbe() {
        MutableClock clock = new MutableClock(Instant.EPOCH);
        FailureCircuitBreakerRegistry registry = new FailureCircuitBreakerRegistry(
                8, Duration.ofMinutes(30), 3, Duration.ofSeconds(30), clock);
        FailureCircuitBreakerRegistry.Permit old = registry.tryAcquire("provider");
        registry.tryAcquire("provider").retryableFailure();
        cancelBeforeCall(registry.tryAcquire("provider"));
        registry.tryAcquire("provider").retryableFailure();
        registry.tryAcquire("provider").retryableFailure();
        assertThat(registry.tryAcquire("provider")).isNull();
        clock.advance(Duration.ofSeconds(30));
        FailureCircuitBreakerRegistry.Permit probe = registry.tryAcquire("provider");
        assertThat(probe).isNotNull();
        cancelBeforeCall(old);
        assertThat(registry.tryAcquire("provider")).isNull();
        probe.success();
    }

    /** ADR0069未发送取消端口；旧ignoredFailure错误路径的RED已独立保存。 */
    private static void cancelBeforeCall(FailureCircuitBreakerRegistry.Permit permit) {
        permit.cancelBeforeCall();
    }

    /** 可推进的测试时钟。 */
    private static final class MutableClock extends Clock {
        /** 当前时刻。 */
        private Instant now;

        /** @param now 初始时刻 */
        private MutableClock(Instant now) {
            this.now = now;
        }

        /** 推进测试时间。 */
        private void advance(Duration duration) {
            now = now.plus(duration);
        }

        /** {@inheritDoc} */
        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        /** {@inheritDoc} */
        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        /** {@inheritDoc} */
        @Override
        public Instant instant() {
            return now;
        }
    }
}
