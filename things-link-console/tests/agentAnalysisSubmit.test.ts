import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { submitNewAnalysisIntent } from '@/features/agent/analysis-submit'
import { analysisIntentStorage } from '@/features/agent/analysis-intent'
import { postAnalysisRun } from '@/api/assistant-analysis'
import { memoryStorage } from './commercialStorageFixture'
vi.mock('@/api/assistant-analysis', () => ({ postAnalysisRun: vi.fn() }))
const scope = {
  accountId: '00000000-0000-4000-8000-000000000001',
  tenantId: '00000000-0000-4000-8000-000000000002',
  projectId: '00000000-0000-4000-8000-000000000003'
}
const request = {
  deviceId: '00000000-0000-4000-8000-000000000004',
  expectedModelVersionId: '00000000-0000-4000-8000-000000000005',
  propertyKeys: ['temperature'],
  template: 'STATUS_SUMMARY' as const
}
const storageKey = `tc-agent-analysis-intent:v1:${scope.accountId}`
const locks = { request: async <T>(_name: string, cb: () => Promise<T>) => cb() }
let data: Storage
const ready = () => undefined
const signal = () => new AbortController().signal
beforeEach(() => {
  data = memoryStorage()
  vi.resetAllMocks()
})
afterEach(() => vi.restoreAllMocks())
it('仅在保存完整原意图后发送一次且任何返回均不删除意图', async () => {
  vi.mocked(postAnalysisRun).mockImplementation(async (intent) => {
    expect(JSON.parse(data.getItem(storageKey)!)).toEqual(intent)
    expect(intent.request).toEqual(request)
    return { call: null, category: 'UNAVAILABLE', result: null }
  })
  const store = analysisIntentStorage(data, locks)
  const value = await submitNewAnalysisIntent(store, scope, request, signal(), ready)
  expect(value.category).toBe('UNAVAILABLE')
  expect(data.length).toBe(1)
  await expect(submitNewAnalysisIntent(store, scope, request, signal(), ready)).rejects.toThrow(
    '已有待确认'
  )
  expect(postAnalysisRun).toHaveBeenCalledTimes(1)
})
it.each(['guard', 'abort', 'locks', 'storage'])(
  '%s失败在发请求前拒绝，不绕过生成新键',
  async (mode) => {
    const controller = new AbortController()
    if (mode === 'abort') controller.abort()
    if (mode === 'storage')
      vi.spyOn(data, 'setItem').mockImplementation(() => {
        throw new Error('quota')
      })
    await expect(
      submitNewAnalysisIntent(
        analysisIntentStorage(data, mode === 'locks' ? undefined : locks),
        scope,
        request,
        controller.signal,
        () => {
          if (mode === 'guard') throw new Error('closed')
        }
      )
    ).rejects.toThrow()
    expect(postAnalysisRun).not.toHaveBeenCalled()
    expect(data.length).toBe(0)
  }
)
it('已保存后取消不发送但保留恢复意图', async () => {
  const controller = new AbortController()
  const original = data.setItem.bind(data)
  vi.spyOn(data, 'setItem').mockImplementationOnce((name, value) => {
    original(name, value)
    controller.abort()
  })
  await expect(
    submitNewAnalysisIntent(
      analysisIntentStorage(data, locks),
      scope,
      request,
      controller.signal,
      ready
    )
  ).rejects.toThrow()
  expect(postAnalysisRun).not.toHaveBeenCalled()
  expect(data.length).toBe(1)
})
it.each(['network', 'identity', 'cancel', 'changed', 'removed'])(
  '发送后%s不重试、不返回旧正文、不清原意图',
  async (mode) => {
    const controller = new AbortController()
    let valid = true
    vi.mocked(postAnalysisRun).mockImplementation(async () => {
      if (mode === 'network') throw new Error('unknown-send')
      if (mode === 'identity') valid = false
      if (mode === 'cancel') controller.abort()
      if (mode === 'removed') data.removeItem(storageKey)
      if (mode === 'changed') {
        const intent = JSON.parse(data.getItem(storageKey)!)
        intent.request.template = 'ALARM_EXPLANATION'
        data.setItem(storageKey, JSON.stringify(intent))
      }
      return { call: null, category: 'UNAVAILABLE', result: null }
    })
    await expect(
      submitNewAnalysisIntent(
        analysisIntentStorage(data, locks),
        scope,
        request,
        controller.signal,
        () => {
          if (!valid) throw new Error('stale')
        }
      )
    ).rejects.toThrow()
    expect(postAnalysisRun).toHaveBeenCalledTimes(1)
    expect(data.length).toBe(mode === 'removed' ? 0 : 1)
  }
)
