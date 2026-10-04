import request from '@/utils/http'
import type { components } from '@/types/api/schema'
export type DeviceMessageRule = components['schemas']['DeviceMessageRuleResponse']
export type DeviceMessageRuleExecution = components['schemas']['DeviceMessageRuleExecutionResponse']
export type DeviceMessageRuleAction = components['schemas']['DeviceMessageRuleActionResponse']
export type DeviceMessageRuleMode = 'definitions' | 'history' | 'actions'
type Page =
  | components['schemas']['CursorPageDeviceMessageRuleResponse']
  | components['schemas']['CursorPageDeviceMessageRuleExecutionResponse']
  | components['schemas']['CursorPageDeviceMessageRuleActionResponse']
const endpoints = {
  definitions: 'message-rule-candidates',
  history: 'message-rule-executions',
  actions: 'message-rule-actions'
}
export function fetchDeviceMessageRules(
  projectId: string,
  deviceId: string,
  mode: DeviceMessageRuleMode,
  cursor?: string,
  signal?: AbortSignal
) {
  return request.get<Page>({
    url: `/api/v1/projects/${projectId}/devices/${deviceId}/${endpoints[mode]}`,
    params: { cursor, limit: 20 },
    signal
  })
}
