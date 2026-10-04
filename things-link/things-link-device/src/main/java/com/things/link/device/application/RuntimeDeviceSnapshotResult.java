package com.things.link.device.application;

import java.util.List;
import java.util.Objects;

/**
 * 设备基础事实与所需不可变模型描述的一次运行快照。
 *
 * @param devices 按请求顺序的设备状态
 * @param models 仅成功设备实际需要的模型描述
 */
public record RuntimeDeviceSnapshotResult(
        List<RuntimeDeviceAvailability> devices,
        List<RuntimeModelDescription> models) {

    /** 复制两个有序集合，禁止HTTP编排层改写服务端判定。 */
    public RuntimeDeviceSnapshotResult {
        devices = List.copyOf(Objects.requireNonNull(devices, "devices"));
        models = List.copyOf(Objects.requireNonNull(models, "models"));
    }
}
