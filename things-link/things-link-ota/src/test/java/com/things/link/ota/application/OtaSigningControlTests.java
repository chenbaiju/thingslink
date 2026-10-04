package com.things.link.ota.application;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 预算/取消控制只产生可观察停止信号，不把信号声明为供应商物理退出。 */
class OtaSigningControlTests {
    /** 外部失租触发全部存活钩子，每个最多一次，异常不阻断后续钩子。 */
    @Test
    void cancellationIsIdempotentAndCallbackFailuresDoNotStopOthers() {
        AtomicBoolean lost = new AtomicBoolean();
        AtomicInteger calls = new AtomicInteger();
        OtaSigningControl control = new OtaSigningControl(Duration.ofSeconds(90), lost::get);
        control.onCancel(() -> { throw new IllegalStateException("provider secret"); });
        control.onCancel(calls::incrementAndGet);
        lost.set(true);
        assertThatThrownBy(control::check).isInstanceOfSatisfying(OtaSigningControl.Failure.class,
                failure -> { assertThat(failure.reason()).isEqualTo(OtaSigningControl.Reason.CANCELLED);
                    assertThat(failure.getCause()).isNull(); assertThat(failure.getMessage()).isEqualTo("CANCELLED"); });
        control.cancel();
        assertThat(control.cancelled()).isTrue();
        assertThat(calls).hasValue(1);
    }

    /** 已完成请求注销自身，后来取消不再调用；停止后新注册仍立即触发一次。 */
    @Test
    void removesFinishedHooksAndImmediatelyCancelsLateRegistration() {
        AtomicInteger calls = new AtomicInteger();
        OtaSigningControl control = new OtaSigningControl(Duration.ofSeconds(90), () -> false);
        var registration = control.onCancel(calls::incrementAndGet);
        registration.close();
        registration.close();
        control.cancel();
        assertThat(calls).hasValue(0);
        control.onCancel(calls::incrementAndGet).close();
        control.cancel();
        assertThat(calls).hasValue(1);
    }

    /** 单调预算归零触发TIMEOUT，之后主动取消不能覆盖首因。 */
    @Test
    void deadlineKeepsTimeoutAsFirstCause() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        OtaSigningControl control = new OtaSigningControl(Duration.ofMillis(1), () -> false);
        control.onCancel(calls::incrementAndGet);
        Thread.sleep(10);
        assertThatThrownBy(control::check).isInstanceOfSatisfying(OtaSigningControl.Failure.class,
                failure -> assertThat(failure.reason()).isEqualTo(OtaSigningControl.Reason.TIMEOUT));
        control.cancel();
        assertThat(control.remainingNanos()).isZero();
        assertThat(calls).hasValue(1);
        assertThatThrownBy(control::check).isInstanceOfSatisfying(OtaSigningControl.Failure.class,
                failure -> assertThat(failure.reason()).isEqualTo(OtaSigningControl.Reason.TIMEOUT));
    }

    /** 取消源自身故障保守停止；零预算和超过90秒均拒绝。 */
    @Test
    void signalFailureAndInvalidBudgetsFailClosed() {
        assertThatThrownBy(() -> new OtaSigningControl(Duration.ZERO, () -> false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OtaSigningControl(Duration.ofSeconds(91), () -> false)).isInstanceOf(IllegalArgumentException.class);
        OtaSigningControl control = new OtaSigningControl(Duration.ofSeconds(90), () -> { throw new IllegalStateException("private"); });
        assertThat(control.cancelled()).isTrue();
        assertThatThrownBy(control::check).hasMessage("CANCELLED").hasNoCause();
    }
}
