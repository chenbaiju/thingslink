import request from '@/utils/http'
import type { components } from '@/types/api/schema'
export type DeviceScene = components['schemas']['DeviceSceneResponse']
export type DeviceSceneExecution = components['schemas']['DeviceSceneExecutionResponse']
export type DeviceSceneMode = 'definitions' | 'history'
type Page =
  | components['schemas']['CursorPageDeviceSceneResponse']
  | components['schemas']['CursorPageDeviceSceneExecutionResponse']
export function fetchDeviceScenes(
  projectId: string,
  deviceId: string,
  mode: DeviceSceneMode,
  cursor?: string,
  signal?: AbortSignal
) {
  return request.get<Page>({
    url: `/api/v1/projects/${projectId}/devices/${deviceId}/${mode === 'definitions' ? 'scene-candidates' : 'scene-executions'}`,
    params: { cursor, limit: 20 },
    signal
  })
}
