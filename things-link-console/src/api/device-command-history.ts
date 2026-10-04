import request from '@/utils/http'
import type { components } from '@/types/api/schema'

export type DeviceCommandHistoryItem = components['schemas']['DeviceCommandHistoryResponse']
type Page = components['schemas']['CursorPageDeviceCommandHistoryResponse']

export function fetchDeviceCommandHistory(
  projectId: string,
  deviceId: string,
  cursor?: string,
  signal?: AbortSignal
) {
  return request.get<Page>({
    url: `/api/v1/projects/${projectId}/devices/${deviceId}/commands`,
    params: { cursor, limit: 20 },
    signal
  })
}
