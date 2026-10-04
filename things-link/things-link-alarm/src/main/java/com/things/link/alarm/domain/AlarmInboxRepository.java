package com.things.link.alarm.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** ADR0093：账号事件回执仓储，所有账号范围均来自已认证应用服务。 */
public interface AlarmInboxRepository {
    /** @return 数据库实际时刻；每个服务请求只取一次，不采用客户端时钟 */
    Instant databaseTime();

    /**
     * 查询固定窗口并多探测一行以决定后页；所有事件均带当前账号的read标志。
     * @param tenantId 项目真实归属租户
     * @param projectId 已授权项目
     * @param accountId 认证账号
     * @param windowStart 窗口起点，含边界
     * @param windowEnd 窗口终点，含边界
     * @param positionTime 上页末行时间，首页为空
     * @param positionId 上页末行ID，首页为空
     * @param fetchLimit 最多获取行数，应用层传入limit加一
     * @return 按received_at和id倒序的有限结果
     */
    List<AlarmInboxItem> page(UUID tenantId, UUID projectId, UUID accountId, Instant windowStart, Instant windowEnd,
                             Instant positionTime, UUID positionId, int fetchLimit);

    /**
     * @param tenantId 项目真实归属租户
     * @param projectId 已授权项目
     * @param accountId 认证账号
     * @param windowStart 当前请求窗口起点
     * @param windowEnd 当前请求数据库时刻
     * @return 最多探测100个未读事件，100供客户端显示99+
     */
    int countUnread(UUID tenantId, UUID projectId, UUID accountId, Instant windowStart, Instant windowEnd);

    /**
     * @param tenantId 项目真实归属租户
     * @param projectId 已授权项目
     * @param eventIds 已去重且最多100个事件身份
     * @return 项目内ACTIVATED事件；不加时间条件，以区分不可见与过期
     */
    List<AlarmInboxEvent> findActivatedEvents(UUID tenantId, UUID projectId, List<UUID> eventIds);

    /**
     * @param tenantId 项目的持久归属租户，不是协作者自己的租户
     * @param projectId 已持有ACTIVE共享许可的项目
     * @param accountId 认证账号
     * @param eventIds 已经整批核对的明确事件身份
     * @param readAt 当前请求的数据库实际时刻
     * @return 当次真正新插入的回执数，重复身份不更新既有事实
     */
    int insertReads(UUID tenantId, UUID projectId, UUID accountId, List<UUID> eventIds, Instant readAt);
}
