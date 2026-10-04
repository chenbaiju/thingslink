package com.things.link.ingestion.application.access.coap;

import com.things.link.shared.message.AuthenticatedDeviceIdentity;

import java.util.Optional;

/**
 * 已认证 CoAP 请求的业务处理端口（AX-4b／4c 接线）。
 *
 * <p>本端口只接收**已完成认证与预算扣减**的请求：协议层负责"能不能进来"，实现方只负责"进来之后干什么"。
 * 没有实现方认领某个资源时，协议层回 {@code 5.01 Not Implemented}——资源已冻结但业务未接线，绝不伪造成功。</p>
 */
public interface DeviceAccessCoapResourceHandler {

    /**
     * 处理一次已认证的设备请求。
     *
     * @param identity 已认证设备身份
     * @param resourcePath 冻结资源路径
     * @param body 请求体字节
     * @return 响应；不认领该资源时为空
     */
    Optional<DeviceAccessCoapResponse> handle(AuthenticatedDeviceIdentity identity, String resourcePath,
                                              byte[] body);
}
