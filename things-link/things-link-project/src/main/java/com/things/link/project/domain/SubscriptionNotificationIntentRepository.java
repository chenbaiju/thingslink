package com.things.link.project.domain;

import java.util.Optional;
import java.util.UUID;

/**
 * 订阅到期通知意图的持久化端口（S14-3c；P4 五个时间点「同一时间点只发一次」）。
 *
 * <p>本端口只有一条写入口：{@code insertIfAbsent}。幂等仲裁者是数据库唯一键
 * {@code (tenant_id, kind, period_ends_at)}，不是应用层的「先查后插」——worker 重跑、
 * 进程重启或多实例并发都只能落一条。真实发送未交付（邮件只写日志），因此持久化的意图行
 * 就是「应发」这一事实本身。
 */
public interface SubscriptionNotificationIntentRepository {

    /**
     * 幂等地写入一条通知意图。
     *
     * @param tenantId 归属租户
     * @param subscriptionId 触发该时间点的订阅行
     * @param kind P4 时间点
     * @param periodEndsAt 该通知所属服务期终点（订阅 {@code ends_at}）
     * @param fireAt 应发时刻
     * @return 本次确实插入了新意图时返回其 ID；同一时间点已存在时返回空
     */
    Optional<UUID> insertIfAbsent(UUID tenantId, UUID subscriptionId, SubscriptionNotificationKind kind,
                                  java.time.Instant periodEndsAt, java.time.Instant fireAt);
}
