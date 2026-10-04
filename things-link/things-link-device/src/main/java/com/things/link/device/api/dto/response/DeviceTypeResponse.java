package com.things.link.device.api.dto.response;

import com.things.link.device.domain.DeviceType;

import java.time.Instant;
import java.util.UUID;

/**
 * 设备类型响应。
 * @param id ID @param projectId 项目 ID @param typeKey 标识符 @param name 名称
 * @param deviceKind 设备分类 @param payloadProtocol 报文协议 @param networkType 通信方式
 * @param version 版本 @param status 状态 @param productKey 一型一密产品标识；没有生成凭据时为空
 * @param createdAt 创建时刻
 */
public record DeviceTypeResponse(UUID id, UUID projectId, String typeKey, String name,
                                 DeviceType.DeviceKind deviceKind, DeviceType.PayloadProtocol payloadProtocol,
                                 DeviceType.NetworkType networkType,
                                 int version, DeviceType.Status status, String productKey, Instant createdAt) {
    /** @param type 领域对象 @return API 响应 */
    public static DeviceTypeResponse from(DeviceType type) {
        return new DeviceTypeResponse(type.id(), type.projectId(), type.typeKey(), type.name(),
                type.deviceKind(), type.payloadProtocol(), type.networkType(), type.version(), type.status(), type.productKey(),
                type.createdAt());
    }
}
