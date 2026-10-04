package com.things.link.rule.application.queue;

import com.things.link.project.application.ProjectDailyQuotaDecisionService;
import com.things.link.project.application.QuotaMetric;
import com.things.link.project.application.QuotaStatus;
import org.springframework.stereotype.Component;

/** 以 project 应用端口检查规则执行次数与 CPU 毫秒两项租户共享日额度。 */
@Component
public class ProjectRuleQuotaGate implements RuleQuotaGate {

    /** PostgreSQL 权威 UTC 日额度决策服务。 */
    private final ProjectDailyQuotaDecisionService quotaDecisionService;

    /** @param quotaDecisionService 项目域公开的可信二元组决策服务 */
    public ProjectRuleQuotaGate(ProjectDailyQuotaDecisionService quotaDecisionService) {
        this.quotaDecisionService = quotaDecisionService;
    }

    /** {@inheritDoc} */
    @Override
    public boolean allows(RuleExecutionEnvelope envelope) {
        QuotaStatus executions = quotaDecisionService.decideTrustedProject(
                envelope.tenantId(), envelope.key().projectId(), QuotaMetric.SCRIPT_EXECUTION);
        QuotaStatus cpu = quotaDecisionService.decideTrustedProject(
                envelope.tenantId(), envelope.key().projectId(), QuotaMetric.SCRIPT_CPU_MILLIS);
        return allowed(executions) && allowed(cpu);
    }

    /** SOFT_LIMIT 仍可服务；HARD_LIMIT 与 DEGRADED 停止新增高成本规则执行。 */
    private static boolean allowed(QuotaStatus status) {
        return status == QuotaStatus.NORMAL || status == QuotaStatus.SOFT_LIMIT;
    }
}
