package com.things.link.device.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 一型一密设备注册请求。
 *
 * @param projectKey 项目 MQTT 标识
 * @param productKey 产品注册 Topic 标识
 * @param productSecret 产品密钥明文
 * @param deviceKey 待创建的项目内设备标识
 */
public record DeviceRegistrationRequest(
        @NotBlank @Pattern(regexp = "^[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}$") String projectKey,
        @NotBlank @Pattern(regexp = "^[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}$") String productKey,
        @NotBlank @Size(min = 32, max = 512) String productSecret,
        @NotBlank @Pattern(regexp = "^[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}$") String deviceKey) {
}
