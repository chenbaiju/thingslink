package com.things.link.telemetry.api.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import java.time.Instant;
import java.util.List;

/** 项目历史曲线响应；来源与空值语义必须保留到浏览器和导出文件。 */
public record OverviewTrendsResponse(
        @Schema(description = "已授权项目标识") java.util.UUID projectId,
        @Schema(description = "窗口起点，包含，UTC小时边界") Instant from,
        @Schema(description = "窗口终点，不包含，最近完整UTC小时") Instant to,
        @Schema(description = "每桶小时数，计数求和、设备状态取桶末快照") int stepHours,
        @Schema(description = "单一数据来源；模拟不代表真实业务或额度用量", allowableValues = {"OBSERVED", "SIMULATED", "NONE"}) String source,
        @Schema(description = "固定顺序的十二类统计图表") List<OverviewTrendChart> charts) {
    /** @param key 图表键 @param title 中文标题 @param unit 单位 @param aggregation 聚合口径 @param series 图例与数据 */
    public record OverviewTrendChart(String key, String title,
            @Schema(allowableValues = {"COUNT", "DEVICES", "BYTES"}) String unit,
            @Schema(allowableValues = {"SUM", "LAST"}) String aggregation,
            List<OverviewTrendSeries> series) { }
    /** @param key 指标键 @param label 中文图例 @param values 时间桶值；缺少任一小时样本为 null，明确零仍为零 */
    public record OverviewTrendSeries(String key, String label,
            @ArraySchema(arraySchema = @Schema(description = "按时间顺序的桶值；缺失样本为null，不推断为0"),
                    schema = @Schema(types = {"integer", "null"}, format = "int64")) List<Long> values) { }
}
