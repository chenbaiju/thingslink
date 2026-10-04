package com.things.link.device.api.dto.request;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * Console稀疏PG当前值请求的OpenAPI形状；运行时由严格原文解析器建立领域值。
 * @param devices 1至20台设备且总属性组合不超过200
 */
public record DeviceCurrentValueSnapshotQueryRequest(
        @NotNull @ArraySchema(minItems = 1, maxItems = 20, uniqueItems = true,
                arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED))
        List<DeviceRequest> devices) {

    /** @param deviceId 设备 @param expectedModelVersionId 预期模型 @param propertyKeys 1至50个顶层属性 */
    @Schema(name = "ConsoleDeviceCurrentValueDeviceRequest")
    public record DeviceRequest(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID deviceId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID expectedModelVersionId,
            @NotNull @ArraySchema(minItems = 1, maxItems = 50, uniqueItems = true,
                    arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED))
            List<String> propertyKeys) { }
}
