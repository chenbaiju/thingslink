package com.things.link.telemetry.api.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/** 当前有效历史窗口及键集页；无总数、无跨事务快照保证。 */
public record DeviceEventPageResponse(
        @io.swagger.v3.oas.annotations.media.ArraySchema(maxItems = 100,
                arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED)) List<DeviceEventResponse> items,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, types = {"string", "null"}, description = "下一页游标；末页为null") String nextCursor,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant windowFrom,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant windowTo,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"90"}) int retentionDays) {
    /** 返回后页内容不可变。 */
    public DeviceEventPageResponse { items = List.copyOf(items); }
}
