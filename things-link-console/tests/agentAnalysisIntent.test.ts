import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import {
  analysisIntentStorage,
  assertAnalysisKeyUsable,
  newAnalysisKey,
  type AnalysisIntentLocks,
  type AnalysisRequest
} from '@/features/agent/analysis-intent'
import { mayForgetAnalysisIntent, recoverAnalysisIntent } from '@/features/agent/analysis-recovery'
import { readAnalysisCallByKey } from '@/api/assistant-analysis'
import { memoryStorage } from './commercialStorageFixture'
vi.mock('@/api/assistant-analysis', () => ({ readAnalysisCallByKey: vi.fn() }))

const scope = {
  accountId: '00000000-0000-4000-8000-000000000001',
  tenantId: '00000000-0000-4000-8000-000000000002',
  projectId: '00000000-0000-4000-8000-000000000003'
}
const request: AnalysisRequest = {
  deviceId: '00000000-0000-4000-8000-000000000004',
  expectedModelVersionId: '00000000-0000-4000-8000-000000000005',
  propertyKeys: ['temperature', 'enabled'],
  template: 'STATUS_SUMMARY'
}
const storageKey = `tc-agent-analysis-intent:v1:${scope.accountId}`
let localStorage: Storage
const valid = () => undefined
const signal = () => new AbortController().signal

/** 两个独立客户端共用同名队列，模拟同源Web Locks的跨标签互斥。 */
function locks(): AnalysisIntentLocks {
  const tails = new Map<string, Promise<unknown>>()
  return {
    request<T>(name: string, callback: () => Promise<T>): Promise<T> {
      const next = (tails.get(name) ?? Promise.resolve()).then(callback, callback)
      tails.set(
        name,
        next.catch(() => undefined)
      )
      return next
    }
  }
}
beforeEach(() => {
  localStorage = memoryStorage()
  vi.resetAllMocks()
  vi.useFakeTimers()
  vi.setSystemTime(new Date('2026-10-05T06:00:00.000Z'))
})
afterEach(() => vi.useRealTimers())

it('生成规范UUIDv7并按原时间判定24小时及未来容差', () => {
  const now = Date.now(),
    key = newAnalysisKey()
  expect(key).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab]/)
  expect(newAnalysisKey()).not.toBe(key)
  expect(() => assertAnalysisKeyUsable(key, now + 86_399_999)).not.toThrow()
  expect(() => assertAnalysisKeyUsable(key, now + 86_400_000)).toThrow()
  expect(() => assertAnalysisKeyUsable(key, now - 300_000)).not.toThrow()
  expect(() => assertAnalysisKeyUsable(key, now - 300_001)).toThrow()
})
it('保存闭集后刷新仍保留原键及属性顺序，读取不发送请求', async () => {
  const store = analysisIntentStorage(localStorage, locks())
  const pending = await store.create(scope, request, valid)
  expect(JSON.parse(localStorage.getItem(storageKey)!)).toEqual({
    ...scope,
    version: 1,
    key: pending.key,
    request
  })
  pending.request.propertyKeys.reverse()
  const restored = analysisIntentStorage(localStorage).load(scope)
  expect(restored.kind).toBe('pending')
  if (restored.kind === 'pending')
    expect(restored.intent.request.propertyKeys).toEqual(request.propertyKeys)
  expect(readAnalysisCallByKey).not.toHaveBeenCalled()
})
it('两个标签同时创建只允许一条意图，另一项目也不能覆盖', async () => {
  const mutex = locks()
  const one = analysisIntentStorage(localStorage, mutex),
    two = analysisIntentStorage(localStorage, mutex)
  const result = await Promise.allSettled([
    one.create(scope, request, valid),
    two.create({ ...scope, projectId: request.deviceId }, request, valid)
  ])
  expect(result.map((r) => r.status)).toEqual(['fulfilled', 'rejected'])
  expect(two.load({ ...scope, projectId: request.deviceId })).toEqual({ kind: 'other-scope' })
  expect(two.load({ ...scope, tenantId: request.deviceId })).toEqual({ kind: 'other-scope' })
  expect(two.load({ ...scope, accountId: request.deviceId })).toEqual({ kind: 'missing' })
  expect(localStorage.length).toBe(1)
})
it('锁不可用时拒绝新意图但可恢复既有意图', async () => {
  const original = await analysisIntentStorage(localStorage, locks()).create(scope, request, valid)
  const noLock = analysisIntentStorage(localStorage)
  await expect(noLock.create(scope, request, valid)).rejects.toThrow('跨标签')
  expect(noLock.load(scope)).toEqual({ kind: 'pending', intent: original })
})
it.each(['read', 'write', 'verify'])(
  '存储%s失败时不返回可提交的意图，不自动删除已写内容',
  async (mode) => {
    const data = new Map<string, string>()
    const storage = {
      getItem(key: string) {
        if (mode === 'read') throw new Error('private detail')
        return mode === 'verify' ? null : (data.get(key) ?? null)
      },
      setItem(key: string, value: string) {
        if (mode === 'write') throw new Error('quota')
        data.set(key, value)
      }
    } as Storage
    await expect(
      analysisIntentStorage(storage, locks()).create(scope, request, valid)
    ).rejects.toThrow('分析意图无法核对')
    expect(data.size).toBe(mode === 'verify' ? 1 : 0)
    expect(readAnalysisCallByKey).not.toHaveBeenCalled()
  }
)
it.each(['{broken', 'null', 'x'.repeat(4097)])('存储损坏拒绝覆盖和丢弃', async (raw) => {
  localStorage.setItem(storageKey, raw)
  const store = analysisIntentStorage(localStorage, locks())
  expect(() => store.load(scope)).toThrow()
  await expect(store.create(scope, request, valid)).rejects.toThrow()
  expect(localStorage.getItem(storageKey)).toBe(raw)
})
it.each([
  { ...request, apiKey: 'never-store' },
  { ...request, propertyKeys: [] },
  { ...request, propertyKeys: ['same', 'same'] },
  { ...request, propertyKeys: ['free text'] },
  { ...request, template: 'FREE_TEXT' },
  { ...request, deviceId: 'device-name' }
])('拒绝扩展或不合规请求，不保存任何内容', async (bad) => {
  await expect(
    analysisIntentStorage(localStorage, locks()).create(scope, bad as AnalysisRequest, valid)
  ).rejects.toThrow()
  expect(localStorage.length).toBe(0)
})
it('锁排队中身份失效不保存，输入提前冻结不接受排队中篡改', async () => {
  let release!: () => void
  const wait = new Promise<void>((resolve) => {
    release = resolve
  })
  const mutex: AnalysisIntentLocks = {
    request: async (_name, cb) => {
      await wait
      return cb()
    }
  }
  let current = true
  const store = analysisIntentStorage(localStorage, mutex)
  const old = store.create(scope, request, () => {
    if (!current) throw new Error('stale')
  })
  current = false
  release()
  await expect(old).rejects.toThrow('stale')
  expect(localStorage.length).toBe(0)
  const copy = { ...request, propertyKeys: [...request.propertyKeys] }
  const fresh = store.create(scope, copy, valid)
  copy.propertyKeys.push('unapproved')
  expect((await fresh).request.propertyKeys).toEqual(request.propertyKeys)
})
it('写入后身份失效仍保留原记录，不能声称没有待确认意图', async () => {
  let checks = 0
  await expect(
    analysisIntentStorage(localStorage, locks()).create(scope, request, () => {
      if (++checks === 3) throw new Error('stale')
    })
  ).rejects.toThrow('stale')
  expect(localStorage.length).toBe(1)
})
it('仅手动恢复原项目原键，刷新后不重建原请求或修改存储', async () => {
  const store = analysisIntentStorage(localStorage, locks()),
    pending = await store.create(scope, request, valid)
  const before = localStorage.getItem(storageKey)
  const call = { id: newAnalysisKey(), status: 'UNKNOWN' }
  vi.mocked(readAnalysisCallByKey).mockResolvedValue(call as never)
  const abort = signal()
  expect(await recoverAnalysisIntent(store, scope, abort, valid)).toBe(call)
  expect(readAnalysisCallByKey).toHaveBeenCalledExactlyOnceWith(scope.projectId, pending.key, abort)
  expect(localStorage.getItem(storageKey)).toBe(before)
})
it.each([400, 401, 403, 404, 409, 500])('恢复错误%s保留原键、不重试、不清除', async (code) => {
  const store = analysisIntentStorage(localStorage, locks())
  await store.create(scope, request, valid)
  const before = localStorage.getItem(storageKey)
  vi.mocked(readAnalysisCallByKey).mockRejectedValue({ code })
  await expect(recoverAnalysisIntent(store, scope, signal(), valid)).rejects.toEqual({ code })
  expect(readAnalysisCallByKey).toHaveBeenCalledTimes(1)
  expect(localStorage.getItem(storageKey)).toBe(before)
})
it('到期保留人工核对记录，其他范围和已取消请求均零网络', async () => {
  const store = analysisIntentStorage(localStorage, locks())
  await store.create(scope, request, valid)
  const before = localStorage.getItem(storageKey)
  await expect(
    recoverAnalysisIntent(store, { ...scope, projectId: request.deviceId }, signal(), valid)
  ).rejects.toThrow()
  const controller = new AbortController()
  controller.abort()
  await expect(recoverAnalysisIntent(store, scope, controller.signal, valid)).rejects.toThrow()
  vi.setSystemTime(Date.now() + 86_400_000)
  await expect(recoverAnalysisIntent(store, scope, signal(), valid)).rejects.toThrow('有效时间')
  expect(localStorage.getItem(storageKey)).toBe(before)
  expect(readAnalysisCallByKey).not.toHaveBeenCalled()
})
it.each(['identity', 'cancel'])('读取期间%s变化丢弃迟到响应且原意图不丢失', async (change) => {
  const store = analysisIntentStorage(localStorage, locks())
  await store.create(scope, request, valid)
  let finish!: (value: never) => void
  vi.mocked(readAnalysisCallByKey).mockImplementation(
    () =>
      new Promise((resolve) => {
        finish = resolve
      })
  )
  let current = true
  const controller = new AbortController()
  const pending = recoverAnalysisIntent(store, scope, controller.signal, () => {
    if (!current) throw new Error('stale')
  })
  if (change === 'identity') current = false
  else controller.abort()
  finish({ status: 'SUCCEEDED' } as never)
  await expect(pending).rejects.toThrow()
  expect(localStorage.length).toBe(1)
})

it('人工处置仅删除完整匹配的本账号原记录，保留其他存储', async () => {
  const store = analysisIntentStorage(localStorage, locks())
  const pending = await store.create(scope, request, valid)
  localStorage.setItem('unrelated', 'keep')
  await store.forget(scope, pending, valid)
  expect(store.load(scope)).toEqual({ kind: 'missing' })
  expect(localStorage.getItem('unrelated')).toBe('keep')
  expect(readAnalysisCallByKey).not.toHaveBeenCalled()
})
it.each(['scope', 'body', 'missing', 'locks', 'identity'])(
  '处置%s不匹配时拒绝误删',
  async (mode) => {
    const store = analysisIntentStorage(localStorage, locks())
    const pending = await store.create(scope, request, valid)
    if (mode === 'body')
      localStorage.setItem(
        storageKey,
        JSON.stringify({ ...pending, request: { ...request, template: 'ALARM_EXPLANATION' } })
      )
    if (mode === 'missing') localStorage.removeItem(storageKey)
    const before = localStorage.getItem(storageKey)
    await expect(
      (mode === 'locks' ? analysisIntentStorage(localStorage) : store).forget(
        mode === 'scope' ? { ...scope, projectId: request.deviceId } : scope,
        pending,
        () => {
          if (mode === 'identity') throw new Error('stale')
        }
      )
    ).rejects.toThrow()
    expect(localStorage.getItem(storageKey)).toBe(before)
  }
)
it('处置等待锁期间身份变化不删除记录', async () => {
  const pending = await analysisIntentStorage(localStorage, locks()).create(scope, request, valid)
  let release!: () => void
  const wait = new Promise<void>((yes) => {
    release = yes
  })
  let current = true
  const store = analysisIntentStorage(localStorage, {
    request: async (_name, cb) => {
      await wait
      return cb()
    }
  })
  const run = store.forget(scope, pending, () => {
    if (!current) throw new Error('stale')
  })
  current = false
  release()
  await expect(run).rejects.toThrow('stale')
  expect(localStorage.length).toBe(1)
})
it.each(['remove', 'verify'])('处置%s异常不宣称已删除', async (mode) => {
  const pending = await analysisIntentStorage(localStorage, locks()).create(scope, request, valid)
  if (mode === 'remove')
    vi.spyOn(localStorage, 'removeItem').mockImplementation(() => {
      throw new Error('private')
    })
  else vi.spyOn(localStorage, 'removeItem').mockImplementation(() => undefined)
  await expect(
    analysisIntentStorage(localStorage, locks()).forget(scope, pending, valid)
  ).rejects.toThrow('处置未能确认')
  expect(localStorage.length).toBe(1)
})
it('查询前完整意图变化零网络，查询中变化丢弃原响应', async () => {
  const store = analysisIntentStorage(localStorage, locks())
  const original = await store.create(scope, request, valid)
  const changed = { ...original, request: { ...request, template: 'ALARM_EXPLANATION' } }
  localStorage.setItem(storageKey, JSON.stringify(changed))
  await expect(recoverAnalysisIntent(store, scope, signal(), valid, original)).rejects.toThrow(
    '已变化'
  )
  expect(readAnalysisCallByKey).not.toHaveBeenCalled()
  localStorage.setItem(storageKey, JSON.stringify(original))
  vi.mocked(readAnalysisCallByKey).mockImplementation(async () => {
    localStorage.setItem(storageKey, JSON.stringify(changed))
    return { status: 'SUCCEEDED' } as never
  })
  await expect(recoverAnalysisIntent(store, scope, signal(), valid, original)).rejects.toThrow(
    '迟到状态'
  )
  expect(JSON.parse(localStorage.getItem(storageKey)!)).toEqual(changed)
})
it('人工处置时间边界与结束状态独立于费用判断', async () => {
  const intent = await analysisIntentStorage(localStorage, locks()).create(scope, request, valid)
  const now = Date.now()
  const call = {
    status: 'UNKNOWN',
    finishedAt: new Date(now).toISOString(),
    deadline: new Date(now + 60_000).toISOString()
  }
  expect(mayForgetAnalysisIntent(intent, undefined, now + 86_459_999)).toBe(false)
  expect(mayForgetAnalysisIntent(intent, undefined, now + 86_460_000)).toBe(true)
  expect(mayForgetAnalysisIntent(intent, call as never, now + 59_999)).toBe(false)
  expect(mayForgetAnalysisIntent(intent, call as never, now + 60_000)).toBe(true)
  for (const status of ['SUCCEEDED', 'FAILED'])
    expect(mayForgetAnalysisIntent(intent, { ...call, status } as never, now)).toBe(true)
  for (const status of ['RESERVED', 'RUNNING'])
    expect(mayForgetAnalysisIntent(intent, { ...call, status } as never, now)).toBe(false)
  for (const finishedAt of [null, '', 'invalid'])
    expect(
      mayForgetAnalysisIntent(intent, { ...call, status: 'SUCCEEDED', finishedAt } as never, now)
    ).toBe(false)
  expect(mayForgetAnalysisIntent(intent, undefined, NaN)).toBe(false)
  expect(mayForgetAnalysisIntent({ ...intent, key: 'invalid' }, call as never, now)).toBe(false)
})
