package com.things.link.enduser.api.dto.request;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * 控制台签发 CLAIM 令牌请求。
 *
 * @param deviceId 当前项目内待认领设备 ID
 */
public record IssueDeviceClaimTokenRequest(@NotNull UUID deviceId) {
}
