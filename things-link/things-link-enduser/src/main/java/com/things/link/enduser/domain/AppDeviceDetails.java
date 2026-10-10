package com.things.link.enduser.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 已授权设备的公共信息，不含控制、密钥或模型私有内容。
 * @param id 设备标识
 * @param deviceKey 项目内设备键
 * @param name 名称
 * @param description 描述
 * @param status 连接状态
 * @param location 位置
 * @param lastOnlineAt 最近在线时间
 * @param createdAt 创建时间
 * @param deviceTypeName 类型名称，可空
 * @param lastDataReportAt 最近有效上报时间，可空
 */
public record AppDeviceDetails(UUID id, String deviceKey, String name, String description,
        String status, String location, Instant lastOnlineAt, Instant createdAt,
        String deviceTypeName, Instant lastDataReportAt) { }
