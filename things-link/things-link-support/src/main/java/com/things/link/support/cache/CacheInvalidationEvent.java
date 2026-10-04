package com.things.link.support.cache;

import java.time.Instant;
import java.util.UUID;

/**
 * 三类缓存共用的 Redis 失效事件 v1。
 *
 * @param eventId UUIDv7 事件 ID，仅用于日志去重诊断
 * @param resource 固定资源类型，同时确定缓存安全等级
 * @param operation 固定失效操作
 * @param resourceId 定向失效的业务资源 ID；允许进入事件和日志，禁止进入指标标签
 * @param relatedId 可选关联资源 ID，例如租户绑定的策略模板 ID
 * @param version 主资源单调版本；无版本派生投影使用事实版本或零
 * @param relatedVersion 可选关联版本，例如策略模板版本
 * @param occurredAt 事实事务提交前生成的 UTC 时刻
 */
public record CacheInvalidationEvent(
        UUID eventId,
        CacheResource resource,
        CacheInvalidationOperation operation,
        UUID resourceId,
        UUID relatedId,
        long version,
        long relatedVersion,
        Instant occurredAt) {

    /** 拒绝损坏消息进入处理器，避免一个非法事件杀死 Redis 监听线程。 */
    public CacheInvalidationEvent {
        if (eventId == null || resource == null || operation == null || resourceId == null || occurredAt == null
                || version < 0 || relatedVersion < 0) {
            throw new IllegalArgumentException("缓存失效事件字段不完整");
        }
    }
}
