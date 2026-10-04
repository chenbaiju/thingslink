package com.things.link.device.application;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 指定设备在一次运行读取中的可用性投影。
 *
 * @param deviceId 请求设备ID
 * @param status 不泄露资源原因的可用性状态
 * @param currentModelVersionId 可见设备当前模型；未绑定时为空，不可见时必须为空
 * @param name 可用设备名称
 * @param deviceStatus 可用设备连接状态
 * @param lastOnlineAt 可用设备最近上线时刻
 */
public record RuntimeDeviceAvailability(
        UUID deviceId,
        Status status,
        UUID currentModelVersionId,
        String name,
        String deviceStatus,
        Instant lastOnlineAt) {

    /** 状态与可见字段必须精确对应，避免失败分支泄露名称或模型身份。 */
    public RuntimeDeviceAvailability {
        Objects.requireNonNull(deviceId, "deviceId");
        Objects.requireNonNull(status, "status");
        if (status == Status.NOT_AVAILABLE
                && (currentModelVersionId != null || name != null || deviceStatus != null || lastOnlineAt != null)) {
            throw new IllegalArgumentException("不可见设备不得携带事实字段");
        }
        if (status == Status.MODEL_MISMATCH && (name != null || deviceStatus != null || lastOnlineAt != null)) {
            throw new IllegalArgumentException("模型失配只允许返回当前模型身份");
        }
        if (status == Status.AVAILABLE
                && (currentModelVersionId == null || name == null || deviceStatus == null)) {
            throw new IllegalArgumentException("可用设备必须携带完整基础事实");
        }
    }

    /** 设备对指定精确模型的可用性。 */
    public enum Status {
        /** 设备不可见、未授权或已删除。 */ NOT_AVAILABLE,
        /** 可见设备当前模型与请求不一致。 */ MODEL_MISMATCH,
        /** 设备可见且当前模型精确匹配。 */ AVAILABLE
    }
}
