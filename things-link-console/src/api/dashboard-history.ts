import {
  decodeHistoryResponse,
  type HistoryQuery,
  type HistoryResult
} from '@things-link/client-contracts/dashboard/v1'
import type { DesignerReadScope } from './designer-read-scope'

/** Console严格历史沿自身成员身份读取，不适配App路径或降低最终点数预算。 */
export async function fetchDesignerHistory(
  projectId: string,
  query: HistoryQuery,
  scope: DesignerReadScope
): Promise<HistoryResult> {
  const uuid = /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/
  if (
    !uuid.test(projectId) ||
    !uuid.test(query.deviceId) ||
    !uuid.test(query.expectedModelVersionId)
  )
    throw new Error('历史查询身份非法')
  const params = new URLSearchParams({
    propertyKey: query.propertyKey,
    expectedModelVersionId: query.expectedModelVersionId,
    from: query.from,
    to: query.to,
    granularity: query.granularity,
    aggregation: query.aggregation
  })
  try {
    await scope.pace()
    const response = await scope.read(
      `/api/v1/projects/${projectId}/devices/${query.deviceId}/telemetry/property/history/versioned?${params}`
    )
    return decodeHistoryResponse(query, response)
  } catch (error) {
    const cause = error as { status?: number; code?: number }
    if (cause.status !== 400 || ![10001, 30058].includes(cause.code ?? 0)) throw error
    return {
      queryId: query.queryId,
      status: cause.code === 30058 ? 'NON_NUMERIC' : 'CONFIGURATION_ERROR',
      points: []
    }
  }
}
