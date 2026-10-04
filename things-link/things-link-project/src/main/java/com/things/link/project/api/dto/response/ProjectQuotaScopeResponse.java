package com.things.link.project.api.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 项目贡献或租户共享池的一组配额指标。
 *
 * @param deviceCount 设备存量指标
 * @param dailyMetrics UTC 日窗口指标
 */
@Schema(description = "配额用量范围")
public record ProjectQuotaScopeResponse(
        @Schema(description = "设备存量指标") QuotaMetricUsageResponse deviceCount,
        @Schema(description = "UTC 日窗口指标") List<QuotaMetricUsageResponse> dailyMetrics) {
}
