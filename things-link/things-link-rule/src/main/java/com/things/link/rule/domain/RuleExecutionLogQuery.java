package com.things.link.rule.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * 上行规则执行日志的只读查询条件。
 *
 * <p>列表按「执行级」聚合：一个 {@code (projectId, messageId, ruleId, ruleVersionId)} 是一行逻辑执行，
 * attempt 只在详情时间线展开（见 ADR 0030 与 S9-5 设计冻结）。本对象只是纯数据载体，状态/时间校验由应用服务
 * 在进入仓储前完成，与 telemetry 的 {@code MessageLogQuery} 同一约定。</p>
 *
 * @param projectId 项目隔离轴
 * @param ruleId 可选，按规则定义筛选
 * @param status 可选，按最新 attempt 的封闭终态筛选（SUCCESS / RETRY_SCHEDULED / DEAD_LETTER）
 * @param from 可选，最后尝试完成时刻下限（含）
 * @param to 可选，最后尝试完成时刻上限（不含）
 * @param cursor 可选，下一页游标
 * @param limit 每页数量
 */
public record RuleExecutionLogQuery(
        UUID projectId,
        UUID ruleId,
        String status,
        Instant from,
        Instant to,
        String cursor,
        int limit) {
}
