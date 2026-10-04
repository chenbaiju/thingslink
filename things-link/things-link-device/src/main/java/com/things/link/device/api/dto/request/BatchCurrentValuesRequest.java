package com.things.link.device.api.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * 批量当前值查询请求。
 *
 * @param deviceIds 设备 ID，最多 100 个
 * @param propertyKeys 属性键，最多 50 个
 */
public record BatchCurrentValuesRequest(
        @NotEmpty @Size(max = 100) List<@NotNull UUID> deviceIds,
        @NotEmpty @Size(max = 50) List<@NotNull @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String> propertyKeys) {
}
