package com.things.link.device.application;

import com.things.link.device.domain.DeviceStatisticsRepository;
import com.things.link.project.application.ProjectService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/** 向其他业务模块提供项目设备统计，保持 device 表所有权边界。 */
@Service
public class ProjectDeviceStatisticsService {
    /** 设备统计持久化端口。 */
    private final DeviceStatisticsRepository repository;
    /** 项目成员授权端口。 */
    private final ProjectService projectService;

    /**
     * 创建项目设备统计服务。
     *
     * @param repository 设备统计持久化端口
     * @param projectService 项目成员授权端口
     */
    public ProjectDeviceStatisticsService(DeviceStatisticsRepository repository, ProjectService projectService) {
        this.repository = repository;
        this.projectService = projectService;
    }

    /**
     * 读取一个项目在指定窗口起点后的设备统计。
     *
     * <p>本服务再次校验项目成员身份；即使调用方绕过概要 Controller，也不能把跨模块应用端口当成
     * 无授权查询入口。</p>
     *
     * @param projectId 项目 ID
     * @param since 活跃窗口起点（包含）
     * @return 设备统计
     */
    @Transactional(readOnly = true)
    public ProjectDeviceStatistics snapshot(UUID projectId, Instant since) {
        projectService.requireRoleInProject(projectId);
        var statistics = repository.summarize(projectId, since);
        return new ProjectDeviceStatistics(statistics.total(), statistics.online(), statistics.activeSince());
    }
    /** 统计编排使用的有界有效设备身份过滤，不向外暴露私有表。 */
    @Transactional(readOnly = true)
    public java.util.Set<UUID> existingIds(UUID projectId, java.util.List<UUID> ids) {
        projectService.requireRoleInProject(projectId);
        if (ids == null || ids.size() > 1000 || ids.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("设备统计批次必须在0..1000项内");
        }
        return repository.existingIds(projectId, java.util.List.copyOf(ids));
    }

}
