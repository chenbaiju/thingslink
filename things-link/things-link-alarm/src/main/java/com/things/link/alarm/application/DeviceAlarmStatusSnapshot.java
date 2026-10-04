package com.things.link.alarm.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 设备列表告警事实，按请求顺序返回且不可部分成功。 */
public record DeviceAlarmStatusSnapshot(Instant observedAt, List<DeviceStatus> devices) {
    public record DeviceStatus(UUID deviceId, UUID modelVersionId, State state) { }
    public enum State { ACTIVE, NORMAL }
}
