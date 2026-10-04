package com.things.link.device.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * 创建设备请求。
 *
 * @param deviceTypeId 设备类型 ID（可空） @param deviceKey 项目内唯一标识符 @param name 名称
 * @param description 说明 @param location 位置
 */
public record CreateDeviceRequest(
        UUID deviceTypeId,
        @NotBlank @Size(max = 64) @Pattern(regexp = "^[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}$") String deviceKey,
        @NotBlank @Size(max = 128) String name,
        @Size(max = 500) String description,
        @Size(max = 256) String location) { }
