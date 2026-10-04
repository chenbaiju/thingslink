package com.things.link.enduser.api.dto.response;

import com.things.link.device.application.AppDevice;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * App 设备响应。
 *
 * <p>从 device 模块的 {@code AppDevice} 投影转换，全部字段字符串化（ID、状态、时间），
 * 便于移动端消费与生成稳定的 OpenAPI 契约。
 *
 * @param id           设备 ID
 * @param deviceKey    设备标识
 * @param name         设备名称
 * @param description  描述
 * @param status       可达性状态
 * @param location     位置
 * @param lastOnlineAt 最近在线时刻
 * @param createdAt    创建时刻
 */
@Schema(description = "App 设备")
public record AppDeviceResponse(
        @Schema(description = "设备 ID") String id,
        @Schema(description = "设备标识") String deviceKey,
        @Schema(description = "设备名称") String name,
        @Schema(description = "设备描述") String description,
        @Schema(description = "可达性状态", example = "ONLINE") String status,
        @Schema(description = "位置") String location,
        @Schema(description = "最近在线时刻（RFC3339 UTC）") String lastOnlineAt,
        @Schema(description = "创建时刻（RFC3339 UTC）") String createdAt) {

    /** @param device device 模块数据面投影 @return App 响应 */
    public static AppDeviceResponse from(AppDevice device) {
        return new AppDeviceResponse(
                device.id().toString(),
                device.deviceKey(),
                device.name(),
                device.description(),
                device.status(),
                device.location(),
                device.lastOnlineAt() == null ? null : device.lastOnlineAt().toString(),
                device.createdAt() == null ? null : device.createdAt().toString());
    }
}
