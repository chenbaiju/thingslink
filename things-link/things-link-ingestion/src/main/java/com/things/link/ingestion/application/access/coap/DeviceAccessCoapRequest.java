package com.things.link.ingestion.application.access.coap;

/**
 * 设备面 CoAP 请求的传输无关视图（接入合同 §3.4）。
 *
 * <p>凭据走 CoAP 自定义选项 {@code TC-Device-Key}(65001) 与 {@code TC-Credential}(65002)，**不混入业务体**；
 * 传输层只负责把选项与载荷填进本记录，认证、预算与响应映射全部由
 * {@link DeviceAccessCoapAccessService} 决定。</p>
 *
 * @param resourcePath 资源路径，例如 {@code /device-access/v1/property/report}
 * @param contentFormat CoAP Content-Format；缺失时为空
 * @param body 请求体字节
 * @param deviceKeyOption {@code TC-Device-Key} 选项值（{@code projectKey/deviceKey}）；缺失时为空
 * @param credentialOption {@code TC-Credential} 选项值（明文密钥）；缺失时为空
 * @param clientIp 设备端 IP，用于认证面每 IP 预算
 */
public record DeviceAccessCoapRequest(String resourcePath, Integer contentFormat, byte[] body,
                                      String deviceKeyOption, String credentialOption, String clientIp) {
}
