import request from '@/utils/http'
import type { components } from '@/types/api/schema'

/** 上行规则执行级聚合摘要；状态取自最新 attempt 的封闭终态。 */
export type RuleExecutionSummaryResponse = components['schemas']['RuleExecutionSummaryResponse']
/** 上行规则执行级聚合列表游标页。 */
export type RuleExecutionSummaryPage =
  components['schemas']['CursorPageRuleExecutionSummaryResponse']
/** 一次逻辑执行内单次 attempt 的时间线节点。 */
export type RuleExecutionAttemptResponse = components['schemas']['RuleExecutionAttemptResponse']
/** 执行记录筛选下拉框的只读选项。 */
export type RuleOptionResponse = components['schemas']['RuleOptionResponse']
/** 手动场景执行事实；一行即一次逻辑执行。 */
export type RuleSceneExecutionResponse = components['schemas']['RuleSceneExecutionResponse']
/** 场景执行事实游标页。 */
export type RuleSceneExecutionPage = components['schemas']['CursorPageRuleSceneExecutionResponse']
/** 场景执行详情：执行事实 + 通知与设备动作投递摘要。 */
export type RuleSceneExecutionDetailResponse =
  components['schemas']['RuleSceneExecutionDetailResponse']
/** 场景执行详情里的通知投递摘要。 */
export type NotificationDeliverySummaryResponse =
  components['schemas']['NotificationDeliverySummaryResponse']
/** 场景执行详情里的设备动作投递摘要。 */
export type DeviceActionDeliverySummaryResponse =
  components['schemas']['DeviceActionDeliverySummaryResponse']

/** 上行规则执行列表筛选参数；from/to 为 RFC3339 UTC。 */
export interface RuleExecutionListParams {
  ruleId?: string
  status?: string
  from?: string
  to?: string
  cursor?: string
  limit?: number
}

/** 场景执行列表筛选参数；from/to 为 RFC3339 UTC。 */
export interface SceneExecutionListParams {
  sceneId?: string
  status?: string
  from?: string
  to?: string
  cursor?: string
  limit?: number
}

/** 分页读取当前项目的上行规则执行级聚合记录。 */
export function fetchRuleExecutions(projectId: string, params: RuleExecutionListParams = {}) {
  return request.get<RuleExecutionSummaryPage>({
    url: `/api/v1/projects/${projectId}/rule-executions`,
    params
  })
}

/** 读取一次逻辑执行的 attempt 时间线（升序）。 */
export function fetchRuleExecutionAttempts(
  projectId: string,
  messageId: string,
  ruleId: string,
  ruleVersionId: string
) {
  return request.get<RuleExecutionAttemptResponse[]>({
    url: `/api/v1/projects/${projectId}/rule-executions/attempts`,
    params: { messageId, ruleId, ruleVersionId }
  })
}

/** 读取已产生执行事实的规则筛选选项。 */
export function fetchRuleExecutionRuleOptions(projectId: string) {
  return request.get<RuleOptionResponse[]>({
    url: `/api/v1/projects/${projectId}/rule-executions/rule-options`
  })
}

/** 分页读取当前项目的手动场景执行事实。 */
export function fetchSceneExecutions(projectId: string, params: SceneExecutionListParams = {}) {
  return request.get<RuleSceneExecutionPage>({
    url: `/api/v1/projects/${projectId}/scene-executions`,
    params
  })
}

/** 读取一次场景执行的详情（含通知与设备动作投递摘要）。 */
export function fetchSceneExecutionDetail(projectId: string, executionId: string) {
  return request.get<RuleSceneExecutionDetailResponse>({
    url: `/api/v1/projects/${projectId}/scene-executions/${executionId}`
  })
}

/** 读取已产生执行事实的场景筛选选项。 */
export function fetchSceneExecutionSceneOptions(projectId: string) {
  return request.get<RuleOptionResponse[]>({
    url: `/api/v1/projects/${projectId}/scene-executions/scene-options`
  })
}
