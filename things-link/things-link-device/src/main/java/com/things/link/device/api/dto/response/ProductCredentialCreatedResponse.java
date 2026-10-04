package com.things.link.device.api.dto.response;

import com.things.link.device.application.DeviceTypeService;

import java.util.UUID;

/**
 * 一型一密产品凭据创建响应；productSecret 只在生成或轮换时返回一次。
 *
 * @param deviceTypeId 设备类型 ID
 * @param productKey MQTT 注册 Topic 的产品段
 * @param productSecret 一次可见的产品密钥明文
 */
public record ProductCredentialCreatedResponse(UUID deviceTypeId, String productKey, String productSecret) {

    /** @param credential 应用服务结果 @return HTTP 响应 */
    public static ProductCredentialCreatedResponse from(DeviceTypeService.ProductCredential credential) {
        return new ProductCredentialCreatedResponse(
                credential.deviceTypeId(), credential.productKey(), credential.productSecret());
    }
}
