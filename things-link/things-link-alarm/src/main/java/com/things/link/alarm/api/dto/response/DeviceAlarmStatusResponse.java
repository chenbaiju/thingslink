package com.things.link.alarm.api.dto.response;

import com.things.link.alarm.application.DeviceAlarmStatusSnapshot;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 最多20台设备的告警状态观测，不承诺实时推送。 */
public record DeviceAlarmStatusResponse(Instant observedAt, List<DeviceStatus> devices) {
    public record DeviceStatus(UUID deviceId, UUID modelVersionId, State state) { }
    public enum State { ACTIVE, NORMAL }
    public static DeviceAlarmStatusResponse from(DeviceAlarmStatusSnapshot snapshot) {
        return new DeviceAlarmStatusResponse(snapshot.observedAt(), snapshot.devices().stream().map(item ->
                new DeviceStatus(item.deviceId(), item.modelVersionId(), State.valueOf(item.state().name()))).toList());
    }
}
