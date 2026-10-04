import request from '@/utils/http'
import type { components } from '@/types/api/schema'

/** ADR0093：通知与个人回执的字段全部来自后端生成合同，不复用共享ACK。 */
export type AlarmInboxItem = components['schemas']['AlarmInboxItemResponse']
export type AlarmInboxPage = components['schemas']['AlarmInboxPageResponse']
export type AlarmInboxCount = components['schemas']['AlarmInboxCountResponse']
export type AlarmInboxReadResult = components['schemas']['AlarmInboxReadResponse']
export type AlarmInboxReadRequest = components['schemas']['AlarmInboxReadRequest']

/** 只读当前页；错误由身份隔离后的局部状态展示，避免旧项目的失败弹出全局提示。 */
export function fetchAlarmInbox(
  projectId: string,
  cursor?: string,
  limit = 20,
  signal?: AbortSignal
) {
  return request.get<AlarmInboxPage>({
    url: `/api/v1/projects/${projectId}/alarm-notifications`,
    params: { cursor, limit },
    signal,
    showErrorMessage: false
  })
}

/** 100表示99+；失败不能被前端伪造为零。 */
export function fetchAlarmInboxUnreadCount(projectId: string, signal?: AbortSignal) {
  return request.get<AlarmInboxCount>({
    url: `/api/v1/projects/${projectId}/alarm-notifications/unread-count`,
    signal,
    showErrorMessage: false
  })
}

/** 仅发送明确事件身份，不允许指定受众账号或隐式时间水位。 */
export function fetchMarkAlarmInboxRead(
  projectId: string,
  eventIds: string[],
  signal?: AbortSignal
) {
  const body: AlarmInboxReadRequest = { eventIds }
  return request.post<AlarmInboxReadResult>({
    url: `/api/v1/projects/${projectId}/alarm-notifications/read`,
    params: body,
    signal,
    showErrorMessage: false,
    showSuccessMessage: false
  })
}
