package com.things.link.rule.domain;

import com.things.link.shared.page.CursorPage;

import java.util.List;
import java.util.UUID;

/**
 * 上行规则执行日志的只读端口。
 *
 * <p>只读查询不写执行事实，与 S8-2C 的写路径（{@code rule_execution_log_append} 函数 + 持久回执）严格分离；
 * 仓储方法都在项目 RLS 范围内执行，天然隔离跨项目事实。</p>
 */
public interface RuleExecutionLogReadRepository {

    /** @return 执行级聚合摘要分页，按最后尝试完成时刻倒序 */
    CursorPage<RuleExecutionSummary> findSummaries(RuleExecutionLogQuery query);

    /** @return 指定逻辑执行的全部 attempt 时间线，按 attempt 升序 */
    List<RuleExecutionAttempt> findAttempts(UUID projectId, UUID messageId, UUID ruleId, UUID ruleVersionId);

    /** @return 当前项目内已产生过执行事实的规则选项，按名称升序 */
    List<RuleOption> findRuleOptions(UUID projectId);
}
