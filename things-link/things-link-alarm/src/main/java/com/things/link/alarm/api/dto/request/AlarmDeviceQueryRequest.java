package com.things.link.alarm.api.dto.request;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * 数据运行合同§3.5/3.6封闭告警筛选信封；原始JSON解析器拒绝未知、重复、null及宽松类型。
 * @param devices 不同设备及当前模型预期
 * @param conditionStates 条件状态闭集
 * @param ackStates 确认状态闭集
 * @param severities 严重程度闭集
 * @param cursor 可省略签名游标，不允许显式null或空串
 * @param limit 可省略页大小，默认20
 */
@Schema(name = "AlarmDeviceQueryRequest")
public record AlarmDeviceQueryRequest(
        @NotNull @ArraySchema(minItems = 1, maxItems = 20, uniqueItems = true) List<DeviceRequest> devices,
        @NotNull @ArraySchema(minItems = 1, maxItems = 3, uniqueItems = true,
                schema = @Schema(allowableValues = {"PENDING", "ACTIVE", "CLEARED"})) List<String> conditionStates,
        @NotNull @ArraySchema(minItems = 1, maxItems = 2, uniqueItems = true,
                schema = @Schema(allowableValues = {"UNACKNOWLEDGED", "ACKNOWLEDGED"})) List<String> ackStates,
        @NotNull @ArraySchema(minItems = 1, maxItems = 5, uniqueItems = true,
                schema = @Schema(allowableValues = {"CRITICAL", "MAJOR", "MINOR", "WARNING", "INFO"})) List<String> severities,
        @Schema(maxLength = 2048, minLength = 1) String cursor,
        @Schema(minimum = "1", maximum = "50", defaultValue = "20") Integer limit) {
    /**
     * 每个deviceId只能出现一次，不以请求模型存在性推导设备权限。
     * @param deviceId 指定设备UUID
     * @param expectedModelVersionId 当前模型预期UUID
     */
    @Schema(name = "AlarmDeviceQueryDeviceRequest")
    public record DeviceRequest(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID deviceId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID expectedModelVersionId) {
    }
}
