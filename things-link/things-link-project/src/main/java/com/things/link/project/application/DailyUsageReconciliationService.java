package com.things.link.project.application;

import com.things.link.project.domain.DailyUsageReconciliationRepository;
import com.things.link.support.tenant.TransactionLocalRlsScope;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 从各业务模块幂等事实归并今天与昨天 UTC 日用量的事务服务。
 *
 * <p>同时回看昨天用于吸收跨零点迟到写入；每次计算完整绝对值并以 {@code GREATEST} 保存，
 * 多实例租约重叠、扫描重试和 Kafka 重放都只能把账单单调推进一次。</p>
 */
@Service
public class DailyUsageReconciliationService {

    /** project 权威账单仓储。 */
    private final DailyUsageReconciliationRepository repository;
    /** 各业务模块公开的绝对事实贡献器。 */
    private final List<DailyUsageContributor> contributors;
    /** 在领取项目的当前事务连接上建立完整 RLS 范围。 */
    private final TransactionLocalRlsScope transactionLocalRlsScope;
    /** UTC 日期来源；测试固定时钟才能稳定覆盖跨日回看。 */
    private final Clock clock;

    /**
     * 生产构造器固定 UTC 时钟，计费日不得受 JVM 默认时区影响。
     *
     * @param repository project 权威账单仓储
     * @param contributors 业务模块贡献器集合
     * @param transactionLocalRlsScope 事务局部 RLS 完整范围组件
     */
    @Autowired
    public DailyUsageReconciliationService(DailyUsageReconciliationRepository repository,
                                            List<DailyUsageContributor> contributors,
                                            TransactionLocalRlsScope transactionLocalRlsScope) {
        this(repository, contributors, transactionLocalRlsScope, Clock.systemUTC());
    }

    /**
     * 测试构造器允许冻结 UTC 日期边界。
     *
     * @param repository project 权威账单仓储
     * @param contributors 业务模块贡献器集合
     * @param transactionLocalRlsScope 事务局部 RLS 完整范围组件
     * @param clock UTC 日期来源
     */
    DailyUsageReconciliationService(DailyUsageReconciliationRepository repository,
                                    List<DailyUsageContributor> contributors,
                                    TransactionLocalRlsScope transactionLocalRlsScope,
                                    Clock clock) {
        this.repository = repository;
        this.contributors = List.copyOf(contributors);
        this.transactionLocalRlsScope = transactionLocalRlsScope;
        this.clock = clock;
    }

    /**
     * 在一个项目事务内重算今天与昨天并释放领取租约。
     *
     * @param scope 受限函数领取的项目范围
     */
    @Transactional
    public void reconcile(DailyUsageScope scope) {
        // 领取函数返回同一项目事实的可信二元组；贡献器的首条 RLS SQL 前必须在本事务建立完整范围。
        transactionLocalRlsScope.establish(scope.tenantId(), scope.projectId());
        repository.lockScope(toDomainScope(scope));
        LocalDate today = LocalDate.now(clock);
        reconcileDate(scope, today.minusDays(1));
        reconcileDate(scope, today);
        repository.complete(toDomainScope(scope));
    }

    /** 合并同一指标的多个模块贡献，未来新增通知贡献器时不会相互覆盖。 */
    private void reconcileDate(DailyUsageScope scope, LocalDate usageDate) {
        Map<QuotaMetric, Long> totals = new EnumMap<>(QuotaMetric.class);
        for (DailyUsageContributor contributor : contributors) {
            List<DailyUsageValue> values = contributor.calculate(scope, usageDate);
            if (values == null) {
                throw new IllegalStateException("日用量贡献器不得返回 null");
            }
            for (DailyUsageValue value : values) {
                totals.merge(value.metric(), value.usedValue(), Math::addExact);
            }
        }
        // 零值无需制造账单行；已有绝对事实也不能因短暂查询空结果被回退。
        totals.forEach((metric, usedValue) -> {
            if (usedValue > 0) {
                repository.mergeAbsolute(toDomainScope(scope), usageDate,
                        new com.things.link.project.domain.DailyUsageValue(
                                com.things.link.project.domain.QuotaMetric.valueOf(metric.name()), usedValue));
            }
        });
    }

    /**
     * 将公开 application 范围收敛为 project 内部持久化范围。
     *
     * @param scope 贡献器公开范围
     * @return 仓储内部范围
     */
    private static com.things.link.project.domain.DailyUsageScope toDomainScope(DailyUsageScope scope) {
        return new com.things.link.project.domain.DailyUsageScope(scope.tenantId(), scope.projectId());
    }
}
