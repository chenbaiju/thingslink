package com.things.link.rule.api.dto.response;

import com.things.link.rule.domain.RuleSceneExecutionSummary;

import java.time.Instant;
import java.util.UUID;

/**
 * 手动场景执行事实响应。
 *
 * @param id 执行事实 ID
 * @param sceneId 被执行的场景定义 ID
 * @param sceneName 查询时刻的场景当前名称
 * @param sceneVersionId 首次执行绑定的不可变场景版本 ID
 * @param deviceId 目标设备 ID
 * @param status 执行终态
 * @param operatorAccountId 点击执行的控制台账号 ID
 * @param traceId 本次请求的全链路追踪 ID
 * @param occurredAt 服务端受理执行请求的 UTC 时刻
 * @param createdAt 执行事实落库的 UTC 时刻
 * @param completedAt 进入终态的 UTC 时刻
 */
public record RuleSceneExecutionResponse(
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

    /** @return 领域执行投影到 API 响应 */
    public static RuleSceneExecutionResponse from(RuleSceneExecutionSummary summary) {
        return new RuleSceneExecutionResponse(
                summary.id(), summary.sceneId(), summary.sceneName(), summary.sceneVersionId(),
                summary.deviceId(), summary.status(), summary.operatorAccountId(), summary.traceId(),
                summary.occurredAt(), summary.createdAt(), summary.completedAt());
    }
}
