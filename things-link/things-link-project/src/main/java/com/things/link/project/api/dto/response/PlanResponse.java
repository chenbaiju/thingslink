package com.things.link.project.api.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.things.link.project.domain.plan.PlanCatalogEntry;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 平台套餐目录的一项：稳定编码 + 某个修订版的报价快照 + 冻结维度与权益。
 *
 * <p>{@code priceCents}、{@code referencePriceCents}、{@code referencePriceCurrency} 与
 * {@code validUntil} 未确定时整体省略（{@code NON_NULL}），JSON 里不会出现 {@code null}：
 * 未定价不是 0 元，也未设失效时刻不是「无期限配额」。
 * {@code referencePriceCents} 只是商业架构 §2 的参考价快照，<b>不是成交价</b>；
 * 是否可售只看 {@code saleStatus} 与 {@code priceCents}。
 * 响应不含密钥、订单、租户或支付渠道信息。
 *
 * @param code 稳定套餐编码
 * @param revision 产品修订版标识
 * @param revisionNo 修订序号
 * @param name 展示名称
 * @param saleStatus 销售状态
 * @param billingPeriod 计费周期
 * @param currency 币种
 * @param priceCents 成交价（人民币分）；未定价/未开售时省略
 * @param referencePriceCents 参考价快照（人民币分）；未记录时省略。它不是成交价，不改变销售状态
 * @param referencePriceCurrency 参考价币种（ISO 4217）；与参考价同生共死，恒等于 {@code currency}
 * @param validFrom 生效时刻（RFC3339）
 * @param validUntil 失效时刻（RFC3339）；未设时省略
 * @param quotaDimensions 冻结数值维度
 * @param entitlements 功能权益
 */
@Schema(description = "平台套餐目录项；成交价与参考价分离，未定价档位没有成交价字段")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PlanResponse(
        @Schema(description = "稳定套餐编码", example = "STANDARD") String code,
        @Schema(description = "产品修订版标识", example = "product-revision-1") String revision,
        @Schema(description = "同一套餐的修订序号", example = "1") int revisionNo,
        @Schema(description = "展示名称", example = "标准版") String name,
        @Schema(description = "销售状态：ON_SALE/NOT_FOR_SALE", example = "NOT_FOR_SALE") String saleStatus,
        @Schema(description = "计费周期：NONE/MONTH/YEAR", example = "YEAR") String billingPeriod,
        @Schema(description = "ISO 4217 币种", example = "CNY") String currency,
        @Schema(description = "成交价（人民币分）；未定价或未开售时省略", example = "0") Long priceCents,
        @Schema(description = "参考价快照（人民币分，商业架构 §2）；不是成交价，未记录时省略",
                example = "298000") Long referencePriceCents,
        @Schema(description = "参考价币种；恒等于 currency，与参考价同生共死", example = "CNY")
        String referencePriceCurrency,
        @Schema(description = "修订版生效时刻（含，RFC3339）") String validFrom,
        @Schema(description = "修订版失效时刻（不含，RFC3339）；未设时省略") String validUntil,
        @Schema(description = "冻结数值维度，数值恒为确定值") List<PlanQuotaDimensionResponse> quotaDimensions,
        @Schema(description = "目录能力声明，非当前运行权限；保留不可变修订版状态") List<PlanEntitlementResponse> entitlements) {

    /**
     * 领域快照转换为 HTTP 契约。
     *
     * @param entry 目录快照
     * @return 目录响应项
     */
    public static PlanResponse from(PlanCatalogEntry entry) {
        return new PlanResponse(
                entry.code(),
                entry.revision(),
                entry.revisionNo(),
                entry.name(),
                entry.saleStatus(),
                entry.billingPeriod(),
                entry.currency(),
                entry.priceCents(),
                entry.referencePriceCents(),
                entry.referencePriceCurrency(),
                entry.validFrom().toString(),
                entry.validUntil() == null ? null : entry.validUntil().toString(),
                entry.dimensions().stream().map(PlanQuotaDimensionResponse::from).toList(),
                entry.entitlements().stream().map(PlanEntitlementResponse::from).toList());
    }
}
