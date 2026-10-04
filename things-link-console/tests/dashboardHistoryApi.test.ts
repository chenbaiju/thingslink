import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { HistoryQuery } from '@things-link/client-contracts/dashboard/v1'
const state = vi.hoisted(() => ({ accessToken: 'test-token', epoch: 1 }))
vi.mock('@/store/modules/user', () => ({ useUserStore: () => state }))
vi.mock('@/utils/http/identity-scope', () => ({ currentIdentityEpoch: () => state.epoch }))
import { createDesignerReadScope } from '@/api/designer-read-scope'
import { fetchDesignerHistory } from '@/api/dashboard-history'
const id = '11111111-1111-4111-8111-111111111111'
const query: HistoryQuery = {
  queryId: 'one',
  deviceId: id,
  expectedModelVersionId: id,
  propertyKey: 'temperature',
  from: '2026-09-08T00:00:00Z',
  to: '2026-09-08T01:00:00Z',
  granularity: 'RAW',
  aggregation: 'AVG'
}
let clock = 0
const response = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } })
describe('Console严格历史适配', () => {
  beforeEach(() => {
    clock += 61000
    vi.spyOn(performance, 'now').mockImplementation(() => clock)
    vi.stubEnv('VITE_API_URL', '/')
    vi.stubGlobal('fetch', vi.fn())
    state.epoch = 1
  })
  afterEach(() => {
    vi.unstubAllGlobals()
    vi.unstubAllEnvs()
    vi.restoreAllMocks()
    vi.useRealTimers()
  })
  it('使用Console路径和完整查询，不混App身份', async () => {
    vi.mocked(fetch).mockResolvedValue(
      response({
        requestedGranularity: 'RAW',
        actualGranularity: 'RAW',
        aggregation: 'AVG',
        points: []
      })
    )
    const scope = createDesignerReadScope()
    try {
      expect((await fetchDesignerHistory(id, query, scope)).status).toBe('READY')
      const path = String(vi.mocked(fetch).mock.calls[0]![0])
      expect(path).toContain(`/projects/${id}/devices/${id}/telemetry/property/history/versioned?`)
      expect(path).toContain('expectedModelVersionId=')
      expect(path).not.toContain('/app/')
    } finally {
      scope.close()
    }
  })
  it.each([10001, 30058])('仅可信400业务码%s转换序列状态', async (code) => {
    vi.mocked(fetch).mockResolvedValue(response({ code }, 400))
    const scope = createDesignerReadScope()
    try {
      expect((await fetchDesignerHistory(id, query, scope)).status).toBe(
        code === 10001 ? 'CONFIGURATION_ERROR' : 'NON_NUMERIC'
      )
    } finally {
      scope.close()
    }
  })
  it('500同名码或字符串码不伪装为组件配置错误', async () => {
    for (const [status, code] of [
      [500, 10001],
      [400, '10001']
    ] as const) {
      vi.mocked(fetch).mockResolvedValue(response({ code }, status))
      const scope = createDesignerReadScope()
      try {
        await expect(fetchDesignerHistory(id, query, scope)).rejects.toThrow()
      } finally {
        scope.close()
      }
    }
  })
  it('四次每秒槽等待不发第五次请求，取消等待即终止', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    vi.mocked(fetch).mockImplementation(async () => response({}))
    const scope = createDesignerReadScope()
    try {
      for (let n = 0; n < 4; n++) await scope.read('/probe')
      const wait = scope.pace()
      const rejection = expect(wait).rejects.toThrow('取消')
      scope.close()
      await rejection
      expect(fetch).toHaveBeenCalledTimes(4)
    } finally {
      scope.close()
    }
  })
  it('时窗退出后可继续但不重发已经完成的请求', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    vi.mocked(fetch).mockImplementation(async () => response({}))
    const scope = createDesignerReadScope()
    try {
      for (let n = 0; n < 4; n++) await scope.read('/probe')
      const wait = scope.pace()
      clock += 1001
      await vi.advanceTimersByTimeAsync(1001)
      await wait
      await scope.read('/next')
      expect(fetch).toHaveBeenCalledTimes(5)
    } finally {
      scope.close()
    }
  })
  it('429服务端等待跨关闭和新scope保留，等待取消不发请求', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    vi.mocked(fetch).mockResolvedValueOnce(
      new Response('{}', {
        status: 429,
        headers: { 'content-type': 'application/json', 'Retry-After': '2' }
      })
    )
    const first = createDesignerReadScope()
    await expect(first.read('/first')).rejects.toThrow('服务不可用')
    first.close()
    const next = createDesignerReadScope()
    try {
      const waiting = next.read('/next')
      const rejected = expect(waiting).rejects.toThrow('取消')
      next.close()
      await rejected
      expect(fetch).toHaveBeenCalledTimes(1)
      clock += 2001
      vi.mocked(fetch).mockResolvedValue(response({}))
      const final = createDesignerReadScope()
      try {
        await final.read('/final')
        expect(fetch).toHaveBeenCalledTimes(2)
      } finally {
        final.close()
      }
    } finally {
      next.close()
    }
  })
})
