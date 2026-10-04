package com.things.link.alarm.application;

import com.things.link.alarm.domain.AlarmInstanceRepository;
import com.things.link.device.application.DeviceRuntimeDataService;
import com.things.link.project.application.ProjectService;
import com.things.link.shared.error.BusinessException;
import com.things.link.shared.error.CommonErrorCode;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 有界批量读取，不从告警分页缺席推断正常；设备验证与事故事实共享快照。 */
@Service
public class ConsoleDeviceAlarmStatusService {
    private final ProjectService projects;
    private final TransactionLocalRlsScope scope;
    private final DeviceRuntimeDataService devices;
    private final AlarmInstanceRepository alarms;
    private final Clock clock;

    public ConsoleDeviceAlarmStatusService(ProjectService projects, TransactionLocalRlsScope scope,
            DeviceRuntimeDataService devices, AlarmInstanceRepository alarms, Clock clock) {
        this.projects = projects;
        this.scope = scope;
        this.devices = devices;
        this.alarms = alarms;
        this.clock = clock;
    }

    /** 失败整批拒绝，避免已删除设备、无模型设备或失权被显示为无告警。 */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ, propagation = Propagation.REQUIRES_NEW)
    public DeviceAlarmStatusSnapshot read(UUID projectId, List<UUID> requested) {
        projects.requireRoleInProject(projectId);
        if (requested == null || requested.isEmpty() || requested.size() > 20
                || requested.stream().anyMatch(java.util.Objects::isNull)
                || requested.stream().distinct().count() != requested.size())
            throw new BusinessException(CommonErrorCode.INVALID_PARAMETER);
        var observedAt = clock.instant();
        scope.establish(projects.requireProjectTenant(projectId), projectId);
        var models = devices.validatedCurrentModelVersions(projectId, Set.copyOf(requested));
        if (!models.keySet().equals(Set.copyOf(requested)) || models.values().stream().anyMatch(java.util.Objects::isNull))
            throw new IllegalStateException("设备模型摘要不完整");
        var active = alarms.activeDeviceIds(projectId, requested);
        if (!models.keySet().containsAll(active)) throw new IllegalStateException("告警摘要越界");
        return new DeviceAlarmStatusSnapshot(observedAt, requested.stream().map(id ->
                new DeviceAlarmStatusSnapshot.DeviceStatus(id, models.get(id), active.contains(id)
                        ? DeviceAlarmStatusSnapshot.State.ACTIVE : DeviceAlarmStatusSnapshot.State.NORMAL)).toList());
    }
}
