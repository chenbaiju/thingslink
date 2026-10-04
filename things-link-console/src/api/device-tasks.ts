import request from '@/utils/http'
import type { components } from '@/types/api/schema'

export type DeviceTaskJob = components['schemas']['DeviceTaskJobResponse']
export type DeviceTaskExecution = components['schemas']['DeviceTaskExecutionResponse']
export type DeviceTaskMode = 'jobs' | 'history'
type Page =
  | components['schemas']['CursorPageDeviceTaskJobResponse']
  | components['schemas']['CursorPageDeviceTaskExecutionResponse']
export function fetchDeviceTasks(
  projectId: string,
  deviceId: string,
  mode: DeviceTaskMode,
  cursor?: string,
  signal?: AbortSignal
) {
  return request.get<Page>({
    url: `/api/v1/projects/${projectId}/devices/${deviceId}/${mode === 'jobs' ? 'task-jobs' : 'task-executions'}`,
    params: { cursor, limit: 20 },
    signal
  })
}
