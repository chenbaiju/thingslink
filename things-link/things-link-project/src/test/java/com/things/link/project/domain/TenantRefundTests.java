package com.things.link.project.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * S14-5a 退款资金事实的领域级验收：金额、原因与「订单最多全额退一次」的边界。
 *
 * <p>期望值独立写死，避免生产实现与断言同时漂移。这里只钉住域类型自身的不可变式（与数据库
 * CHECK 逐条对应）；退款与权益的联动由真库用例负责。
 */
@DisplayName("S14-5a 退款资金事实")
class TenantRefundTests {

    /** 完整退款事实被逐字保留。 */
    @Test
    void completeRefundIsPreserved() {
        UUID orderId = UUID.randomUUID();
        TenantRefund refund = new TenantRefund(UUID.randomUUID(), UUID.randomUUID(), orderId, 298_000L,
                "CNY", PaymentProvider.SIMULATED, "sim-refund-1", RefundStatus.SUCCEEDED,
                "客户取消购买", Instant.parse("2026-09-17T00:00:00Z"));

        assertThat(refund.orderId()).isEqualTo(orderId);
        assertThat(refund.amountCents()).isEqualTo(298_000L);
        assertThat(refund.currency()).isEqualTo("CNY");
        assertThat(refund.provider()).isEqualTo(PaymentProvider.SIMULATED);
        assertThat(refund.providerRefundId()).isEqualTo("sim-refund-1");
        assertThat(refund.status()).isEqualTo(RefundStatus.SUCCEEDED);
        assertThat(refund.reason()).isEqualTo("客户取消购买");
    }

    /** 退款金额必须为正：0 与负数都不是一笔退款。 */
    @Test
    void amountMustBePositive() {
        assertThatThrownBy(() -> refund(0L, "原因")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> refund(-1L, "原因")).isInstanceOf(IllegalArgumentException.class);
    }

    /** 原因必填且不得是空白串：没有原因的退款无法对账与追责。 */
    @Test
    void reasonMustBePresentAndNotBlank() {
        assertThatThrownBy(() -> refund(1L, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> refund(1L, "   ")).isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> refund(1L, "有原因")).doesNotThrowAnyException();
    }

    /** 订单的已退金额必须落在 [0, 订单金额] 内：跨行不变量在行内的确定性边界。 */
    @Test
    void orderRefundedAmountMustStayWithinOrderAmount() {
        assertThatThrownBy(() -> order(298_000L, -1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("累计退款");
        assertThatThrownBy(() -> order(298_000L, 298_001L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("累计退款");
        assertThatCode(() -> order(298_000L, 0L)).doesNotThrowAnyException();
        assertThatCode(() -> order(298_000L, 298_000L)).doesNotThrowAnyException();
    }

    /** 本片只写 SUCCEEDED：FAILED 是真实渠道的预留取值，闭集必须与数据库 CHECK 一致。 */
    @Test
    void refundStatusClosedSetMatchesDatabase() {
        assertThat(RefundStatus.values()).containsExactly(RefundStatus.SUCCEEDED, RefundStatus.FAILED);
    }

    /**
     * 构造一条用于校验的退款事实。
     *
     * @param amountCents 退款金额
     * @param reason 退款原因
     * @return 退款事实
     */
    private static TenantRefund refund(long amountCents, String reason) {
        return new TenantRefund(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), amountCents,
                "CNY", PaymentProvider.SIMULATED, "sim-refund-x", RefundStatus.SUCCEEDED, reason,
                Instant.parse("2026-09-17T00:00:00Z"));
    }

    /**
     * 构造一条用于校验的订单事实（资源包订单）。
     *
     * @param amountCents 订单金额
     * @param refundedCents 已退金额
     * @return 订单事实
     */
    private static TenantOrder order(long amountCents, long refundedCents) {
        return new TenantOrder(UUID.randomUUID(), UUID.randomUUID(), null, TenantOrderKind.PACKAGE,
                null, null, new ResourcePackagePurchase("DEVICES_MAX", 2, "COUNT", "NONE", 12, null),
                PaymentProvider.SIMULATED, TenantOrderStatus.PAID, "sim-pay-x", amountCents, "CNY",
                Instant.parse("2026-09-17T00:00:00Z"), Instant.parse("2026-09-17T00:00:00Z"),
                refundedCents, 1L);
    }
}
