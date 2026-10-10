package com.things.link.enduser.api.dto.response;

import com.things.link.enduser.domain.AppDeviceDetails;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * App 设备响应。
 *
 * <p>从已授权公共设备投影转换，全部字段字符串化（ID、状态、时间），
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
 * @param deviceTypeName 当前可见类型名称，可空
 * @param lastDataReportAt 最近有效数据接收时刻，可空
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
        @Schema(description = "创建时刻（RFC3339 UTC）") String createdAt,
        @Schema(description = "当前设备类型名称，可空") String deviceTypeName,
        @Schema(description = "最近有效数据接收时刻（RFC3339 UTC），历史未知为空") String lastDataReportAt) {

    /** @param device 已授权公共设备投影 @return App 响应 */
    public static AppDeviceResponse from(AppDeviceDetails device) {
        return new AppDeviceResponse(
                device.id().toString(),
                device.deviceKey(),
                device.name(),
                device.description(),
                device.status(),
                device.location(),
                device.lastOnlineAt() == null ? null : device.lastOnlineAt().toString(),
                device.createdAt() == null ? null : device.createdAt().toString(),
                device.deviceTypeName(),
                device.lastDataReportAt() == null ? null : device.lastDataReportAt().toString());
    }
}
