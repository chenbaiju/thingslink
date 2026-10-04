import request from '@/utils/http'
import { useUserStore } from '@/store/modules/user'
import { currentIdentityEpoch } from '@/utils/http/identity-scope'
import type { ShareCreateIntent } from '@/features/dashboard/sharing-model'

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
const base = (projectId: string, id: string) =>
  `/api/v1/projects/${encodeURIComponent(projectId)}/dashboards/${encodeURIComponent(id)}/shares` as const
export function fetchShareConfiguration(projectId: string, id: string): Promise<unknown> {
  return request.get({ url: `${base(projectId, id)}/configuration`, showErrorMessage: false })
}
export function fetchDashboardShares(
  projectId: string,
  id: string,
  cursor?: string
): Promise<unknown> {
  return request.get({
    url: base(projectId, id),
    params: { limit: 20, ...(cursor === undefined ? {} : { cursor }) },
    showErrorMessage: false
  })
}
interface RevokeIntent {
  projectId: string
  dashboardId: string
  shareId: string
}
export function createDashboardShareIntent(intent: ShareCreateIntent): Promise<unknown> {
  return sendShareWrite(intent)
}
export function revokeDashboardShare(
  projectId: string,
  dashboardId: string,
  shareId: string
): Promise<unknown> {
  return sendShareWrite({ projectId, dashboardId, shareId })
}
export class DashboardSharingError extends Error {
  constructor(
    message: string,
    readonly code: number | undefined,
    readonly status: number | undefined,
    readonly outcomeUnknown: boolean,
    readonly details?: unknown
  ) {
    super(message)
  }
}
/** 分享管理写请求只发送一次。显式恢复才重用同key/同正文；不进入全局401刷新重放。 */
export async function sendShareWrite(intent: ShareCreateIntent | RevokeIntent): Promise<unknown> {
  const epoch = currentIdentityEpoch(),
    token = useUserStore().accessToken
  if (!token) throw new DashboardSharingError('请先登录。', 401, 401, false)
  const controller = new AbortController(),
    deadline = performance.now() + 30_000
  const timer = setTimeout(() => controller.abort(), 30_000)
  const check = () => {
    if (
      controller.signal.aborted ||
      performance.now() >= deadline ||
      epoch !== currentIdentityEpoch() ||
      token !== useUserStore().accessToken
    ) {
      controller.abort()
      throw new DashboardSharingError('分享请求响应未知或身份已变化。', undefined, undefined, true)
    }
  }
  const creating = 'key' in intent
  const suffix = creating ? '' : `/${encodeURIComponent(intent.shareId)}/revoke`
  try {
    check()
    const response = await fetch(
      `${import.meta.env.VITE_API_URL.replace(/\/$/, '')}${base(intent.projectId, intent.dashboardId)}${suffix}`,
      {
        method: 'POST',
        headers: {
          Authorization: `Bearer ${token}`,
          ...(creating ? { 'Content-Type': 'application/json' } : {}),
          Accept: 'application/json',
          ...(creating ? { 'Idempotency-Key': intent.key } : {})
        },
        body: creating ? JSON.stringify(intent.body) : undefined,
        credentials: 'omit',
        cache: 'no-store',
        redirect: 'error',
        signal: controller.signal
      }
    )
    check()
    let payload: unknown
    if (response.status !== 204) {
      const reader = response.body?.getReader()
      if (!reader)
        throw new DashboardSharingError(
          '分享响应缺失。',
          undefined,
          response.status,
          response.ok || response.status >= 500
        )
      const chunks: Uint8Array[] = []
      let size = 0
      try {
        for (;;) {
          const chunk = await reader.read()
          check()
          if (chunk.done) break
          size += chunk.value.byteLength
          if (size > 4 * 1024 * 1024) {
            controller.abort()
            throw new DashboardSharingError(
              '分享响应超过4MiB上限。',
              undefined,
              response.status,
              response.ok || response.status >= 500
            )
          }
          chunks.push(chunk.value)
        }
      } finally {
        await reader.cancel().catch(() => undefined)
        reader.releaseLock()
      }
      const data = new Uint8Array(size)
      let offset = 0
      for (const chunk of chunks) {
        data.set(chunk, offset)
        offset += chunk.byteLength
      }
      try {
        payload = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(data))
      } catch {
        if (response.ok)
          throw new DashboardSharingError(
            '分享成功响应无法解析，需读取权威事实。',
            undefined,
            response.status,
            true
          )
      }
    }
    check()
    if (!response.ok) {
      const error = payload as { code?: unknown; details?: unknown } | undefined
      throw new DashboardSharingError(
        '分享请求被拒绝，请核对权限、版本和范围后恢复。',
        typeof error?.code === 'number' ? error.code : response.status,
        response.status,
        response.status >= 500,
        error?.code === 60052 &&
        Array.isArray(error.details) &&
        error.details.length === 1 &&
        typeof error.details[0] === 'string' &&
        UUID.test(error.details[0])
          ? error.details
          : undefined
      )
    }
    const expected = creating ? 201 : 204
    if (response.status !== expected)
      throw new DashboardSharingError(
        '分享响应状态与操作不匹配。',
        undefined,
        response.status,
        true
      )
    return payload
  } catch (error) {
    if (error instanceof DashboardSharingError) throw error
    throw new DashboardSharingError(
      '分享请求结果未知，请使用原请求恢复。',
      undefined,
      undefined,
      true
    )
  } finally {
    clearTimeout(timer)
    controller.abort()
  }
}
