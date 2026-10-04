package com.things.link.telemetry.application;

import java.util.UUID;

/** @param commandId 设备操作事实 ID @param accepted 是否成功受理 @param failureCode 永久拒绝码 */
public record RuleDeviceActionResult(UUID commandId, boolean accepted, String failureCode) {
}
