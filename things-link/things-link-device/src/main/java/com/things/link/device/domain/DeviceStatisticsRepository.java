package com.things.link.device.domain;

import java.time.Instant;
import java.util.UUID;

/** 设备概要统计持久化端口。 */
public interface DeviceStatisticsRepository {
    /** 仅返回本项目未删除设备，内部统计批次最多1000项，不依赖物模型绑定。 */
    java.util.Set<UUID> existingIds(UUID projectId, java.util.List<UUID> ids);

    /**
     * 在同一数据库快照内聚合项目设备统计。
     *
     * @param projectId 项目 ID
     * @param since 活跃窗口起点（包含）
     * @return 项目设备统计
     */
    DeviceStatistics summarize(UUID projectId, Instant since);
}
