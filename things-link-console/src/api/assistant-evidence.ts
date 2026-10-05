import request from '@/utils/http'
import type { components } from '@/types/api/schema'

/** 受权设备证据，类型由现有 OpenAPI 唯一生成。 */
export type DeviceEvidenceSnapshot = components['schemas']['DeviceEvidenceSnapshot']

/** 只读获取指定属性；重复参数保持独立，不使用逗号或数组括号展开。 */
export function readDeviceEvidence(
  projectId: string,
  deviceId: string,
  modelVersionId: string,
  propertyKeys: string[],
  signal: AbortSignal
) {
  const params = new URLSearchParams({ expectedModelVersionId: modelVersionId })
  for (const key of propertyKeys) params.append('propertyKey', key)
  return request.get<DeviceEvidenceSnapshot>({
    url: `/api/v1/projects/${encodeURIComponent(projectId)}/assistant/devices/${encodeURIComponent(deviceId)}/snapshot`,
    params,
    signal,
    showErrorMessage: false
  })
}
