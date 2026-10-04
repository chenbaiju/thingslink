package com.things.link.project.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * 下单所需的修订版购买投影（S14-3a）。
 *
 * <p>它把「订单能引用什么」一次读齐：稳定档位编码（用于拒绝 FREE）、销售状态（用于审计留痕）、
 * 计费周期、币种、<b>参考价</b>与生效后要绑定的配额模板。价格口径按负责人确认的选项 (b)：
 * 付费档 {@code saleStatus = NOT_FOR_SALE} 且无成交价，模拟订单的金额只能取
 * {@code referencePriceCents}，并始终以 {@link PaymentProvider#SIMULATED} 标记为模拟。
 *
 * @param planRevisionId 产品修订版 ID
 * @param planCode 稳定档位编码，如 {@code STANDARD}
 * @param displayOrder 档位展示顺序（10/20/30/40）；S14-3b 用它判定升级/降级方向，数值越大档位越高
 * @param saleStatus 销售状态，如 {@code NOT_FOR_SALE}
 * @param billingPeriod 计费周期快照：{@code MONTH} 或 {@code YEAR}（{@code NONE} 不可下单）
 * @param currency ISO 4217 币种
 * @param referencePriceCents 参考价（人民币分）；未记录时为 {@code null}
 * @param quotaPolicyId 该修订版绑定的配额模板 ID；生效时经 S7 绑定为租户有效策略
 */
public record PlanPurchase(
        UUID planRevisionId,
        String planCode,
        int displayOrder,
        String saleStatus,
        String billingPeriod,
        String currency,
        Long referencePriceCents,
        UUID quotaPolicyId) {

    /** 购买投影必须能唯一指向一个修订版与档位，且展示顺序为正。 */
    public PlanPurchase {
        Objects.requireNonNull(planRevisionId, "产品修订版 ID 不得为空");
        Objects.requireNonNull(planCode, "档位编码不得为空");
        Objects.requireNonNull(billingPeriod, "计费周期不得为空");
        Objects.requireNonNull(currency, "币种不得为空");
        if (displayOrder <= 0) {
            throw new IllegalArgumentException("档位展示顺序必须为正数");
        }
    }
}
