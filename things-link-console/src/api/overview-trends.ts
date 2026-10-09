import request from '@/utils/http'
import type { components } from '@/types/api/schema'

/** 小时统计只读合同；模拟样本不是业务或计费事实。 */
export type OverviewTrends = components['schemas']['OverviewTrendsResponse']
export type OverviewTrend = components['schemas']['OverviewTrendChart']
export type TrendDays = 1 | 3 | 7 | 15 | 30

export function fetchOverviewTrends(projectId: string, days: TrendDays) {
  return request.get<OverviewTrends>({
    url: `/api/v1/projects/${projectId}/overview/trends`,
    params: { days },
    showErrorMessage: false
  })
}
