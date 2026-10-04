package com.things.link.device.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * 修改设备基础信息请求。device_key 创建后不可变更。
 *
 * @param deviceTypeId 设备类型 ID（可空） @param name 名称 @param description 说明 @param location 位置
 */
public record UpdateDeviceRequest(
        UUID deviceTypeId,
        @NotBlank @Size(max = 128) String name,
        @Size(max = 500) String description,
        @Size(max = 256) String location) { }
