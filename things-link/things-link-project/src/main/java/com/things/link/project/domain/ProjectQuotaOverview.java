package com.things.link.project.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 项目设置页的配额与用量只读投影。
 *
 * @param projectId 当前项目 ID
 * @param usageDate 计量 UTC 日期
 * @param policyCode 租户当前套餐策略编码
 * @param policyVersion 单调策略版本
 * @param deviceUsage 当前项目设备存量及租户共享设备总量
 * @param memberCount 当前项目有效成员数；成员没有租户共享套餐上限，故不伪装成配额指标
 * @param dailyMetrics UTC 日计量指标
 * @param planSummary 租户套餐摘要（S14-2c）；调用者不是项目归属租户成员或该租户没有 ACTIVE
 *        订阅时为 {@code null}，表示「本读取面不提供套餐事实」，不是「额度为零」
 */
public record ProjectQuotaOverview(
        UUID projectId,
        LocalDate usageDate,
        String policyCode,
        long policyVersion,
        QuotaMetricUsage deviceUsage,
        long memberCount,
        List<QuotaMetricUsage> dailyMetrics,
        TenantPlanSummary planSummary) {
}
