package com.things.link.rule.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 手动场景执行事实的只读查询条件。
 *
 * <p>场景执行一行就是一次逻辑执行（无 attempt 聚合），列表即执行事实本身；状态/时间校验由应用服务在进入
 * 仓储前完成。与 {@link RuleExecutionLogQuery} 同为纯数据载体。</p>
 *
 * @param projectId 项目隔离轴
 * @param sceneId 可选，按场景定义筛选
 * @param status 可选，按执行终态筛选（DISPATCHED / SKIPPED / FAILED）
 * @param from 可选，执行落库时刻下限（含）
 * @param to 可选，执行落库时刻上限（不含）
 * @param cursor 可选，下一页游标
 * @param limit 每页数量
 */
public record RuleSceneExecutionQuery(
        UUID projectId,
        UUID sceneId,
        String status,
        Instant from,
        Instant to,
        String cursor,
        int limit) {
}
