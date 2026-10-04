package com.things.link.project.application;

import com.things.link.project.domain.DailyQuotaDecisionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 为数据面、任务和通知入口提供 PostgreSQL 权威 UTC 日额度决策。
 *
 * <p>本服务不缓存 Redis 计数，也不接受未经确权的单独 tenantId。调用方必须传入同一业务事实
 * 派生的 owner tenant/project 二元组；不匹配时 fail-closed。</p>
 */
@Service
public class ProjectDailyQuotaDecisionService {

    /** 受限日额度投影仓储。 */
    private final DailyQuotaDecisionRepository repository;
    private final DeploymentEntitlementPolicy entitlementPolicy;
    /** UTC 日期来源。 */
    private final Clock clock;

    /**
     * 生产构造器固定 UTC 计费日。
     *
     * @param repository 受限日额度投影仓储
     */
    @Autowired
    public ProjectDailyQuotaDecisionService(DailyQuotaDecisionRepository repository,
            DeploymentEntitlementPolicy entitlementPolicy) {
        this(repository, entitlementPolicy, Clock.systemUTC());
    }

    /**
     * 测试构造器允许冻结 UTC 日边界。
     *
     * @param repository 受限日额度投影仓储
     * @param clock UTC 日期来源
     */
    ProjectDailyQuotaDecisionService(DailyQuotaDecisionRepository repository, Clock clock) {
        this(repository, DeploymentEntitlementPolicy.commercial(), clock);
    }

    ProjectDailyQuotaDecisionService(DailyQuotaDecisionRepository repository,
            DeploymentEntitlementPolicy entitlementPolicy, Clock clock) {
        this.repository = repository;
        this.entitlementPolicy = entitlementPolicy;
        this.clock = clock;
    }

    /**
     * 返回当前 UTC 日的租户共享额度等级。
     *
     * @param tenantId 已确权项目所有者租户 ID
     * @param projectId 已确权项目 ID
     * @param metric 要判定的日计量指标
     * @return NORMAL、SOFT_LIMIT、HARD_LIMIT 或 DEGRADED
     */
    @Transactional(readOnly = true)
    public QuotaStatus decideTrustedProject(UUID tenantId, UUID projectId, QuotaMetric metric) {
        return decisionTrustedProject(tenantId, projectId, metric).status();
    }

    /** Trusted consumers that must distinguish explicit zero from an ordinary soft hard-limit threshold. */
    @Transactional(readOnly = true)
    public Decision decisionTrustedProject(UUID tenantId, UUID projectId, QuotaMetric metric) {
        if (metric == null || !metric.dailyCounter()) {
            throw new IllegalArgumentException("运行时日额度决策只支持 UTC 日计量指标");
        }
        com.things.link.project.domain.QuotaMetric domainMetric =
                com.things.link.project.domain.QuotaMetric.valueOf(metric.name());
        DailyQuotaDecisionRepository.DailyQuotaDecisionFact fact = repository.find(
                        new com.things.link.project.domain.DailyUsageScope(tenantId, projectId),
                        LocalDate.now(clock), domainMetric)
                .orElseThrow(() -> new IllegalArgumentException("项目与租户归属不匹配或项目不可用"));
        Long limit = entitlementPolicy.nonCommercial()
                ? Long.valueOf(entitlementPolicy.dailyLimit(domainMetric)) : fact.limit();
        com.things.link.project.domain.QuotaMetricUsage.Status status =
                new com.things.link.project.domain.QuotaMetricUsage(
                        domainMetric, limit, fact.tenantUsed(), fact.tenantUsed(),
                        fact.softLimitBasisPoints(), fact.degradeBasisPoints()).status();
        return new Decision(QuotaStatus.valueOf(status.name()), Long.valueOf(0).equals(limit));
    }
    public record Decision(QuotaStatus status, boolean disabled) {}
}
