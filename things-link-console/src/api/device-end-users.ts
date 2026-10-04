import request from '@/utils/http'
import type { components } from '@/types/api/schema'

export type DeviceEndUser = components['schemas']['DeviceEndUserResponse']
type Page = components['schemas']['CursorPageDeviceEndUserResponse']

export function fetchDeviceEndUsers(
  projectId: string,
  deviceId: string,
  cursor?: string,
  signal?: AbortSignal
) {
  return request.get<Page>({
    url: `/api/v1/projects/${projectId}/devices/${deviceId}/end-users`,
    params: { cursor, limit: 20 },
    signal
  })
}
