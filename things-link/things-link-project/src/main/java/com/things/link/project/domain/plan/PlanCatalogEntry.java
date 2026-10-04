package com.things.link.project.domain.plan;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 目录投影：一个稳定套餐编码在某个修订版上的完整报价快照。
 *
 * <p>报价字段（名称、销售状态、计费周期、币种、价格、有效期）与权益/维度来自同一不可变
 * 修订版行，读取端不再二次拼接，避免把不同修订版的价格和额度混在一起。
 *
 * @param code 稳定套餐编码，如 {@code FREE}；中文名只在 {@code name}
 * @param displayOrder 定价页展示顺序；只影响排序，不是可售权益
 * @param revision 产品修订版标识，如 {@code product-revision-1}
 * @param revisionNo 同一套餐的单调修订序号
 * @param name 展示名称（中文）
 * @param saleStatus {@code ON_SALE} 或 {@code NOT_FOR_SALE}
 * @param billingPeriod {@code NONE}、{@code MONTH} 或 {@code YEAR}
 * @param currency ISO 4217 币种，一律 {@code CNY}
 * @param priceCents 成交价快照（人民币分）；未定价/未开售时为 {@code null}，绝不表示「不限」
 * @param referencePriceCents 参考价快照（人民币分），来自商业架构 §2；未记录时为 {@code null}。
 *        它<b>不是成交价</b>：在售判定只看 {@link #priceCents()}，订单/支付逻辑不得读本字段
 * @param referencePriceCurrency 参考价币种；与 {@code referencePriceCents} 同生共死，且恒等于 {@code currency}
 * @param validFrom 修订版生效时刻（含）
 * @param validUntil 修订版失效时刻（不含）；{@code null} 表示未设失效时刻
 * @param dimensions 冻结数值维度，按编码排序
 * @param entitlements 功能权益，按编码排序
 */
public record PlanCatalogEntry(
        String code,
        int displayOrder,
        String revision,
        int revisionNo,
        String name,
        String saleStatus,
        String billingPeriod,
        String currency,
        Long priceCents,
        Long referencePriceCents,
        String referencePriceCurrency,
        Instant validFrom,
        Instant validUntil,
        List<PlanDimension> dimensions,
        List<PlanEntitlement> entitlements) {

    /** 快照的必填字段不得为空，列表必须是非空只读副本；参考价与其币种必须同生共死。 */
    public PlanCatalogEntry {
        Objects.requireNonNull(code, "套餐编码不得为空");
        Objects.requireNonNull(revision, "产品修订版标识不得为空");
        Objects.requireNonNull(name, "套餐名称不得为空");
        Objects.requireNonNull(saleStatus, "销售状态不得为空");
        Objects.requireNonNull(billingPeriod, "计费周期不得为空");
        Objects.requireNonNull(currency, "币种不得为空");
        Objects.requireNonNull(validFrom, "生效时刻不得为空");
        if ((referencePriceCents == null) != (referencePriceCurrency == null)) {
            throw new IllegalArgumentException("参考价与参考价币种必须同时存在或同时缺失");
        }
        if (referencePriceCents != null && referencePriceCents < 0) {
            throw new IllegalArgumentException("参考价不得为负数");
        }
        dimensions = List.copyOf(dimensions);
        entitlements = List.copyOf(entitlements);
    }

    /**
     * 是否处于在售状态。
     *
     * @return {@code ON_SALE} 时为 {@code true}
     */
    public boolean onSale() {
        return "ON_SALE".equals(saleStatus);
    }

    /**
     * 查询某项能力在该档位是否启用。
     *
     * @param capabilityCode 冻结 capability code
     * @return 该编码存在且状态为启用时返回 {@code true}；未知编码返回 {@code false}
     */
    public boolean entitlementEnabled(String capabilityCode) {
        return entitlements.stream()
                .anyMatch(entitlement -> entitlement.code().equals(capabilityCode) && entitlement.enabled());
    }
}
