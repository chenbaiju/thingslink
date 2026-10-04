package com.things.link.enduser.domain;

import java.time.Instant;
import java.util.UUID;

/** 当前设备授权的低敏读取投影，不包含登录标识或口令。 */
public record DeviceEndUserItem(UUID id, UUID appUserId, String displayName,
        String relationRole, Instant createdAt) { }
