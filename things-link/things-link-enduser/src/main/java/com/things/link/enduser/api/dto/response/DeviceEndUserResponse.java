package com.things.link.enduser.api.dto.response;

import com.things.link.enduser.domain.DeviceEndUserItem;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/** 当前授权白名单：显示名可以为空，不回退为登录名。 */
public record DeviceEndUserResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID appUserId,
        String displayName,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                allowableValues = {"PRIMARY", "MEMBER", "READ_ONLY"}) String relationRole,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant createdAt) {
    public static DeviceEndUserResponse from(DeviceEndUserItem item) {
        return new DeviceEndUserResponse(item.id(), item.appUserId(), item.displayName(), item.relationRole(), item.createdAt());
    }
}
