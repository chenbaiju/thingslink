package com.things.link.enduser.domain;

import java.util.UUID;

/** App四统计仓储，跨域只读取批准的最小公开视图。 */
public interface AppDeviceStatisticsRepository {
    /**
     * 聚合当前终端用户的有效设备绑定，不在客户端或内存展开全量设备。
     * @param tenantId 可信令牌租户
     * @param projectId 可信令牌项目
     * @param appUserId 可信终端用户
     * @return 四项计数及同一数据库时刻
     */
    AppDeviceStatistics read(UUID tenantId, UUID projectId, UUID appUserId);
}
