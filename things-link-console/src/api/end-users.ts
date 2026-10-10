import request from '@/utils/http'
import type { components } from '@/types/api/schema'
export type EndUser = components['schemas']['EndUserResponse']
export type EndUserRole = components['schemas']['EndUserRoleRequest']['role']
export type ProvisionEndUser = components['schemas']['ProvisionEndUserRequest']
const base = (projectId: string) =>
  `/api/v1/projects/${encodeURIComponent(projectId)}/end-users` as const
export function fetchEndUsers(projectId: string, cursor?: string) {
  return request.get<components['schemas']['CursorPageEndUserResponse']>({
    url: base(projectId),
    params: { limit: 20, cursor },
    showErrorMessage: false
  })
}
export function lookupEndUser(projectId: string, username: string) {
  return request.get<EndUser>({
    url: `${base(projectId)}/lookup`,
    params: { username },
    showErrorMessage: false
  })
}
export function provisionEndUser(projectId: string, body: ProvisionEndUser) {
  return request.post<EndUser>({ url: base(projectId), params: body, showErrorMessage: false })
}
export function assignEndUserRole(projectId: string, userId: string, role: EndUserRole) {
  return request.post<void>({
    url: `${base(projectId)}/${encodeURIComponent(userId)}/role`,
    params: { role },
    showErrorMessage: false
  })
}
export function updateEndUserRole(projectId: string, userId: string, role: EndUserRole) {
  return request.patch<void>({
    url: `${base(projectId)}/${encodeURIComponent(userId)}/role`,
    params: { role },
    showErrorMessage: false
  })
}
export function setEndUserRoleStatus(
  projectId: string,
  userId: string,
  action: 'suspend' | 'restore'
) {
  return request.post<void>({
    url: `${base(projectId)}/${encodeURIComponent(userId)}/${action}`,
    showErrorMessage: false
  })
}

/** 项目用户设备关系包含ACTIVE及CLOSED历史，不推导项目角色。 */
export function fetchEndUserDevices(projectId: string, userId: string) {
  return request.get<components['schemas']['EndUserDeviceBindingResponse'][]>({
    url: `${base(projectId)}/${encodeURIComponent(userId)}/devices`,
    showErrorMessage: false
  })
}
/** 幂等关闭指定项目有效关系，未知结果后先读取。 */
export function unbindEndUserDevice(projectId: string, userId: string, deviceId: string) {
  return request.del<void>({
    url: `${base(projectId)}/${encodeURIComponent(userId)}/devices/${encodeURIComponent(deviceId)}`,
    showErrorMessage: false
  })
}
/** 明文仅本次响应可见，不能重新查回或当作指定用户绑定。 */
export function issueDeviceClaimToken(projectId: string, deviceId: string) {
  return request.post<components['schemas']['DeviceClaimTokenResponse']>({
    url: `${base(projectId)}/device-claim-tokens`,
    params: { deviceId },
    showErrorMessage: false
  })
}

export type EndUserNotificationContact = components['schemas']['EndUserNotificationContactResponse']
export function fetchEndUserNotificationContact(projectId: string, userId: string) {
  return request.get<EndUserNotificationContact>({
    url: `${base(projectId)}/${encodeURIComponent(userId)}/notification-contact`,
    showErrorMessage: false
  })
}
export function updateEndUserNotificationContact(
  projectId: string,
  userId: string,
  body: components['schemas']['UpdateEndUserNotificationContactRequest']
) {
  return request.put<EndUserNotificationContact>({
    url: `${base(projectId)}/${encodeURIComponent(userId)}/notification-contact`,
    params: body,
    showErrorMessage: false
  })
}
