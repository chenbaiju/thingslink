package com.things.link.enduser.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * App运行目录的一条已授权设备投影。
 *
 * @param deviceId 设备ID
 * @param name 设备名称
 * @param deviceStatus 设备状态
 * @param currentModelVersionId 当前模型版本ID
 * @param createdAt 目录稳定倒序分页时刻
 */
public record AppRuntimeDeviceCatalogItem(
        UUID deviceId, String name, String deviceStatus, UUID currentModelVersionId, Instant createdAt) {

    /** 拒绝视图或授权联查返回缺失的目录事实。 */
    public AppRuntimeDeviceCatalogItem {
        Objects.requireNonNull(deviceId, "deviceId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(deviceStatus, "deviceStatus");
        Objects.requireNonNull(currentModelVersionId, "currentModelVersionId");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
