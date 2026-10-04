package com.things.link.project.api.dto.response;

import com.things.link.project.domain.plan.PlanDimension;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 套餐的一个冻结数值维度（编码 + 数值 + 单位 + 窗口）。
 *
 * <p>{@code value} 是原始类型，JSON 里不可能是 {@code null}：冻结目录禁止用
 * {@code null} 表示「不限」。未交付能力不出现在本列表，而由权益的 {@code enabled=false} 表达。
 *
 * @param code 冻结维度编码，如 {@code PROJECTS_MAX}
 * @param value 冻结数值
 * @param unit 数值单位
 * @param window 计量窗口
 */
@Schema(description = "套餐冻结数值维度；数值恒为确定值，不使用 null 表示不限")
public record PlanQuotaDimensionResponse(
        @Schema(description = "冻结维度编码", example = "PROJECTS_MAX") String code,
        @Schema(description = "冻结数值", example = "5") long value,
        @Schema(description = "数值单位", example = "COUNT") String unit,
        @Schema(description = "计量窗口", example = "NONE") String window) {

    /**
     * 领域维度转换为 HTTP 契约。
     *
     * @param dimension 冻结维度
     * @return 目录维度响应
     */
    public static PlanQuotaDimensionResponse from(PlanDimension dimension) {
        return new PlanQuotaDimensionResponse(
                dimension.code(), dimension.value(), dimension.unit(), dimension.window());
    }
}
