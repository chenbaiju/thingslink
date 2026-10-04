package com.things.link.project.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * 注册时写入订阅行的 FREE 价目快照（S14-2a）。
 *
 * <p>它来自 {@code product-revision-1} 的 FREE 修订版与其绑定的 {@code PLAN_R1_FREE} 配额模板，
 * 而不是调用方传参：注册路径不得自行编造价格或指向另一个修订版。价格与币种是本字段的
 * <b>成交快照</b>，写入订阅行后不再随目录变化，调价只影响后来的订阅。
 *
 * @param planRevisionId 成交锁定的产品修订版 ID
 * @param quotaPolicyId 该修订版绑定的配额模板 ID；注册同一事务把它绑为租户有效策略指针
 * @param billingPeriod 计费周期快照，FREE 为 {@code NONE}
 * @param priceCents 成交价目快照（人民币分）；FREE 是真实的 0
 * @param currency ISO 4217 币种快照，一律 {@code CNY}
 */
public record FreeSubscriptionSnapshot(
        UUID planRevisionId,
        UUID quotaPolicyId,
        String billingPeriod,
        long priceCents,
        String currency) {

    /** 快照必须完整且自洽：缺任何一项都说明目录处于半迁移状态，必须当场失败而不是写半行事实。 */
    public FreeSubscriptionSnapshot {
        Objects.requireNonNull(planRevisionId, "产品修订版 ID 不得为空");
        Objects.requireNonNull(quotaPolicyId, "配额模板 ID 不得为空");
        Objects.requireNonNull(billingPeriod, "计费周期不得为空");
        Objects.requireNonNull(currency, "币种不得为空");
        if (priceCents < 0) {
            throw new IllegalArgumentException("成交价目快照不得为负数");
        }
    }
}
