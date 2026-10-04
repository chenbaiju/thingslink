import request from '@/utils/http'
import type { components } from '@/types/api/schema'

/**
 * 项目概要响应。
 *
 * 统计定义与字段由 OpenAPI 唯一约束；浏览器只展示服务端的 PostgreSQL 事实快照，不能把
 * 已分页的设备或消息列表重新聚合成“概要”。
 */
export type OverviewResponse = components['schemas']['OverviewResponse']

/**
 * 查询当前项目的运行概要。
 *
 * @param projectId 当前进入的项目 ID
 */
export function fetchProjectOverview(projectId: string) {
  return request.get<OverviewResponse>({
    url: `/api/v1/projects/${projectId}/overview`
  })
}
