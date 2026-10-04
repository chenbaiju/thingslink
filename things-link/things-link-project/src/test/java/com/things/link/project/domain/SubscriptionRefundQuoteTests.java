package com.things.link.project.domain;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ADR0166 整组金额与来源去重的数值安全边界。 */
class SubscriptionRefundQuoteTests {
    /** 冻结计算时刻。 */
    private static final Instant NOW=Instant.parse("2026-01-01T00:00:00Z");
    /** 两笔独立合法long金额不能在整组加总时回绕。 */
    @Test
    void aggregateOverflowNeverWrapsIntoSmallerRefund() {
        assertThatThrownBy(() -> quote(List.of(line(Long.MAX_VALUE),line(1)),0)).isInstanceOf(ArithmeticException.class);
    }
    /** 同一订单不能在整组来源中算两次。 */
    @Test
    void duplicateSourceCannotBeCountedTwice() {
        var line=line(1);
        assertThatThrownBy(() -> quote(List.of(line,line),2)).isInstanceOf(IllegalArgumentException.class);
    }
    /** 构造边界报价，不依赖目录或外部资金。 */
    private SubscriptionRefundQuote quote(List<SubscriptionRefundQuote.Line> lines,long total) {
        return new SubscriptionRefundQuote(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),1,NOW,NOW.plusSeconds(300),
                "数值边界",SubscriptionRefundQuote.ALGORITHM,total,"{}",lines);
    }
    /** 单笔long边界合法的1日来源。 */
    private SubscriptionRefundQuote.Line line(long amount) {
        return new SubscriptionRefundQuote.Line(UUID.randomUUID(),UUID.randomUUID(),TenantOrderKind.PURCHASE,NOW,NOW.plusSeconds(86400),
                1,1,amount,0,1,1,amount);
    }
}
