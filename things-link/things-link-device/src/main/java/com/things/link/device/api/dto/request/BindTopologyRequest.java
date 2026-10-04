package com.things.link.device.api.dto.request;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * 绑定子设备到网关的请求。
 *
 * @param subDeviceId 子设备 ID @param gatewayId 网关 ID
 */
public record BindTopologyRequest(@NotNull UUID subDeviceId, @NotNull UUID gatewayId) { }
