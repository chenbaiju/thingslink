import {
  parseDashboardRuntimeResponse,
  isStrictJsonNumber
} from '@things-link/client-contracts/dashboard/v1'
import type { components } from '@/types/api/schema'
import { useUserStore } from '@/store/modules/user'
import { assertCurrentIdentity, currentIdentityEpoch } from '@/utils/http/identity-scope'
import { decodePropertyPage } from '@/features/device/property-history-model'

export type PropertyHistoryItem = Omit<components['schemas']['PropertyPointResponse'], 'value'> & {
  valueText: string
}
export interface PropertyHistoryPage {
  items: PropertyHistoryItem[]
  nextCursor?: string
  hasMore: boolean
}
export interface PropertyHistoryFilters {
  propertyKey: string
  from: string
  to: string
}
export const PROPERTY_HISTORY_MAX_BYTES = 4 * 1024 * 1024
export class PropertyHistoryReadError extends Error {
  constructor(
    public readonly code?: number,
    public readonly status?: number
  ) {
    super('原始属性历史不可用，请检查筛选条件或刷新重试。')
  }
}
export function propertyHistoryQuery(filters: PropertyHistoryFilters, cursor?: string) {
  const allowed = ['propertyKey', 'from', 'to'] as const
  if (
    Object.keys(filters).some((key) => !(allowed as readonly string[]).includes(key)) ||
    allowed.some((key) => typeof filters[key] !== 'string')
  )
    throw new PropertyHistoryReadError()
  if (filters.propertyKey && !/^[A-Za-z0-9_-]+$/.test(filters.propertyKey))
    throw new PropertyHistoryReadError()
  // 保留纳秒与时区；时间顺序和真实日期仍交给后端 Instant 校验。
  const timePattern =
    /^[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01])[Tt]([01][0-9]|2[0-3]):[0-5][0-9]:([0-5][0-9]|60)(\.[0-9]{1,9})?([Zz]|[+-]([01][0-9]|2[0-3]):[0-5][0-9])$/
  for (const key of ['from', 'to'] as const)
    if (filters[key] && !timePattern.test(filters[key])) throw new PropertyHistoryReadError()
  const query = new URLSearchParams({ limit: '20' })
  for (const [key, value] of Object.entries(filters)) if (value) query.set(key, value)
  if (cursor) {
    if (cursor.length > 8192) throw new PropertyHistoryReadError()
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
      throw new PropertyHistoryReadError(undefined, response.status)
    reader = response.body.getReader()
    const chunks: Uint8Array[] = []
    let size = 0
    while (true) {
      const chunk = await reader.read()
      assertCurrentIdentity(identity)
      if (chunk.done) break
      size += chunk.value.byteLength
      if (size > PROPERTY_HISTORY_MAX_BYTES)
        throw new PropertyHistoryReadError(undefined, response.status)
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
      throw new PropertyHistoryReadError(code, response.status)
    }
    return bytes
  } catch (error) {
    if (controller.signal.aborted || signal?.aborted)
      throw new DOMException('属性历史读取已取消', 'AbortError')
    if (error instanceof PropertyHistoryReadError) throw error
    throw new PropertyHistoryReadError()
  } finally {
    void reader?.cancel().catch(() => undefined)
    clearTimeout(timeout)
    signal?.removeEventListener('abort', cancel)
  }
}
export async function fetchDevicePropertyHistory(
  projectId: string,
  deviceId: string,
  filters: PropertyHistoryFilters,
  cursor?: string,
  signal?: AbortSignal
): Promise<PropertyHistoryPage> {
  const url = `/api/v1/projects/${encodeURIComponent(projectId)}/devices/${encodeURIComponent(deviceId)}/telemetry/property?${propertyHistoryQuery(filters, cursor)}`
  return decodePropertyPage(await read(url, signal), deviceId)
}
