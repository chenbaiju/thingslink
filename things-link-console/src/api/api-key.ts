import request from '@/utils/http'
import type { components } from '@/types/api/schema'

export type ApiKey = components['schemas']['ApiKeyView']
export type CreateKey = components['schemas']['CreateKey']
type IssuedKey = components['schemas']['IssuedKey']
type KeyPage = components['schemas']['CursorPageApiKeyView']
const base = (project: string) => `/api/v1/projects/${project}/api-keys` as const
export const listKeys = (project: string, cursor?: string) =>
  request.get<KeyPage>({ url: base(project), params: { cursor, limit: 20 } })
export const createKey = (project: string, data: CreateKey, target?: string) =>
  target
    ? request.post<IssuedKey>({
        url: `${base(project)}/${target}/rotate`,
        params: data,
        headers: { 'Idempotency-Key': data.operationId }
      })
    : request.post<IssuedKey>({
        url: base(project),
        params: data,
        headers: { 'Idempotency-Key': data.operationId }
      })
export const revokeKey = (project: string, id: string, operationId: string) =>
  request.post<ApiKey>({
    url: `${base(project)}/${id}/revoke`,
    params: { operationId },
    headers: { 'Idempotency-Key': operationId }
  })
export const recoverKey = (project: string, operation: string) =>
  request.get<ApiKey>({ url: `${base(project)}/operations/${operation}` })
