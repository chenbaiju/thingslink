package com.things.link.project.domain;

import java.time.LocalDate;
import java.util.Optional;

/**
 * 运行时 UTC 日额度决策所需的最小 PostgreSQL 投影端口。
 */
public interface DailyQuotaDecisionRepository {

    /**
     * 读取受信项目所有者租户的单指标日用量，不接受 JWT tenant 推导或 Redis 近似值。
     *
     * @param scope 已确权的 owner tenant/project 二元组
     * @param usageDate UTC 计量日期
     * @param metric 日计量指标
     * @return 匹配 active 项目时的决策事实；归属不匹配时为空
     */
    Optional<DailyQuotaDecisionFact> find(DailyUsageScope scope, LocalDate usageDate, QuotaMetric metric);

    /**
     * @param limit 套餐日上限；{@code null} 表示不限
     * @param tenantUsed 租户全部项目日用量合计
     * @param softLimitBasisPoints 软限阈值基点
     * @param degradeBasisPoints 降级阈值基点
     */
    record DailyQuotaDecisionFact(Long limit, long tenantUsed,
                                  int softLimitBasisPoints, int degradeBasisPoints) {
    }
}
