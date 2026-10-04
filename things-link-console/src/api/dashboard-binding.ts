import { createRuntimeNumber } from '@things-link/client-contracts/dashboard/v1'
import type {
  DeviceBindingProperty,
  DeviceComponentInput
} from '@/features/dashboard/designer-model'
import { createDesignerReadScope, type DesignerReadScope } from './designer-read-scope'
export { createDesignerReadScope } from './designer-read-scope'
export function fetchPreviewSnapshots(
  projectId: string,
  body: unknown,
  scope: DesignerReadScope
): Promise<unknown> {
  return scope.read(
    `/api/v1/projects/${encodeURIComponent(projectId)}/devices/snapshots/query`,
    body
  )
}
export function fetchPreviewCurrent(
  projectId: string,
  body: unknown,
  scope: DesignerReadScope
): Promise<unknown> {
  return scope.read(
    `/api/v1/projects/${encodeURIComponent(projectId)}/devices/current-value-snapshots/query`,
    body,
    { current: true }
  )
}
export interface BindingMetadata {
  model: DeviceComponentInput['model']
  properties: DeviceBindingProperty[]
}
export async function fetchBindingMetadata(
  projectId: string,
  deviceId: string,
  providedScope?: DesignerReadScope
): Promise<BindingMetadata> {
  const scope = providedScope ?? createDesignerReadScope()
  let payload: unknown
  try {
    payload = await scope.read(
      `/api/v1/projects/${encodeURIComponent(projectId)}/devices/${encodeURIComponent(deviceId)}/binding-metadata`
    )
  } finally {
    if (!providedScope) scope.close()
  }
  const response = payload as {
    devices?: { deviceId?: string; status?: string; currentModelVersionId?: string }[]
    models?: (DeviceComponentInput['model'] & {
      properties?: {
        propertyKey: string
        dataType: string
        minimumValue?: unknown
        maximumValue?: unknown
      }[]
    })[]
  }
  const device = response?.devices?.[0]
  const model = response?.models?.[0]
  if (
    response?.devices?.length !== 1 ||
    response?.models?.length !== 1 ||
    device?.deviceId !== deviceId ||
    device?.status !== 'AVAILABLE' ||
    !model ||
    model.versionId !== device.currentModelVersionId ||
    !/^[0-9a-f-]{36}$/.test(model.versionId) ||
    !/^[0-9a-f]{64}$/.test(model.digest) ||
    model.digestAlgorithm !== 'PG_JSONB_TEXT_V1_SHA256' ||
    model.profile !== 'TC_PROPERTY_COMPOSITE_V1' ||
    !Array.isArray(model.properties) ||
    model.properties.length > 200 ||
    model.properties.some(
      (property) =>
        typeof property?.propertyKey !== 'string' || typeof property.dataType !== 'string'
    ) ||
    new Set(model.properties.map((property) => property.propertyKey)).size !==
      model.properties.length
  ) {
    throw new Error('设备绑定元数据不完整或物模型已变化')
  }
  return {
    model: {
      versionId: model.versionId,
      digest: model.digest,
      digestAlgorithm: model.digestAlgorithm,
      profile: model.profile
    },
    properties: model.properties.map((property) => ({
      key: property.propertyKey,
      name: property.propertyKey,
      dataType: property.dataType,
      minimumValue:
        property.minimumValue == null ? null : createRuntimeNumber(property.minimumValue),
      maximumValue:
        property.maximumValue == null ? null : createRuntimeNumber(property.maximumValue)
    }))
  }
}

export interface DesignerDeviceCatalog {
  items: { deviceId: string; name: string; deviceStatus: string; currentModelVersionId: string }[]
  nextCursor: string | null
  hasMore: boolean
}
/** 精确模型过滤由服务端分页前完成；共享读取预算及身份围栏，不借用App或匿名权限。 */
export async function fetchDesignerDeviceCatalog(
  projectId: string,
  modelVersionId: string,
  cursor?: string,
  limit = 20,
  providedScope?: DesignerReadScope
): Promise<DesignerDeviceCatalog> {
  const scope = providedScope ?? createDesignerReadScope()
  const uuid = /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/
  try {
    if (
      !uuid.test(projectId) ||
      !uuid.test(modelVersionId) ||
      !Number.isInteger(limit) ||
      limit < 1 ||
      limit > 50
    )
      throw new Error('设备目录查询范围不合法')
    const query = new URLSearchParams({ modelVersionId, limit: String(limit) })
    if (cursor !== undefined) query.set('cursor', cursor)
    const response = (await scope.read(
      `/api/v1/projects/${projectId}/devices/catalog?${query}`
    )) as DesignerDeviceCatalog
    if (
      !response ||
      typeof response !== 'object' ||
      Object.keys(response).sort().join(',') !== 'hasMore,items,nextCursor' ||
      !Array.isArray(response.items) ||
      response.items.length > limit ||
      typeof response.hasMore !== 'boolean' ||
      (response.hasMore
        ? typeof response.nextCursor !== 'string' ||
          !response.nextCursor ||
          response.items.length !== limit
        : response.nextCursor !== null) ||
      response.items.some(
        (item) =>
          !item ||
          Object.keys(item).sort().join(',') !==
            'currentModelVersionId,deviceId,deviceStatus,name' ||
          !uuid.test(item.deviceId) ||
          typeof item.name !== 'string' ||
          !['INACTIVE', 'ONLINE', 'OFFLINE'].includes(item.deviceStatus) ||
          item.currentModelVersionId !== modelVersionId
      ) ||
      new Set(response.items.map((item) => item.deviceId)).size !== response.items.length
    )
      throw new Error('设备目录响应不符合精确模型合同')
    return response
  } finally {
    if (!providedScope) scope.close()
  }
}
