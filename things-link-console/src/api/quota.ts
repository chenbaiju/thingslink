import request from '@/utils/http'
import type { components } from '@/types/api/schema'

/**
 * 当前项目的配额与日用量概览。
 *
 * 用量事实由服务端从 PostgreSQL 聚合；前端只展示“当前项目贡献”与“租户共享池”，
 * 不能把共享池余额误读为当前项目独享额度。
 *
 * 同一响应还携带 `planSummary`（S14-2c），但它只在调用者属于项目归属租户时出现：
 * 架构文档 §6 规定租户成员才能读本租户套餐摘要，跨租户协作者只保留既有共享池投影。
 * 字段缺省表示“本读取面不提供套餐事实”，不是“没有套餐”或“额度为零”。
 *
 * S14-4c 起 `planSummary` 另带两个可选字段：`effectiveQuotaDimensions`（运行时真正生效的额度，
 * 用运行时单位表达，例如对象存储报 BYTE）与 `additions`（购买包/运营调整的溯源行，不含操作人账号）；
 * 二者缺省同样表示“本读取面没有运行时额度投影”，而不是“额度为零”。
 */
export type ProjectQuotaOverviewResponse = components['schemas']['ProjectQuotaOverviewResponse']

/** 项目贡献或租户共享池的一组配额指标。 */
export type ProjectQuotaScopeResponse = components['schemas']['ProjectQuotaScopeResponse']

/** 单个配额指标在当前 UTC 日窗口内的使用情况。 */
export type QuotaMetricUsageResponse = components['schemas']['QuotaMetricUsageResponse']

/**
 * 租户套餐摘要：订阅锁定的档位、服务期、冻结额度与功能权益。
 *
 * S14-4c 起另含 `effectiveQuotaDimensions`（运行时生效额度，运行时单位）与 `additions`
 * （购买包/人工调整溯源，不含操作人账号）；缺省表示“没有运行时额度投影”，不是零额度。
 */
export type ProjectPlanSummaryResponse = components['schemas']['ProjectPlanSummaryResponse']

/** 扩容或人工调整溯源行；`reason` 仅人工调整携带，操作人账号刻意不下发。 */
export type PlanQuotaAdditionResponse = components['schemas']['PlanQuotaAdditionResponse']

/** 套餐最小身份投影；不含价格、额度或权益。 */
export type PlanIdentityResponse = components['schemas']['PlanIdentityResponse']

/**
 * 查询当前项目的配额策略、日用量与（同租户成员可见的）套餐摘要。
 *
 * @param projectId 当前进入的项目 ID；服务端按项目成员关系授权，并只暴露该项目的贡献值。
 */
export function fetchProjectQuota(projectId: string) {
  return request.get<ProjectQuotaOverviewResponse>({
    url: `/api/v1/projects/${projectId}/quota`
  })
}
