package com.things.link.device.api.dto.response;

import com.things.link.device.application.ConsoleDeviceCatalogService;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

/** @param items 授权与精确模型过滤后的完整页 @param nextCursor 下一页或null @param hasMore 是否有后续页 */
public record DeviceRuntimeCatalogResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<Item> items,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) String nextCursor,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean hasMore) {
    /** @param source 应用层权威页 @return 不暴露排序时间或内部租户字段的响应 */
    public static DeviceRuntimeCatalogResponse from(ConsoleDeviceCatalogService.Page source) {
        return new DeviceRuntimeCatalogResponse(source.items().stream().map(item -> new Item(item.deviceId(),
                item.name(), item.deviceStatus(), item.currentModelVersionId())).toList(), source.nextCursor(), source.hasMore());
    }

    /** @param deviceId 设备身份 @param name 当前名称 @param deviceStatus 领域状态 @param currentModelVersionId 当前精确模型 */
    @Schema(name = "ConsoleDeviceCatalogItem")
    public record Item(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID deviceId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, allowableValues = {"INACTIVE", "ONLINE", "OFFLINE"}) String deviceStatus,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID currentModelVersionId) { }
}
