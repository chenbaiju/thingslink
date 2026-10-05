package com.things.link.telemetry.application;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 受权历史的中性投影；不包含设备名称、自由单位、仓储对象或出站许可。 */
@Schema(name = "AssistantHistoryEvidence", requiredProperties = {"projectId", "deviceId", "modelVersionId", "propertyKey",
        "requestedFrom", "requestedTo", "effectiveFrom", "effectiveTo", "retentionClipped", "requestedGranularity",
        "actualGranularity", "aggregation", "readAt", "state", "points"})
public record ConsoleHistoryEvidence(UUID projectId, UUID deviceId, UUID modelVersionId, String propertyKey,
        Instant requestedFrom, Instant requestedTo, Instant effectiveFrom, Instant effectiveTo,
        boolean retentionClipped, String requestedGranularity, String actualGranularity, String aggregation,
        Instant readAt, State state, @io.swagger.v3.oas.annotations.media.ArraySchema(maxItems = 2000) List<Point> points) {
    public ConsoleHistoryEvidence { points = List.copyOf(points); }
    @Override public String toString() { return "历史证据[" + state + ",点数=" + points.size() + "]"; }

    /** 空点与保留窗口外分别表明缺项，不提供正常状态断言。 */
    public enum State { HAS_POINTS, NO_POINTS, OUTSIDE_RETENTION }
    /** 历史模型点不沿当前模型重新解释；来源未知时不提供数值。 */
    public enum Source { CURRENT_MODEL, HISTORICAL_MODEL, SOURCE_UNKNOWN }
    /** 样本数使用十进制字符串；原平台双精度值不承诺任意精度。 */
    @Schema(name = "AssistantHistoryPoint", requiredProperties = {"at", "value", "sampleCount", "sourceModelVersionId", "source"})
    public record Point(Instant at, @Schema(types = {"number", "null"}) Double value,
            @Schema(pattern = "[1-9][0-9]{0,18}") String sampleCount,
            @Schema(types = {"string", "null"}, format = "uuid") UUID sourceModelVersionId, Source source) {
        @Override public String toString() { return "历史证据点[" + source + "]"; }
    }
}
