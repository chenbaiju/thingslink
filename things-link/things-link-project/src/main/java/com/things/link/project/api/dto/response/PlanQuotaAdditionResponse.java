package com.things.link.project.api.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.things.link.project.domain.PlanQuotaAddition;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 一条扩容或人工调整的只读溯源行（S14-4c）。
 *
 * <p>它回答租户的「我的额度为什么比套餐高」：{@code source} 区分购买与运营调整，
 * {@code amount} 是该窗口内为 {@code dimensionCode} 追加的额度，{@code effectiveNow} 是服务端
 * 算出的「此刻是否已计入有效权益」。
 *
 * <p><b>不含操作人账号</b>：人工调整的原因对租户可见，但平台内部人员身份不在租户契约里
 * （最小披露）。{@code operatorId} 只出现在平台侧审计台账，不随本响应下发。
 *
 * @param source 来源：{@code PURCHASE} 购买、{@code OPERATION_ADJUSTMENT} 运营人工调整
 * @param dimensionCode 被追加额度的冻结维度编码
 * @param amount 该维度追加的额度，恒为正
 * @param unit 额度单位（运行时单位）
 * @param window 计量窗口
 * @param startsAt 自身服务期起点（UTC，含，RFC3339）
 * @param endsAt 自身服务期终点（UTC，不含，RFC3339）
 * @param status 生命周期状态：{@code ACTIVE}/{@code PENDING}
 * @param effectiveNow 此刻是否确实参与有效权益合成
 * @param reason 人工调整原因；购买包省略该字段
 */
@Schema(description = "扩容或人工调整溯源行：额度为什么比套餐冻结值高")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PlanQuotaAdditionResponse(
        @Schema(description = "来源", example = "OPERATION_ADJUSTMENT") String source,
        @Schema(description = "维度编码", example = "DEVICES_MAX") String dimensionCode,
        @Schema(description = "追加额度", example = "2") long amount,
        @Schema(description = "额度单位（运行时单位）", example = "COUNT") String unit,
        @Schema(description = "计量窗口", example = "NONE") String window,
        @Schema(description = "自身服务期起点（含，RFC3339）") String startsAt,
        @Schema(description = "自身服务期终点（不含，RFC3339）") String endsAt,
        @Schema(description = "生命周期状态", example = "ACTIVE") String status,
        @Schema(description = "此刻是否已计入有效权益", example = "true") boolean effectiveNow,
        @Schema(description = "人工调整原因；购买包省略", nullable = true) String reason) {

    /**
     * 领域溯源行转换为 HTTP 契约。
     *
     * @param addition 溯源行
     * @return 不含操作人身份的响应
     */
    public static PlanQuotaAdditionResponse from(PlanQuotaAddition addition) {
        return new PlanQuotaAdditionResponse(
                addition.source().name(), addition.dimensionCode(), addition.amount(),
                addition.unit(), addition.window(), addition.startsAt().toString(),
                addition.endsAt().toString(), addition.status().name(), addition.effectiveNow(),
                addition.reason());
    }
}
