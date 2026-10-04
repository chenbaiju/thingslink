import request from '@/utils/http'
import type { components } from '@/types/api/schema'
import { useUserStore } from '@/store/modules/user'
import { currentIdentityEpoch } from '@/utils/http/identity-scope'
import type { GrantWriteIntent } from '@/features/dashboard/grant-model'

const users = (projectId: string) =>
  `/api/v1/projects/${encodeURIComponent(projectId)}/end-users` as const
const base = (projectId: string, appUserId: string) =>
  `${users(projectId)}/${encodeURIComponent(appUserId)}/dashboard-grants` as const
export function fetchGrantUsers(projectId: string, cursor?: string) {
  return request.get<components['schemas']['CursorPageEndUserResponse']>({
    url: users(projectId),
    params: { limit: 20, ...(cursor === undefined ? {} : { cursor }) },
    showErrorMessage: false
  })
}
export function fetchDashboardGrant(projectId: string, appUserId: string, dashboardId: string) {
  return request.get<components['schemas']['AppUserDashboardGrantResponse']>({
    url: `${base(projectId, appUserId)}/${encodeURIComponent(dashboardId)}`,
    showErrorMessage: false
  })
}
export class DashboardGrantError extends Error {
  constructor(
    message: string,
    readonly code: number | undefined,
    readonly status: number | undefined,
    readonly outcomeUnknown: boolean
  ) {
    super(message)
  }
}
/** 授权管理写请求只发送一次。显式恢复才重用同key/同正文；不进入全局401刷新重放。 */
export async function writeDashboardGrantIntent(intent: GrantWriteIntent): Promise<unknown> {
  const epoch = currentIdentityEpoch(),
    token = useUserStore().accessToken
  if (!token) throw new DashboardGrantError('请先登录。', 401, 401, false)
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
      throw new DashboardGrantError('授权请求响应未知或身份已变化。', undefined, undefined, true)
    }
  }
  try {
    check()
    const response = await fetch(
      `${import.meta.env.VITE_API_URL.replace(/\/$/, '')}${base(intent.projectId, intent.appUserId)}/${encodeURIComponent(intent.dashboardId)}`,
      {
        method: 'PUT',
        headers: {
          Authorization: `Bearer ${token}`,
          'Content-Type': 'application/json',
          Accept: 'application/json',
          'Idempotency-Key': intent.key
        },
        body: JSON.stringify(intent.body),
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
        throw new DashboardGrantError(
          '授权响应缺失。',
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
            throw new DashboardGrantError(
              '授权响应超过4MiB上限。',
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
          throw new DashboardGrantError(
            '授权成功响应无法解析，需读取权威事实。',
            undefined,
            response.status,
            true
          )
      }
    }
    check()
    if (!response.ok) {
      const error = payload as { code?: unknown; message?: unknown } | undefined
      throw new DashboardGrantError(
        `授权请求被拒绝（HTTP ${response.status}）。`,
        typeof error?.code === 'number' ? error.code : response.status,
        response.status,
        response.status >= 500
      )
    }
    if (response.status !== 200)
      throw new DashboardGrantError('授权响应状态与操作不匹配。', undefined, response.status, true)
    return payload
  } catch (error) {
    if (error instanceof DashboardGrantError) throw error
    throw new DashboardGrantError(
      '授权请求结果未知，请使用原请求恢复。',
      undefined,
      undefined,
      true
    )
  } finally {
    clearTimeout(timer)
    controller.abort()
  }
}
