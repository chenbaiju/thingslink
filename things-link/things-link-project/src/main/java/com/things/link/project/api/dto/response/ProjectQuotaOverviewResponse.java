package com.things.link.project.api.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.things.link.project.domain.ProjectQuotaOverview;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.ZoneOffset;

/**
 * 项目设置页的只读配额与用量响应。
 *
 * <p>套餐归属始终是租户；{@code project} 只展示当前项目贡献，{@code tenantSharedPool}
 * 展示同一租户的总已用/剩余。两者并列是为了阻止前端把共享池误写成项目独享额度。
 *
 * <p>{@code planSummary} 是租户套餐摘要（S14-2c），只在调用者属于项目归属租户**且该租户存在处于
 * 活状态（{@code ACTIVE}/{@code GRACE}/{@code RESTRICTED_FREE}）的订阅**时出现：架构文档 §6 规定
 * 「租户成员只能读取本租户套餐摘要」，跨租户协作者在项目页只保留既有共享池投影。宽限与受限免费期
 * 仍属活状态（P4：读、历史与设备连接照常），因此摘要继续可读；字段省略表示「本读取面不提供套餐
 * 事实」，不是「没有套餐」或「额度为零」。
 *
 * @param projectId 当前已选项目 ID
 * @param policyCode 当前生效策略编码
 * @param policyVersion 当前策略单调版本
 * @param windowStart UTC 计量窗口起点（包含，RFC3339）
 * @param windowEnd UTC 计量窗口终点（不包含，RFC3339）
 * @param memberCount 当前项目有效成员数，不属于租户共享套餐指标
 * @param project 当前项目贡献
 * @param tenantSharedPool 所属租户共享池
 * @param planSummary 租户套餐摘要；调用者不是项目归属租户成员或该租户无活状态订阅时省略
 */
@Schema(description = "项目配额与 UTC 日用量概览")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProjectQuotaOverviewResponse(
        @Schema(description = "当前项目 ID") String projectId,
        @Schema(description = "当前配额策略编码", example = "FREE") String policyCode,
        @Schema(description = "策略单调版本", example = "1") long policyVersion,
        @Schema(description = "UTC 窗口起点（包含）") String windowStart,
        @Schema(description = "UTC 窗口终点（不包含）") String windowEnd,
        @Schema(description = "当前项目有效成员数", example = "3") long memberCount,
        @Schema(description = "当前项目贡献") ProjectQuotaScopeResponse project,
        @Schema(description = "所属租户共享池") ProjectQuotaScopeResponse tenantSharedPool,
        @Schema(description = "租户套餐摘要；仅项目归属租户成员可见", nullable = true)
        ProjectPlanSummaryResponse planSummary) {

    /**
     * 将领域概览转换为控制台契约。
     *
     * @param overview 已验证项目成员关系的领域概览
     * @return 不含租户 ID 或其他项目明细的 HTTP DTO
     */
    public static ProjectQuotaOverviewResponse from(ProjectQuotaOverview overview) {
        var projectDaily = overview.dailyMetrics().stream()
                .map(metric -> QuotaMetricUsageResponse.from(metric, metric.projectUsed()))
                .toList();
        var tenantDaily = overview.dailyMetrics().stream()
                .map(metric -> QuotaMetricUsageResponse.from(metric, metric.tenantUsed()))
                .toList();
        return new ProjectQuotaOverviewResponse(
                overview.projectId().toString(), overview.policyCode(), overview.policyVersion(),
                overview.usageDate().atStartOfDay(ZoneOffset.UTC).toInstant().toString(),
                overview.usageDate().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toString(),
                overview.memberCount(),
                new ProjectQuotaScopeResponse(
                        QuotaMetricUsageResponse.from(overview.deviceUsage(), overview.deviceUsage().projectUsed()),
                        projectDaily),
                new ProjectQuotaScopeResponse(
                        QuotaMetricUsageResponse.from(overview.deviceUsage(), overview.deviceUsage().tenantUsed()),
                        tenantDaily),
                overview.planSummary() == null
                        ? null : ProjectPlanSummaryResponse.from(overview.planSummary()));
    }
}
