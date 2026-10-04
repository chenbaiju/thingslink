package com.things.link.project.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * S14-5b 支付失败事实的领域级验收：失败分类形状、事件 ID 与金额边界。
 *
 * <p>期望值独立写死，避免生产实现与断言同时漂移。这里只钉住域类型自身的不可变式（与数据库
 * CHECK 逐条对应）；「失败不产生任何权益后果」由真库用例负责。
 */
@DisplayName("S14-5b 支付失败事实")
class OrderPaymentFailureTests {

    /** 完整失败事实被逐字保留。 */
    @Test
    void completeFailureIsPreserved() {
        UUID orderId = UUID.randomUUID();
        OrderPaymentFailure failure = new OrderPaymentFailure(UUID.randomUUID(), UUID.randomUUID(),
                orderId, PaymentProvider.SIMULATED, "sim-fail-1", "INSUFFICIENT_FUNDS", 298_000L,
                "CNY", Instant.parse("2026-09-17T00:00:00Z"), Instant.parse("2026-09-17T00:00:01Z"));

        assertThat(failure.orderId()).isEqualTo(orderId);
        assertThat(failure.failureCode()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(failure.amountCents()).isEqualTo(298_000L);
        assertThat(failure.currency()).isEqualTo("CNY");
        assertThat(failure.occurredAt()).isBefore(failure.createdAt());
    }

    /** 渠道失败事件 ID 必填且不得是空白串（它是幂等键）。 */
    @Test
    void providerEventIdIsRequired() {
        assertThatThrownBy(() -> failure(null, "TIMEOUT", 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("事件 ID");
        assertThatThrownBy(() -> failure("   ", "TIMEOUT", 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("事件 ID");
    }

    /** 失败分类只钉形状：小写、下划线开头、过短与过长都拒绝。 */
    @Test
    void failureCodeShapeIsEnforced() {
        assertThatThrownBy(() -> failure("sim-fail-x", "timeout", 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("失败分类");
        assertThatThrownBy(() -> failure("sim-fail-x", "_TIMEOUT", 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> failure("sim-fail-x", "AB", 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> failure("sim-fail-x", "A".repeat(65), 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> failure("sim-fail-x", "CHANNEL_REJECTED", 1L)).doesNotThrowAnyException();
        assertThatCode(() -> failure("sim-fail-x", "A".repeat(64), 1L)).doesNotThrowAnyException();
    }

    /** 失败快照金额不得为负数（0 是合法的：零元订单也可能失败）。 */
    @Test
    void snapshotAmountMustNotBeNegative() {
        assertThatThrownBy(() -> failure("sim-fail-x", "TIMEOUT", -1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("金额");
        assertThatCode(() -> failure("sim-fail-x", "TIMEOUT", 0L)).doesNotThrowAnyException();
    }

    /**
     * 构造一条用于校验的失败事实。
     *
     * @param providerEventId 渠道失败事件 ID
     * @param failureCode 失败分类
     * @param amountCents 快照金额
     * @return 失败事实
     */
    private static OrderPaymentFailure failure(String providerEventId, String failureCode,
                                              long amountCents) {
        return new OrderPaymentFailure(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                PaymentProvider.SIMULATED, providerEventId, failureCode, amountCents, "CNY",
                Instant.parse("2026-09-17T00:00:00Z"), Instant.parse("2026-09-17T00:00:01Z"));
    }
}
