package com.things.link.device.api.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/** 静态组成员整集替换请求。 */
public record ReplaceDeviceGroupMembersRequest(
        @NotNull @Size(max = 1000) List<@NotNull UUID> deviceIds) {
}
