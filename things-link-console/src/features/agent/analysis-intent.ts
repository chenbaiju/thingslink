import type { components } from '@/types/api/schema'

export type AnalysisRequest = components['schemas']['AssistantAnalysisRequest']
export interface AnalysisScope {
  accountId: string
  tenantId: string
  projectId: string
}
export interface AnalysisIntent extends AnalysisScope {
  version: 1
  key: string
  request: AnalysisRequest
}
export interface AnalysisIntentLocks {
  request<T>(name: string, callback: () => Promise<T>): Promise<T>
}
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
const UUID7 = /^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/
const invalid = () => new Error('分析意图无法核对，已保留原记录，请勿重复提交。')
const exact = (value: unknown, names: string): value is Record<string, unknown> =>
  !!value &&
  typeof value === 'object' &&
  !Array.isArray(value) &&
  Object.keys(value).sort().join(',') === names
const uuid = (value: unknown): value is string => typeof value === 'string' && UUID.test(value)

export function validateAnalysisScope(scope: AnalysisScope): void {
  if (!scope || ![scope.accountId, scope.tenantId, scope.projectId].every(uuid)) throw invalid()
}

/** 键的时间来自原UUIDv7，不能通过恢复或刷新延长24小时。 */
export function assertAnalysisKeyUsable(key: string, now = Date.now()): void {
  if (typeof key !== 'string' || !UUID7.test(key) || !Number.isFinite(now)) throw invalid()
  const created = Number.parseInt(key.replaceAll('-', '').slice(0, 12), 16)
  if (created > now + 300_000 || now >= created + 86_400_000)
    throw new Error('原分析调用键已超出有效时间，请保留记录人工核对，不要自动重新提交。')
}

/** 仅生成恢复身份；不意味着模型可用或获得了发送授权。 */
export function newAnalysisKey(now = Date.now()): string {
  if (!Number.isSafeInteger(now) || now < 0 || now > 0xffffffffffff) throw invalid()
  const bytes = crypto.getRandomValues(new Uint8Array(16))
  let timestamp = now
  for (let i = 5; i >= 0; i--) {
    bytes[i] = timestamp % 256
    timestamp = Math.floor(timestamp / 256)
  }
  bytes[6] = (bytes[6] & 15) | 0x70
  bytes[8] = (bytes[8] & 63) | 0x80
  const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}

/** 发送和存储共用封闭意图校验，返回独立副本，拒绝附加字段。 */
export function parseIntent(value: unknown): AnalysisIntent {
  if (
    !exact(value, 'accountId,key,projectId,request,tenantId,version') ||
    value.version !== 1 ||
    !uuid(value.accountId) ||
    !uuid(value.tenantId) ||
    !uuid(value.projectId) ||
    typeof value.key !== 'string' ||
    !UUID7.test(value.key)
  )
    throw invalid()
  const request = value.request
  if (
    !exact(request, 'deviceId,expectedModelVersionId,propertyKeys,template') ||
    !uuid(request.deviceId) ||
    !uuid(request.expectedModelVersionId) ||
    !['STATUS_SUMMARY', 'ALARM_EXPLANATION'].includes(request.template as string) ||
    !Array.isArray(request.propertyKeys) ||
    request.propertyKeys.length < 1 ||
    request.propertyKeys.length > 10 ||
    request.propertyKeys.some(
      (key) => typeof key !== 'string' || !/^[A-Za-z0-9_-]{1,64}$/.test(key)
    ) ||
    new Set(request.propertyKeys).size !== request.propertyKeys.length
  )
    throw invalid()
  // 重建闭集，调用方修改返回对象不能改写持久原意图。
  return {
    version: 1,
    accountId: value.accountId,
    tenantId: value.tenantId,
    projectId: value.projectId,
    key: value.key,
    request: {
      deviceId: request.deviceId,
      expectedModelVersionId: request.expectedModelVersionId,
      template: request.template as AnalysisRequest['template'],
      propertyKeys: [...request.propertyKeys]
    }
  }
}

export type LoadedAnalysisIntent =
  | { kind: 'missing' }
  | { kind: 'other-scope' }
  | { kind: 'pending'; intent: AnalysisIntent }

/** 同账号单条待确认意图；不自动删除、过期换键或提交，人工处置只比较删除原记录。 */
export function analysisIntentStorage(storage: Storage, locks?: AnalysisIntentLocks) {
  const storageKey = (account: string) => `tc-agent-analysis-intent:v1:${account}`
  function stored(account: string): AnalysisIntent | null {
    try {
      const raw = storage.getItem(storageKey(account))
      if (raw === null) return null
      if (raw.length > 4096) throw invalid()
      const intent = parseIntent(JSON.parse(raw))
      if (intent.accountId !== account) throw invalid()
      return intent
    } catch {
      throw invalid()
    }
  }
  return {
    load(scope: AnalysisScope): LoadedAnalysisIntent {
      validateAnalysisScope(scope)
      const intent = stored(scope.accountId)
      if (!intent) return { kind: 'missing' }
      if (intent.tenantId !== scope.tenantId || intent.projectId !== scope.projectId)
        return { kind: 'other-scope' }
      return { kind: 'pending', intent }
    },
    async forget(
      scope: AnalysisScope,
      expected: AnalysisIntent,
      assertCurrent: () => void
    ): Promise<void> {
      validateAnalysisScope(scope)
      const candidate = parseIntent(expected)
      if (
        candidate.accountId !== scope.accountId ||
        candidate.tenantId !== scope.tenantId ||
        candidate.projectId !== scope.projectId
      )
        throw invalid()
      assertCurrent()
      if (!locks) throw new Error('当前浏览器不支持跨标签互斥，不能处置原分析意图。')
      await locks.request(storageKey(candidate.accountId), async () => {
        assertCurrent()
        const current = stored(candidate.accountId)
        if (!current || JSON.stringify(current) !== JSON.stringify(candidate))
          throw new Error('原分析意图已变化，请重新核对，未删除其他记录。')
        try {
          storage.removeItem(storageKey(candidate.accountId))
          if (storage.getItem(storageKey(candidate.accountId)) !== null) throw invalid()
        } catch {
          throw new Error('本地处置未能确认，请重新核对；远端调用不受影响。')
        }
        assertCurrent()
      })
    },
    async create(
      scope: AnalysisScope,
      request: AnalysisRequest,
      assertCurrent: () => void
    ): Promise<AnalysisIntent> {
      validateAnalysisScope(scope)
      // 等待锁前冻结输入，页面切换和调用方修改不能改变这次意图。
      const candidate = parseIntent({
        ...scope,
        version: 1,
        key: '00000000-0000-7000-8000-000000000000',
        request
      })
      assertCurrent()
      if (!locks) throw new Error('当前浏览器不支持跨标签互斥，不能创建新的分析意图。')
      return locks.request(storageKey(candidate.accountId), async () => {
        assertCurrent()
        if (stored(candidate.accountId)) throw new Error('已有待确认的分析意图，请先核对原调用。')
        candidate.key = newAnalysisKey()
        assertAnalysisKeyUsable(candidate.key)
        try {
          const raw = JSON.stringify(candidate)
          storage.setItem(storageKey(candidate.accountId), raw)
          if (storage.getItem(storageKey(candidate.accountId)) !== raw) throw invalid()
        } catch {
          throw invalid()
        }
        assertCurrent()
        return candidate
      })
    }
  }
}
