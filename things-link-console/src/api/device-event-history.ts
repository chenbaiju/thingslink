import {
  parseDashboardRuntimeResponse,
  isStrictJsonNumber
} from '@things-link/client-contracts/dashboard/v1'
import type { components } from '@/types/api/schema'
import { useUserStore } from '@/store/modules/user'
import { assertCurrentIdentity, currentIdentityEpoch } from '@/utils/http/identity-scope'
import { decodeEvent, decodeEventPage } from '@/features/device/event-history-model'

export type DeviceEventResponse = components['schemas']['DeviceEventResponse']
export type DeviceEventPageResponse = components['schemas']['DeviceEventPageResponse']
export type DeviceEventItem = Omit<DeviceEventResponse, 'params'> & { paramsText: string }
export type DeviceEventPage = Omit<DeviceEventPageResponse, 'items'> & { items: DeviceEventItem[] }
export interface EventHistoryFilters {
  eventKey: string
  level: string
  thingModelVersionId: string
  from: string
  to: string
}
export const EVENT_HISTORY_MAX_BYTES = 8 * 1024 * 1024
export class EventHistoryReadError extends Error {
  constructor(
    public readonly code?: number,
    public readonly status?: number
  ) {
    super('事件历史读取不可用，请检查筛选条件或刷新重试。')
  }
}
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
export function eventHistoryQuery(filters: EventHistoryFilters, cursor?: string) {
  const allowed = ['eventKey', 'level', 'thingModelVersionId', 'from', 'to']
  if (
    Object.keys(filters).some((key) => !allowed.includes(key)) ||
    allowed.some((key) => typeof filters[key as keyof EventHistoryFilters] !== 'string')
  )
    throw new EventHistoryReadError()
  const query = new URLSearchParams({ limit: '20' })
  if (filters.eventKey && !/^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$/.test(filters.eventKey))
    throw new EventHistoryReadError()
  if (filters.level && !['INFO', 'WARNING', 'ERROR'].includes(filters.level))
    throw new EventHistoryReadError()
  if (filters.thingModelVersionId && !uuid.test(filters.thingModelVersionId))
    throw new EventHistoryReadError()
  // 纳秒顺序与真实日期由后端Instant权威校验，不用Date.parse截断为毫秒。
  const timePattern =
    /^[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01])[Tt]([01][0-9]|2[0-3]):[0-5][0-9]:([0-5][0-9]|60)(\.[0-9]{1,9})?([Zz]|[+-]([01][0-9]|2[0-3]):[0-5][0-9])$/
  for (const name of ['from', 'to'] as const)
    if (filters[name] && !timePattern.test(filters[name])) throw new EventHistoryReadError()
  for (const [key, value] of Object.entries(filters)) if (value) query.set(key, value)
  if (cursor) {
    if (cursor.length > 8192) throw new EventHistoryReadError()
    query.set('cursor', cursor)
  }
  return query
}
async function read(url: string, signal?: AbortSignal): Promise<Uint8Array> {
  const identity = currentIdentityEpoch()
  const token = useUserStore().accessToken
  const controller = new AbortController()
  const cancel = () => controller.abort()
  signal?.addEventListener('abort', cancel, { once: true })
  if (signal?.aborted) controller.abort()
  const timeout = setTimeout(cancel, 20_000)
  let reader: ReadableStreamDefaultReader<Uint8Array> | undefined
  try {
    const base = (import.meta.env.VITE_API_URL || '/').replace(/\/$/, '')
    const response = await fetch(`${base}${url}`, {
      method: 'GET',
      headers: { Authorization: `Bearer ${token}`, Accept: 'application/json' },
      credentials: 'same-origin',
      cache: 'no-store',
      redirect: 'error',
      signal: controller.signal
    })
    assertCurrentIdentity(identity)
    if (
      response.headers.get('content-type')?.split(';')[0]?.trim().toLowerCase() !==
        'application/json' ||
      !response.body
    )
      throw new EventHistoryReadError(undefined, response.status)
    reader = response.body.getReader()
    const chunks: Uint8Array[] = []
    let size = 0
    while (true) {
      const chunk = await reader.read()
      assertCurrentIdentity(identity)
      if (chunk.done) break
      size += chunk.value.byteLength
      if (size > EVENT_HISTORY_MAX_BYTES)
        throw new EventHistoryReadError(undefined, response.status)
      chunks.push(chunk.value)
    }
    const bytes = new Uint8Array(size)
    let offset = 0
    for (const chunk of chunks) {
      bytes.set(chunk, offset)
      offset += chunk.byteLength
    }
    if (!response.ok) {
      // 错误正文不回显参数或服务端任意文本，只读取封闭数值错误码。
      let code: number | undefined
      try {
        const error = parseDashboardRuntimeResponse(bytes)
        if (
          Object.keys(error).length === 4 &&
          ['code', 'message', 'traceId', 'details'].every((key) => Object.hasOwn(error, key)) &&
          typeof error.message === 'string' &&
          typeof error.traceId === 'string' &&
          Array.isArray(error.details) &&
          error.details.every((value) => typeof value === 'string') &&
          isStrictJsonNumber(error.code!) &&
          /^\d{5}$/.test(error.code.lexical)
        )
          code = parseInt(error.code.lexical, 10)
      } catch {
        /* 非合同错误体只保留HTTP状态，不回显正文。 */
      }
      throw new EventHistoryReadError(code, response.status)
    }
    return bytes
  } catch (error) {
    if (controller.signal.aborted || signal?.aborted)
      throw new DOMException('事件读取已取消', 'AbortError')
    if (error instanceof EventHistoryReadError) throw error
    throw new EventHistoryReadError()
  } finally {
    void reader?.cancel().catch(() => undefined)
    clearTimeout(timeout)
    signal?.removeEventListener('abort', cancel)
  }
}
const path = (projectId: string, deviceId: string) =>
  `/api/v1/projects/${encodeURIComponent(projectId)}/devices/${encodeURIComponent(deviceId)}/events`
export async function fetchDeviceEvents(
  projectId: string,
  deviceId: string,
  filters: EventHistoryFilters,
  cursor?: string,
  signal?: AbortSignal
) {
  return decodeEventPage(
    await read(`${path(projectId, deviceId)}?${eventHistoryQuery(filters, cursor)}`, signal),
    deviceId
  )
}
export async function fetchDeviceEvent(
  projectId: string,
  deviceId: string,
  messageId: string,
  signal?: AbortSignal
) {
  return decodeEvent(
    await read(`${path(projectId, deviceId)}/${encodeURIComponent(messageId)}`, signal),
    deviceId,
    messageId
  )
}
