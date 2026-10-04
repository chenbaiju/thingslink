package com.things.link.device.application;

import java.time.Instant;
import java.util.UUID;

/**
 * App 数据面可见的设备投影。
 *
 * <p>从 domain {@link com.things.link.device.domain.Device} 映射而来，只暴露 App 客户端需要的
 * 稳定标量，不泄露物模型/设备类型内部结构。状态刻意字符串化（{@code Device.Status.name()}），
 * 这样 enduser 模块不必 import 本模块的 domain 枚举即可消费。
 *
 * @param id           设备 ID
 * @param deviceKey    设备标识
 * @param name         设备名称
 * @param description  描述
 * @param status       可达性状态（INACTIVE/ONLINE/OFFLINE）
 * @param location     位置
 * @param lastOnlineAt 最近在线时刻
 * @param createdAt    创建时刻
 */
public record AppDevice(UUID id, String deviceKey, String name, String description,
                        String status, String location, Instant lastOnlineAt, Instant createdAt) {

    /** @param device 领域对象 @return App 数据面投影 */
    public static AppDevice from(com.things.link.device.domain.Device device) {
        return new AppDevice(device.id(), device.deviceKey(), device.name(), device.description(),
                device.status().name(), device.location(), device.lastOnlineAt(), device.createdAt());
    }
}
