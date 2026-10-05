import request from '@/utils/http'
import type { components } from '@/types/api/schema'

export type HistoryEvidence = components['schemas']['AssistantHistoryEvidence']
export type AlarmEvidence = components['schemas']['AssistantAlarmEvidence']

/** 单属性有界历史；时间窗和模型显式传递，不补查其他设备或属性。 */
export function readHistoryEvidence(
  project: string,
  device: string,
  model: string,
  property: string,
  from: string,
  to: string,
  signal: AbortSignal
) {
  return request.get<HistoryEvidence>({
    url: `/api/v1/projects/${encodeURIComponent(project)}/assistant/devices/${encodeURIComponent(device)}/history`,
    params: new URLSearchParams({ expectedModelVersionId: model, propertyKey: property, from, to }),
    signal,
    showErrorMessage: false
  })
}

/** 每次固定二十条，只传服务端游标，不自动追页或重试。 */
export function readAlarmEvidence(
  project: string,
  device: string,
  model: string,
  signal: AbortSignal,
  cursor?: string
) {
  const params = new URLSearchParams({ expectedModelVersionId: model, limit: '20' })
  if (cursor !== undefined) params.set('cursor', cursor)
  return request.get<AlarmEvidence>({
    url: `/api/v1/projects/${encodeURIComponent(project)}/assistant/devices/${encodeURIComponent(device)}/alarms`,
    params,
    signal,
    showErrorMessage: false
  })
}
