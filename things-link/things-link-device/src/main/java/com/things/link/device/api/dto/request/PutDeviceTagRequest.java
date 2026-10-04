package com.things.link.device.api.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 新增或覆盖一个设备键值标签。 */
public record PutDeviceTagRequest(
        @NotBlank @Pattern(regexp = "^[A-Za-z][A-Za-z0-9_-]{0,63}$") String key,
        @NotBlank @Size(max = 128) String value) {
}
