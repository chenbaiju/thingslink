package com.things.link.project.api.dto.response;

import com.things.link.project.domain.QuotaMetricUsage;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 一个配额指标在项目贡献或租户共享池中的展示值。
 *
 * @param metric 指标编码
 * @param limit 租户共享套餐上限；null 表示未设上限，零表示明确禁用
 * @param used 此展示范围内已用值
 * @param remaining 共享池剩余量；未设上限时为 null
 * @param status 根据共享池用量得出的状态
 */
@Schema(description = "配额指标用量")
public record QuotaMetricUsageResponse(
        @Schema(description = "指标编码", example = "UPLINK_MESSAGE") String metric,
        @Schema(description = "租户共享上限；null 表示未设上限", nullable = true, example = "1000000") Long limit,
        @Schema(description = "此范围已用值", example = "120") long used,
        @Schema(description = "共享池剩余量；null 表示未设上限", nullable = true, example = "999880") Long remaining,
        @Schema(description = "共享池状态", example = "NORMAL") String status) {

    /**
     * 从领域投影转换为 HTTP DTO。
     *
     * @param usage 领域指标用量
     * @param used 要展示的项目贡献或共享池已用值
     * @return HTTP 响应指标
     */
    public static QuotaMetricUsageResponse from(QuotaMetricUsage usage, long used) {
        return new QuotaMetricUsageResponse(usage.metric().name(), usage.limit(), used,
                usage.remaining(), usage.status().name());
    }
}
