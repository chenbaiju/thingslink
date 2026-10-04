package com.things.link.rule.application;

import java.time.Instant;
import java.util.UUID;

/**
 * application 层执行事实投影；同一幂等键重试返回相同内容。
 *
 * @param id 执行事实 ID，兼作规则消息 messageId
 * @param sceneId 场景定义 ID
 * @param sceneVersionId 冻结场景版本 ID
 * @param idempotencyKey 客户端幂等键
 * @param deviceId 目标设备 ID
 * @param status DISPATCHED、SKIPPED 或 FAILED
 * @param trigger 触发类型，固定 MANUAL
 * @param operatorAccountId 执行账号
 * @param traceId 全链路追踪 ID
 * @param occurredAt 服务端受理时刻
 * @param createdAt 执行事实落库时刻
 */
public record RuleSceneExecutionView(
        UUID id,
        UUID sceneId,
        UUID sceneVersionId,
        String idempotencyKey,
        UUID deviceId,
        String status,
        String trigger,
        UUID operatorAccountId,
        String traceId,
        Instant occurredAt,
        Instant createdAt) {
}
