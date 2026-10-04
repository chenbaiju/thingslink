import request from '@/utils/http'
import type { components } from '@/types/api/schema'

/** 设备组响应；动态规则保持结构化，前端不得接受自由 SQL/DSL。 */
export type DeviceGroupResponse = components['schemas']['DeviceGroupResponse']
/** 创建或修改设备组请求。 */
export type SaveDeviceGroupRequest = components['schemas']['SaveDeviceGroupRequest']
/** 设备键值标签响应。 */
export type DeviceTagResponse = components['schemas']['DeviceTagResponse']
/** 新增或覆盖设备标签请求。 */
export type PutDeviceTagRequest = components['schemas']['PutDeviceTagRequest']
/** 静态组成员整集替换请求。 */
export type ReplaceDeviceGroupMembersRequest =
  components['schemas']['ReplaceDeviceGroupMembersRequest']
/** 组内设备沿用设备实例契约。 */
export type GroupDeviceResponse = components['schemas']['DeviceResponse']

/** 读取项目内全部有效设备组；服务端配置硬上限保证本轮 List 响应有界。 */
export function fetchDeviceGroups(projectId: string) {
  return request.get<DeviceGroupResponse[]>({
    url: `/api/v1/projects/${projectId}/device-groups`
  })
}

/** 创建静态组或按受控条件查询的动态组。 */
export function fetchCreateDeviceGroup(projectId: string, body: SaveDeviceGroupRequest) {
  return request.post<DeviceGroupResponse>({
    url: `/api/v1/projects/${projectId}/device-groups`,
    params: body
  })
}

/** 修改组名称、说明或动态规则；后端保持组类型不可变。 */
export function fetchUpdateDeviceGroup(
  projectId: string,
  groupId: string,
  body: SaveDeviceGroupRequest
) {
  return request.put<DeviceGroupResponse>({
    url: `/api/v1/projects/${projectId}/device-groups/${groupId}`,
    params: body
  })
}

/** 软删除设备组。 */
export function fetchDeleteDeviceGroup(projectId: string, groupId: string) {
  return request.del<void>({
    url: `/api/v1/projects/${projectId}/device-groups/${groupId}`
  })
}

/** 原子替换静态组完整成员集，避免逐条增删产生半完成界面。 */
export function fetchReplaceDeviceGroupMembers(
  projectId: string,
  groupId: string,
  body: ReplaceDeviceGroupMembersRequest
) {
  return request.put<void>({
    url: `/api/v1/projects/${projectId}/device-groups/${groupId}/devices`,
    params: body
  })
}

/** 读取单台设备全部键值标签。 */
export function fetchDeviceTags(projectId: string, deviceId: string) {
  return request.get<DeviceTagResponse[]>({
    url: `/api/v1/projects/${projectId}/devices/${deviceId}/tags`
  })
}

/** 新增标签或按键覆盖标签值。 */
export function fetchPutDeviceTag(projectId: string, deviceId: string, body: PutDeviceTagRequest) {
  return request.put<void>({
    url: `/api/v1/projects/${projectId}/devices/${deviceId}/tags`,
    params: body
  })
}

/** 删除单台设备的一个标签键。 */
export function fetchDeleteDeviceTag(projectId: string, deviceId: string, key: string) {
  return request.del<void>({
    url: `/api/v1/projects/${projectId}/devices/${deviceId}/tags/${key}`
  })
}
