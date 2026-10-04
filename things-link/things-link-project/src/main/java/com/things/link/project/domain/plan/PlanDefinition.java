package com.things.link.project.domain.plan;

import java.util.List;
import java.util.Objects;

/**
 * 一个套餐修订版的冻结定义，用作目录播种的输入。
 *
 * <p>与 {@link PlanCatalogEntry} 的区别是这里没有持久化时刻（生效/失效时间由写入方决定），
 * 也不携带主键；它表达的是 S14-0 冻结记录里的产品值本身。
 *
 * @param code 稳定套餐编码
 * @param displayOrder 定价页展示顺序
 * @param revision 产品修订版标识
 * @param revisionNo 同一套餐的单调修订序号
 * @param name 展示名称（中文）
 * @param saleStatus {@code ON_SALE} 或 {@code NOT_FOR_SALE}
 * @param billingPeriod {@code NONE}、{@code MONTH} 或 {@code YEAR}
 * @param currency ISO 4217 币种
 * @param priceCents 成交价（人民币分）；未定价/未开售时为 {@code null}
 * @param referencePriceCents 参考价（人民币分，商业架构 §2 快照）；不是成交价，可为 {@code null}
 * @param dimensions 冻结数值维度
 * @param entitlements 功能权益
 */
public record PlanDefinition(
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
        List<PlanDimension> dimensions,
        List<PlanEntitlement> entitlements) {

    /** 定义必须自洽：在售必有成交价，未开售必须无成交价；参考价独立于销售状态。 */
    public PlanDefinition {
        Objects.requireNonNull(code, "套餐编码不得为空");
        Objects.requireNonNull(revision, "产品修订版标识不得为空");
        Objects.requireNonNull(name, "套餐名称不得为空");
        Objects.requireNonNull(currency, "币种不得为空");
        if (displayOrder <= 0 || revisionNo <= 0) {
            throw new IllegalArgumentException("展示顺序与修订序号必须为正数");
        }
        if (!"ON_SALE".equals(saleStatus) && !"NOT_FOR_SALE".equals(saleStatus)) {
            throw new IllegalArgumentException("销售状态不合法");
        }
        if (("ON_SALE".equals(saleStatus)) != (priceCents != null)) {
            throw new IllegalArgumentException("在售套餐必须有价格，未开售套餐必须没有价格");
        }
        if (priceCents != null && priceCents < 0) {
            throw new IllegalArgumentException("价格不得为负数");
        }
        if (referencePriceCents != null && referencePriceCents < 0) {
            throw new IllegalArgumentException("参考价不得为负数");
        }
        dimensions = List.copyOf(dimensions);
        entitlements = List.copyOf(entitlements);
    }
}
