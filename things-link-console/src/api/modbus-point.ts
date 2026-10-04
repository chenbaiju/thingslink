import request from '@/utils/http'
import type { components } from '@/types/api/schema'

/** Modbus 点位响应；类型由 OpenAPI 生成，禁止在前端复制字段定义。 */
export type ModbusPointMappingResponse = components['schemas']['ModbusPointMappingResponse']
/** 创建或修改点位请求。 */
export type SaveModbusPointMappingRequest = components['schemas']['SaveModbusPointMappingRequest']

/** @param projectId 项目 ID @param deviceId 网关设备 ID */
export function fetchModbusPoints(projectId: string, deviceId: string) {
  return request.get<ModbusPointMappingResponse[]>({
    url: `/api/v1/projects/${projectId}/devices/${deviceId}/modbus-points`
  })
}

/** @param projectId 项目 ID @param deviceId 网关设备 ID @param body 点位参数 */
export function createModbusPoint(
  projectId: string,
  deviceId: string,
  body: SaveModbusPointMappingRequest
) {
  return request.post<ModbusPointMappingResponse>({
    url: `/api/v1/projects/${projectId}/devices/${deviceId}/modbus-points`,
    params: body
  })
}

/** @param projectId 项目 ID @param deviceId 网关设备 ID @param id 点位 ID @param body 点位参数 */
export function updateModbusPoint(
  projectId: string,
  deviceId: string,
  id: string,
  body: SaveModbusPointMappingRequest
) {
  return request.put<ModbusPointMappingResponse>({
    url: `/api/v1/projects/${projectId}/devices/${deviceId}/modbus-points/${id}`,
    params: body
  })
}

/** @param projectId 项目 ID @param deviceId 网关设备 ID @param id 点位 ID */
export function deleteModbusPoint(projectId: string, deviceId: string, id: string) {
  return request.del<void>({
    url: `/api/v1/projects/${projectId}/devices/${deviceId}/modbus-points/${id}`
  })
}

/** @param projectId 项目 ID @param deviceId 网关设备 ID */
export function publishModbusPoints(projectId: string, deviceId: string) {
  return request.post<void>({
    url: `/api/v1/projects/${projectId}/devices/${deviceId}/modbus-points/publish`
  })
}
