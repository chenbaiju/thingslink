import type { DeviceTypeResponse } from '@/api/device'

export interface TypeHandoffScope {
  projectId: string
  userId: string
  tenantId: string
  identity: number
  canCreate: boolean
}

/** URL 只传递意图；类型必须由当前项目接口重新读取，权限和身份在异步返回后再次核对。 */
export async function resolveTypeHandoff(
  query: { createTypeId?: unknown; contextProjectId?: unknown },
  scope: () => TypeHandoffScope,
  read: (projectId: string, typeId: string) => Promise<DeviceTypeResponse>
): Promise<DeviceTypeResponse | undefined> {
  const initial = scope()
  const typeId = query.createTypeId
  if (
    !initial.canCreate ||
    !initial.projectId ||
    typeof typeId !== 'string' ||
    !/^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(typeId) ||
    query.contextProjectId !== initial.projectId
  )
    return undefined
  const type = await read(initial.projectId, typeId)
  if (JSON.stringify(initial) !== JSON.stringify(scope())) return undefined
  return type.id === typeId && type.projectId === initial.projectId && type.status === 'PUBLISHED'
    ? type
    : undefined
}
