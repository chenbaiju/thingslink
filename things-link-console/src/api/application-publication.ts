import request from '@/utils/http'
import type { components } from '@/types/api/schema'
import { useUserStore } from '@/store/modules/user'
import { currentIdentityEpoch } from '@/utils/http/identity-scope'
import type { PublicationIntent } from '@/features/application/publication-model'

const base = (projectId: string, id: string) =>
  `/api/v1/projects/${encodeURIComponent(projectId)}/applications/${encodeURIComponent(id)}` as const
export function fetchApplicationPublicationCatalog(projectId: string, id: string) {
  return request.get<components['schemas']['ApplicationCatalogResponse']>({
    url: base(projectId, id),
    showErrorMessage: false
  })
}
export function fetchApplicationPublicationHistory(projectId: string, id: string, cursor?: string) {
  return request.get<components['schemas']['CursorPageApplicationVersionSummaryResponse']>({
    url: `${base(projectId, id)}/versions`,
    params: { limit: 20, ...(cursor === undefined ? {} : { cursor }) },
    showErrorMessage: false
  })
}
export function fetchApplicationPublicationVersion(
  projectId: string,
  id: string,
  versionId: string
) {
  return request.get<components['schemas']['ApplicationVersionResponse']>({
    url: `${base(projectId, id)}/versions/${encodeURIComponent(versionId)}`,
    showErrorMessage: false
  })
}
export class ApplicationPublicationError extends Error {
  constructor(
    message: string,
    readonly code: number | undefined,
    readonly status: number | undefined,
    readonly outcomeUnknown: boolean
  ) {
    super(message)
  }
}
/** 发布管理写请求只发送一次。显式恢复才重用同key/同正文；不进入全局401刷新重放。 */
export async function writeApplicationPublicationIntent(
  intent: PublicationIntent
): Promise<unknown> {
  const epoch = currentIdentityEpoch(),
    token = useUserStore().accessToken
  if (!token) throw new ApplicationPublicationError('请先登录。', 401, 401, false)
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
      throw new ApplicationPublicationError(
        '发布请求响应未知或身份已变化。',
        undefined,
        undefined,
        true
      )
    }
  }
  // 全响应期限也约束服务端迟迟不结束的响应流。
  const bounded = async <T>(work: Promise<T>): Promise<T> => {
    let onAbort!: () => void
    const aborted = new Promise<never>((_, reject) => {
      onAbort = () =>
        reject(
          new ApplicationPublicationError(
            '发布请求结果未知，请使用原请求恢复。',
            undefined,
            undefined,
            true
          )
        )
      controller.signal.addEventListener('abort', onAbort, { once: true })
      if (controller.signal.aborted) onAbort()
    })
    try {
      return await Promise.race([work, aborted])
    } finally {
      controller.signal.removeEventListener('abort', onAbort)
    }
  }
  const suffix =
    intent.kind === 'PUBLISH'
      ? '/versions'
      : intent.kind === 'WITHDRAW'
        ? '/withdraw'
        : `/versions/${encodeURIComponent(intent.targetVersionId ?? '')}/rollback`
  try {
    check()
    const response = await bounded(
      fetch(
        `${import.meta.env.VITE_API_URL.replace(/\/$/, '')}${base(intent.projectId, intent.applicationId)}${suffix}`,
        {
          method: 'POST',
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
    )
    check()
    let payload: unknown
    if (response.status !== 204) {
      const reader = response.body?.getReader()
      if (!reader)
        throw new ApplicationPublicationError(
          '发布响应缺失。',
          undefined,
          response.status,
          response.ok || response.status >= 500
        )
      const chunks: Uint8Array[] = []
      let size = 0
      try {
        for (;;) {
          const chunk = await bounded(reader.read())
          check()
          if (chunk.done) break
          size += chunk.value.byteLength
          if (size > 4 * 1024 * 1024) {
            controller.abort()
            throw new ApplicationPublicationError(
              '发布响应超过4MiB上限。',
              undefined,
              response.status,
              response.ok || response.status >= 500
            )
          }
          chunks.push(chunk.value)
        }
      } finally {
        void reader.cancel().catch(() => undefined)
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
          throw new ApplicationPublicationError(
            '发布成功响应无法解析，需读取权威事实。',
            undefined,
            response.status,
            true
          )
      }
    }
    check()
    if (!response.ok) {
      const error = payload as { code?: unknown; message?: unknown } | undefined
      throw new ApplicationPublicationError(
        typeof error?.message === 'string'
          ? error.message
          : `发布请求被拒绝（HTTP ${response.status}）。`,
        typeof error?.code === 'number' ? error.code : response.status,
        response.status,
        response.status >= 500
      )
    }
    const expected = intent.kind === 'PUBLISH' ? 201 : intent.kind === 'WITHDRAW' ? 204 : 200
    if (response.status !== expected)
      throw new ApplicationPublicationError(
        '发布响应状态与操作不匹配。',
        undefined,
        response.status,
        true
      )
    return payload
  } catch (error) {
    if (error instanceof ApplicationPublicationError) throw error
    throw new ApplicationPublicationError(
      '发布请求结果未知，请使用原请求恢复。',
      undefined,
      undefined,
      true
    )
  } finally {
    clearTimeout(timer)
    controller.abort()
  }
}
