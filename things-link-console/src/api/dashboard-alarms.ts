import {
  decodeAlarmResponse,
  type AlarmQuery,
  type AlarmResult
} from '@things-link/client-contracts/dashboard/v1'
import type { DesignerReadScope } from './designer-read-scope'
/** 同查询由页面计划统一读取，Console不得发送App身份或在客户端过滤全项目页。 */
export async function fetchDesignerAlarms(
  projectId: string,
  query: AlarmQuery,
  componentId: string,
  cursor: string | undefined,
  scope: DesignerReadScope
): Promise<AlarmResult> {
  if (
    !/^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(projectId) ||
    (cursor !== undefined && !/^[\x21-\x7e]{1,2048}$/.test(cursor))
  )
    throw new Error('告警查询范围不合法')
  try {
    await scope.pace()
    const response = await scope.read(`/api/v1/projects/${projectId}/alarms/query`, {
      devices: query.devices,
      conditionStates: query.conditionStates,
      ackStates: query.ackStates,
      severities: query.severities,
      limit: query.limit,
      ...(cursor === undefined ? {} : { cursor })
    })
    return decodeAlarmResponse(query, componentId, response)
  } catch (error) {
    const cause = error as { status?: number; code?: number }
    if (cause.status !== 400 || cause.code !== 10001) throw error
    return {
      componentId,
      queryId: query.queryId,
      status: 'CONFIGURATION_ERROR',
      items: [],
      nextCursor: null,
      hasMore: false
    }
  }
}
