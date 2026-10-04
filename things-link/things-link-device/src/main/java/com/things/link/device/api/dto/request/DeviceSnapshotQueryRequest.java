package com.things.link.device.api.dto.request;

import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * Console设备与精确模型描述快照请求的OpenAPI形状；运行时由严格原文解析器建立领域值。
 * @param models 最多20个不重复模型身份
 * @param devices 1至20个不重复设备请求
 */
public record DeviceSnapshotQueryRequest(
        @NotNull @ArraySchema(minItems = 0, maxItems = 20, uniqueItems = true,
                arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED))
        List<ModelReference> models,
        @NotNull @ArraySchema(minItems = 1, maxItems = 20, uniqueItems = true,
                arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED))
        List<DeviceRequest> devices) {

    /** @param versionId 模型版本 @param digestAlgorithm 摘要算法 @param digest 摘要 @param profile Profile */
    @Schema(name = "ConsoleDeviceSnapshotModelReference")
    public record ModelReference(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID versionId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                    allowableValues = "PG_JSONB_TEXT_V1_SHA256") String digestAlgorithm,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "^[0-9a-f]{64}$") String digest,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                    allowableValues = "TC_PROPERTY_COMPOSITE_V1") String profile) { }

    /** @param deviceId 设备 @param expectedModelVersionId 预期模型 @param propertyKeys 0至50个顶层属性 */
    @Schema(name = "ConsoleDeviceSnapshotDeviceRequest")
    public record DeviceRequest(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID deviceId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID expectedModelVersionId,
            @NotNull @ArraySchema(minItems = 0, maxItems = 50, uniqueItems = true,
                    arraySchema = @Schema(requiredMode = Schema.RequiredMode.REQUIRED))
            List<String> propertyKeys) { }
}
