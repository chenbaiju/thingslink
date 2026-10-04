package com.things.link.alarm.application;

import com.things.link.alarm.domain.AlarmInstanceRepository;
import com.things.link.project.application.ProjectService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** 向其他业务模块公开项目 ACTIVE 告警设备统计，避免跨模块读取 alarm_ 表。 */
@Service
public class ProjectAlarmStatisticsService {
    /** 告警实例事实查询端口。 */
    private final AlarmInstanceRepository repository;
    /** 项目成员授权端口。 */
    private final ProjectService projectService;
    private final com.things.link.device.application.ProjectDeviceStatisticsService devices;

    /**
     * @param repository 告警实例端口
     * @param projectService 项目成员授权端口
     */
    public ProjectAlarmStatisticsService(AlarmInstanceRepository repository, ProjectService projectService,
            com.things.link.device.application.ProjectDeviceStatisticsService devices) {
        this.repository = repository;
        this.projectService = projectService;
        this.devices = devices;
    }

    /**
     * 读取项目 ACTIVE 告警影响的去重设备数。
     *
     * <p>再次校验成员身份，使该跨模块端口不能绕过 HTTP 层成为项目枚举入口；RLS 仍作为
     * 数据库侧最终隔离边界。</p>
     *
     * @param projectId 项目 ID
     * @return PostgreSQL 事实聚合
     */
    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public ProjectAlarmStatistics snapshot(UUID projectId) {
        projectService.requireRoleInProject(projectId);
        var counts = new java.util.EnumMap<com.things.link.alarm.domain.AlarmRule.Severity, Long>(
                com.things.link.alarm.domain.AlarmRule.Severity.class);
        UUID after = null;
        while (true) {
            var batch = repository.activeDeviceSeverities(projectId, after, 1000);
            if (batch.isEmpty()) break;
            var existing = devices.existingIds(projectId,
                    batch.stream().map(AlarmInstanceRepository.DeviceSeverity::deviceId).toList());
            for (var item : batch) {
                if (existing.contains(item.deviceId())) counts.merge(item.severity(), 1L, Long::sum);
            }
            after = batch.getLast().deviceId();
            if (batch.size() < 1000) break;
        }
        return new ProjectAlarmStatistics(
                counts.getOrDefault(com.things.link.alarm.domain.AlarmRule.Severity.CRITICAL, 0L),
                counts.getOrDefault(com.things.link.alarm.domain.AlarmRule.Severity.MAJOR, 0L),
                counts.getOrDefault(com.things.link.alarm.domain.AlarmRule.Severity.MINOR, 0L),
                counts.getOrDefault(com.things.link.alarm.domain.AlarmRule.Severity.WARNING, 0L),
                counts.getOrDefault(com.things.link.alarm.domain.AlarmRule.Severity.INFO, 0L));
    }
}
