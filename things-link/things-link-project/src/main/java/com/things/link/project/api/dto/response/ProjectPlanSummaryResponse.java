package com.things.link.project.api.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.things.link.project.domain.TenantPlanSummary;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 项目设置页的租户套餐摘要（S14-2c）。
 *
 * <p><b>命名口径必须诚实。</b>{@code subscribedPlan} 是租户 ACTIVE 订阅锁定的修订版快照，
 * {@code effectivePlan} 是运行时配额指针当前实际绑定的档位；两者可能不一致，因此并列暴露，
 * 并由 {@code effectiveMatchesSubscribed} 显式给出结论。{@code effectivePlan} 缺省表示运行时
 * 绑定不是可售套餐模板（例如 S7 遗留 FREE 基线），<b>不是「不限」</b>。
 *
 * <p>{@code quotaDimensions} 与 {@code capabilities} 恒来自 {@code subscribedPlan} 锁定的修订版，
 * 是租户与平台之间的冻结契约；{@code DISABLED} 权益保留且不携带数值额度。成交价、订单、支付渠道
 * 与其他租户的事实都不在本响应里。{@code referencePriceCents} 只是商业架构 §2 的参考价快照，
 * 不是成交价，也不改变销售状态（付费三档一律 {@code NOT_FOR_SALE}）。
 *
 * <p>{@code endsAt} 缺省表示服务期没有终点（FREE 长期有效档），{@code perpetual=true} 把这一点
 * 显式表达出来，避免控制台把缺省读成「加载失败」。未记录的参考价同样整体省略而不是显示为 0。
 *
 * <p><b>S14-4c 有效额度与溯源。</b>{@code effectiveQuotaDimensions} 是运行时**实际生效**的额度
 * （运行时单位，如对象存储写 {@code BYTE}），已计入此刻有效的资源包与人工调整；它与
 * {@code quotaDimensions}（修订版冻结的目录表示，例如对象存储写 {@code 100 MB}）并列而不是替换，
 * 两者单位不同、不得互相冒充。运行时绑定不是可售套餐模板时该字段整体省略，表示「没有运行时额度
 * 投影」，<b>不是额度为零</b>。{@code additions} 是扩容与人工调整的溯源行（含待生效），可以为空；
 * 它不含操作人账号。
 *
 * @param subscribedPlan 订阅锁定的档位身份
 * @param effectivePlan 运行时实际绑定的档位身份；绑定不是套餐模板时省略
 * @param effectiveMatchesSubscribed 运行时绑定是否与订阅修订版一致
 * @param subscriptionStatus 订阅状态：{@code ACTIVE}/{@code GRACE}/{@code RESTRICTED_FREE} 都是
 *        活状态（S14-6b），受限期继续返回摘要但状态与运行时绑定会如实显示不一致
 * @param startsAt 服务期起始时刻（含，RFC3339）
 * @param endsAt 服务期结束时刻（不含，RFC3339）；长期有效时省略
 * @param perpetual 服务期是否没有终点
 * @param billingPeriod 计费周期：{@code NONE}/{@code MONTH}/{@code YEAR}
 * @param renewalMode 续费方式：{@code NONE}/{@code AUTO}/{@code MANUAL}
 * @param referencePriceCents 参考价快照（人民币分）；不是成交价，未记录时省略
 * @param referencePriceCurrency 参考价币种；与参考价同生共死
 * @param quotaDimensions 冻结数值维度（目录表示），按编码排序
 * @param capabilities 功能权益，含 {@code DISABLED}，按编码排序
 * @param effectiveQuotaDimensions 运行时生效维度（运行时单位，含有效扩容与调整）；无运行时额度投影时省略
 * @param additions 扩容与人工调整溯源行，按起点升序；没有时为空数组
 */
@Schema(description = "租户套餐摘要：订阅锁定的档位、服务期、冻结额度与功能权益")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProjectPlanSummaryResponse(
        @Schema(description = "订阅锁定的档位身份") PlanIdentityResponse subscribedPlan,
        @Schema(description = "运行时实际绑定的档位身份；绑定不是套餐模板时省略", nullable = true)
        PlanIdentityResponse effectivePlan,
        @Schema(description = "运行时绑定是否与订阅修订版一致", example = "true")
        boolean effectiveMatchesSubscribed,
        @Schema(description = "订阅状态", example = "ACTIVE") String subscriptionStatus,
        @Schema(description = "服务期起始时刻（含，RFC3339）") String startsAt,
        @Schema(description = "服务期结束时刻（不含，RFC3339）；长期有效时省略", nullable = true)
        String endsAt,
        @Schema(description = "服务期是否没有终点", example = "true") boolean perpetual,
        @Schema(description = "计费周期", example = "NONE") String billingPeriod,
        @Schema(description = "续费方式", example = "NONE") String renewalMode,
        @Schema(description = "参考价快照（人民币分）；不是成交价，未记录时省略", nullable = true,
                example = "0") Long referencePriceCents,
        @Schema(description = "参考价币种；恒等于修订版币种", nullable = true, example = "CNY")
        String referencePriceCurrency,
        @Schema(description = "冻结数值维度（目录表示），数值恒为确定值")
        List<PlanQuotaDimensionResponse> quotaDimensions,
        @Schema(description = "目录能力声明，非当前运行权限；保留不可变修订版状态") List<PlanEntitlementResponse> capabilities,
        @Schema(description = "运行时生效维度（运行时单位，含有效扩容与调整）；无运行时额度投影时省略",
                nullable = true) List<PlanQuotaDimensionResponse> effectiveQuotaDimensions,
        @Schema(description = "扩容与人工调整溯源行；不含操作人账号")
        List<PlanQuotaAdditionResponse> additions) {

    /**
     * 领域摘要转换为 HTTP 契约。
     *
     * @param summary 由当前项目归属租户的订阅事实合成
     * @return 不含租户 ID、成交价或订单信息的套餐摘要
     */
    public static ProjectPlanSummaryResponse from(TenantPlanSummary summary) {
        return new ProjectPlanSummaryResponse(
                PlanIdentityResponse.from(summary.subscribedPlan()),
                summary.effectivePlan() == null ? null : PlanIdentityResponse.from(summary.effectivePlan()),
                summary.effectiveMatchesSubscribed(),
                summary.subscriptionStatus(),
                summary.serviceStartsAt().toString(),
                summary.serviceEndsAt() == null ? null : summary.serviceEndsAt().toString(),
                summary.perpetual(),
                summary.billingPeriod(),
                summary.renewalMode(),
                summary.referencePriceCents(),
                summary.referencePriceCurrency(),
                summary.quotaDimensions().stream().map(PlanQuotaDimensionResponse::from).toList(),
                summary.capabilities().stream().map(PlanEntitlementResponse::from).toList(),
                summary.hasEffectiveQuotaDimensions()
                        ? summary.effectiveQuotaDimensions().stream()
                                .map(PlanQuotaDimensionResponse::from).toList()
                        : null,
                summary.additions().stream().map(PlanQuotaAdditionResponse::from).toList());
    }
}
