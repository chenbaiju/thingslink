package com.things.link.assistant.application;

import com.things.link.alarm.application.ConsoleDeviceAlarmStatusService;
import com.things.link.device.application.ConsoleDeviceEvidenceService;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

/** 只读编排：各领域完成自己的授权与事务，最后再次复核当前资格。 */
@Service
public class DeviceEvidenceService {
    private final ConsoleDeviceEvidenceService devices;
    private final ConsoleDeviceAlarmStatusService alarms;
    private final Clock clock;
    public DeviceEvidenceService(ConsoleDeviceEvidenceService devices, ConsoleDeviceAlarmStatusService alarms, Clock clock) {
        this.devices = devices; this.alarms = alarms; this.clock = clock;
    }
    /**
     * 通过设备及告警领域端口读取证据，返回前再次检查权限及模型版本。
     * 各领域使用独立事务，采集时间区间不代表跨领域原子快照。
     * @param project 当前身份所选项目
     * @param device 所选项目内的目标设备标识
     * @param expected 调用方期望的不可变模型版本，变化时拒绝返回
     * @param keys 要读取的有界属性键列表，由设备领域校验
     * @return 带来源和采集时间的只读证据，不直接访问设备数据库或 MQTT
     */
    public DeviceEvidenceSnapshot read(UUID project, UUID device, UUID expected, List<String> keys) {
        var started = clock.instant();
        var evidence = devices.read(project, device, expected, keys);
        var alarm = alarms.read(project, List.of(device));
        if (alarm.devices().size() != 1 || !device.equals(alarm.devices().getFirst().deviceId()))
            throw new IllegalStateException("告警摘要设备不完整");
        var state = alarm.devices().getFirst();
        if (!expected.equals(state.modelVersionId())) throw new BusinessException(CommonErrorCode.RESOURCE_STATE_CONFLICT);
        devices.revalidate(project, device, expected);
        return new DeviceEvidenceSnapshot(1, project, device, expected, started, clock.instant(),
                new DeviceEvidenceSnapshot.Device(evidence.status(), evidence.lastOnlineAt(), evidence.readAt()),
                evidence.properties(), new DeviceEvidenceSnapshot.AlarmSummary(state.state(), alarm.observedAt()));
    }
}
