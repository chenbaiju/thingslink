import request from '@/utils/http'
import type { components } from '@/types/api/schema'

/** 拓扑绑定响应；类型由 OpenAPI 生成，禁止在前端复制字段定义。 */
export type DeviceTopologyResponse = components['schemas']['DeviceTopologyResponse']
/** 绑定子设备请求。 */
export type BindTopologyRequest = components['schemas']['BindTopologyRequest']

/**
 * 查询项目内拓扑绑定；省略 gatewayId 时返回全部有效绑定（控制台拓扑树一次拉取）。
 *
 * @param projectId 当前项目 ID
 * @param gatewayId 可选网关 ID，指定时仅返回该网关挂载的子设备
 */
export function fetchDeviceTopologies(projectId: string, gatewayId?: string) {
  return request.get<DeviceTopologyResponse[]>({
    url: `/api/v1/projects/${projectId}/device-topologies`,
    params: gatewayId ? { gatewayId } : undefined
  })
}

/** @param projectId 项目 ID @param body 绑定请求（含子设备与网关 ID） */
export function fetchBindTopology(projectId: string, body: BindTopologyRequest) {
  return request.post<DeviceTopologyResponse>({
    url: `/api/v1/projects/${projectId}/device-topologies`,
    params: body
  })
}

/** @param projectId 项目 ID @param subDeviceId 要解绑的子设备 ID */
export function fetchUnbindTopology(projectId: string, subDeviceId: string) {
  return request.del<void>({
    url: `/api/v1/projects/${projectId}/device-topologies/${subDeviceId}`
  })
}
