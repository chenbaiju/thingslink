package com.things.link.rule.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 一次手动场景执行事实的只读投影。
 *
 * <p>场景执行同步在单事务内即达终态，故无需 attempt 聚合；一行执行事实即一行列表行。DISPATCHED 只表示副作用
 * 已可靠写入 Outbox，不代表已送达。</p>
 *
 * @param id 执行事实 ID
 * @param sceneId 被执行的场景定义 ID
 * @param sceneName 查询时刻的场景当前名称，仅用于展示，非执行时快照
 * @param sceneVersionId 首次执行绑定的不可变场景版本 ID
 * @param deviceId 目标设备 ID
 * @param status 执行终态
 * @param operatorAccountId 点击执行的控制台账号 ID
 * @param traceId 本次请求的全链路追踪 ID
 * @param occurredAt 服务端受理执行请求的 UTC 时刻
 * @param createdAt 执行事实落库的 UTC 时刻，兼作列表排序键
 * @param completedAt 进入终态的 UTC 时刻，等于创建时刻
 */
public record RuleSceneExecutionSummary(
        UUID id,
        UUID sceneId,
        String sceneName,
        UUID sceneVersionId,
        UUID deviceId,
        String status,
        UUID operatorAccountId,
        String traceId,
        Instant occurredAt,
        Instant createdAt,
        Instant completedAt) {
}
