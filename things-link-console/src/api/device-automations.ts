import request from '@/utils/http'
import type { components } from '@/types/api/schema'
export type DeviceAutomation = components['schemas']['DeviceAutomationResponse']
export type DeviceAutomationExecution = components['schemas']['DeviceAutomationExecutionResponse']
export type DeviceAutomationMode = 'definitions' | 'history'
type Page =
  | components['schemas']['CursorPageDeviceAutomationResponse']
  | components['schemas']['CursorPageDeviceAutomationExecutionResponse']
export function fetchDeviceAutomations(
  projectId: string,
  deviceId: string,
  mode: DeviceAutomationMode,
  cursor?: string,
  signal?: AbortSignal
) {
  return request.get<Page>({
    url: `/api/v1/projects/${projectId}/devices/${deviceId}/${mode === 'definitions' ? 'automations' : 'automation-executions'}`,
    params: { cursor, limit: 20 },
    signal
  })
}
