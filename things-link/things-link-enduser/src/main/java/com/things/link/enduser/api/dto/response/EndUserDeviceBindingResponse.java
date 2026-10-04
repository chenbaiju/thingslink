package com.things.link.enduser.api.dto.response;

import com.things.link.enduser.domain.AppUserDevice;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 终端用户设备绑定响应（设备绑定概览）。
 *
 * <p>只陈述 {@code app_user_device} 的授权事实，<b>不返回设备名</b>——设备名属 device 模块，
 * S11-2b 的 App 设备数据面再拼。S11-3 之前该表由绑定流程填充，此响应为空属正常。
 *
 * @param deviceId     设备 ID
 * @param relationRole 设备关系角色
 * @param status       关系状态
 * @param createdAt    建立关系时刻
 */
@Schema(description = "终端用户设备绑定")
public record EndUserDeviceBindingResponse(
        @Schema(description = "设备 ID") String deviceId,
        @Schema(description = "设备关系角色", example = "PRIMARY") String relationRole,
        @Schema(description = "关系状态", example = "ACTIVE") String status,
        @Schema(description = "建立关系时刻") String createdAt) {

    /** 由设备授权关系转换。 */
    public static EndUserDeviceBindingResponse from(AppUserDevice device) {
        return new EndUserDeviceBindingResponse(
                device.deviceId().toString(),
                device.relationRole().name(),
                device.status().name(),
                device.createdAt().toString());
    }
}
