import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
const state = vi.hoisted(() => ({ accessToken: 'token', epoch: 1, parsed: vi.fn() }))
vi.mock('@/store/modules/user', () => ({ useUserStore: () => state }))
vi.mock('@/utils/http/identity-scope', () => ({ currentIdentityEpoch: () => state.epoch }))
vi.mock('@things-link/client-contracts/dashboard/v1', () => ({
  parseDashboardRuntimeResponse: (bytes: Uint8Array) => state.parsed(bytes)
}))
type Budget = typeof import('@/api/designer-read-scope')
let api: Budget
const scopes: ReturnType<Budget['createDesignerReadScope']>[] = []
function scope(options?: Parameters<Budget['createDesignerReadScope']>[0]) {
  const value = api.createDesignerReadScope(options)
  scopes.push(value)
  return value
}
function response(bytes = 2) {
  return new Response(new Uint8Array(bytes), { headers: { 'content-type': 'application/json' } })
}
async function spend(count: number, kind: 'FULL' | 'INTERACTION') {
  for (let n = 0; n < count; n++) {
    const reading = scope({ kind })
    await reading.read('/read')
    reading.close()
    if (n % 4 === 3) await vi.advanceTimersByTimeAsync(1000)
  }
}
describe('设计读取跨轮非退款预算与事件等待', () => {
  beforeEach(async () => {
    vi.resetModules()
    vi.stubEnv('VITE_API_URL', '/')
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'performance'] })
    state.epoch = 1
    state.accessToken = 'token'
    state.parsed.mockReset().mockReturnValue({})
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => response())
    )
    api = await import('@/api/designer-read-scope')
  })
  afterEach(() => {
    scopes.splice(0).forEach((item) => item.close())
    vi.unstubAllGlobals()
    vi.unstubAllEnvs()
    vi.useRealTimers()
  })
  it('取消换身份不退一秒4次；下一请求等待准确边界而非立即发出', async () => {
    for (let n = 0; n < 4; n++) {
      const reading = scope()
      await reading.read('/read')
      reading.close()
      state.epoch++
    }
    expect(api.designerReadEligibility('INTERACTION')).toEqual({
      eligible: false,
      nextAvailableAt: 1000
    })
    const pending = scope().read('/read')
    await vi.advanceTimersByTimeAsync(999)
    expect(fetch).toHaveBeenCalledTimes(4)
    await vi.advanceTimersByTimeAsync(1)
    await expect(pending).resolves.toEqual({})
    expect(fetch).toHaveBeenCalledTimes(5)
  })
  it('交互100次为完整保留20；总120次已发失败/关闭都不退款', async () => {
    await spend(100, 'INTERACTION')
    expect(api.designerReadEligibility('INTERACTION')).toEqual({
      eligible: false,
      nextAvailableAt: 60000
    })
    expect(api.designerReadEligibility('FULL').eligible).toBe(true)
    await spend(20, 'FULL')
    expect(fetch).toHaveBeenCalledTimes(120)
    expect(api.designerReadEligibility('FULL')).toEqual({ eligible: false, nextAvailableAt: 60000 })
    await vi.advanceTimersByTimeAsync(30000)
    expect(api.designerReadEligibility('FULL').eligible).toBe(true)
    expect(api.designerReadEligibility('INTERACTION').eligible).toBe(true)
  })
  it('最多4在途，未知释放仅订阅事件不创建轮询timer；释放后唤醒', async () => {
    vi.mocked(fetch).mockImplementation(
      (_path, options) =>
        new Promise((_resolve, reject) => {
          options?.signal?.addEventListener('abort', () => reject(new Error('aborted')))
        })
    )
    const active = Array.from({ length: 4 }, () => scope())
    const reads = active.map((reading) => reading.read('/read').catch((error) => error))
    await vi.advanceTimersByTimeAsync(1000)
    const waiting = vi.fn(),
      reading = scope({ onWait: waiting })
    const pending = reading.read('/read')
    await vi.advanceTimersByTimeAsync(0)
    expect(api.designerReadEligibility('FULL')).toEqual({ eligible: false, nextAvailableAt: null })
    expect(waiting).toHaveBeenCalledWith(null)
    expect(vi.getTimerCount()).toBe(5)
    await vi.advanceTimersByTimeAsync(5000)
    expect(fetch).toHaveBeenCalledTimes(4)
    vi.mocked(fetch).mockImplementation(async () => response())
    active[0]!.close()
    await expect(pending).resolves.toEqual({})
    expect(waiting).toHaveBeenLastCalledWith(undefined)
    active.forEach((item) => item.close())
    await Promise.all(reads)
  })
  it('不同轮的current最多一个在途，其他REST可并行，真实完成释放current槽', async () => {
    let resolve!: (response: Response) => void
    vi.mocked(fetch).mockImplementationOnce(
      () =>
        new Promise((done) => {
          resolve = done
        })
    )
    const first = scope().read('/current', {}, { current: true })
    await vi.advanceTimersByTimeAsync(0)
    expect(api.designerReadEligibility('FULL', { current: true }).nextAvailableAt).toBeNull()
    const second = scope({ kind: 'FULL' }).read('/current', {}, { current: true })
    await scope().read('/metadata')
    expect(fetch).toHaveBeenCalledTimes(2)
    resolve(response())
    await Promise.all([first, second])
    expect(fetch).toHaveBeenCalledTimes(3)
  })
  it('56MiB交互失败正文仍计数，FULL能使用保留8MiB；64MiB总量不可突破', async () => {
    vi.mocked(fetch).mockImplementation(
      async () => new Response(new Uint8Array(4 * 1024 * 1024), { status: 500 })
    )
    for (let n = 0; n < 14; n++) {
      const reading = scope()
      await expect(reading.read('/read')).rejects.toMatchObject({ status: 500 })
      reading.close()
      await vi.advanceTimersByTimeAsync(1000)
    }
    expect(api.designerReadEligibility('INTERACTION').eligible).toBe(false)
    expect(api.designerReadEligibility('FULL').eligible).toBe(true)
    for (let n = 0; n < 2; n++) {
      const reading = scope({ kind: 'FULL' })
      await expect(reading.read('/read')).rejects.toMatchObject({ status: 500 })
      reading.close()
      await vi.advanceTimersByTimeAsync(1000)
    }
    expect(api.designerReadEligibility('FULL')).toEqual({ eligible: false, nextAvailableAt: 61000 })
    await vi.advanceTimersByTimeAsync(45000)
    expect(api.designerReadEligibility('FULL').eligible).toBe(true)
  })
  it('预留未使用可退款；小正文不能永久当成4MiB，释放通知可取消订阅', async () => {
    const listener = vi.fn(),
      unsubscribe = api.subscribeDesignerReadBudget(listener)
    await spend(20, 'INTERACTION')
    expect(api.designerReadEligibility('INTERACTION').eligible).toBe(true)
    expect(listener).toHaveBeenCalled()
    unsubscribe()
    listener.mockClear()
    await scope().read('/read')
    expect(listener).not.toHaveBeenCalled()
  })
  it('等待在途释放也耗原30秒，取消唤醒等待并删除事件监听', async () => {
    vi.mocked(fetch).mockImplementation(
      (_path, options) =>
        new Promise((_resolve, reject) => {
          options?.signal?.addEventListener('abort', () => reject(new Error('aborted')))
        })
    )
    const first = scope()
      .read('/current', {}, { current: true })
      .catch((error) => error)
    await vi.advanceTimersByTimeAsync(0)
    const waitingScope = scope(),
      wait = waitingScope.pace({ current: true }).catch((error) => error)
    waitingScope.close()
    expect(await wait).toBeInstanceOf(Error)
    const deadline = scope()
      .read('/current', {}, { current: true })
      .catch((error) => error)
    await vi.advanceTimersByTimeAsync(30000)
    expect(await deadline).toBeInstanceOf(Error)
    await first
    expect(fetch).toHaveBeenCalledTimes(1)
  })
  it('并发等待唤醒仍原子预留，四次窗口释放后只允许四个请求', async () => {
    await spend(4, 'FULL')
    // now=1000，八个不同轮同时竞争，第二批必须等下一秒。
    const pending = Array.from({ length: 8 }, () => scope({ kind: 'FULL' }).read('/read'))
    await vi.advanceTimersByTimeAsync(0)
    expect(fetch).toHaveBeenCalledTimes(8)
    await vi.advanceTimersByTimeAsync(999)
    expect(fetch).toHaveBeenCalledTimes(8)
    await vi.advanceTimersByTimeAsync(1)
    await Promise.all(pending)
    expect(fetch).toHaveBeenCalledTimes(12)
  })
  it('429头即使来自已取消轮也保留等待；新轮不隐式重试失败请求', async () => {
    let resolve!: (response: Response) => void
    vi.mocked(fetch).mockImplementationOnce(
      () =>
        new Promise((done) => {
          resolve = done
        })
    )
    const old = scope(),
      read = old.read('/read').catch((error) => error)
    await vi.advanceTimersByTimeAsync(0)
    old.close()
    resolve(new Response('{}', { status: 429, headers: { 'Retry-After': '12' } }))
    await read
    expect(api.designerReadEligibility('FULL')).toEqual({ eligible: false, nextAvailableAt: 12000 })
    const next = scope({ kind: 'FULL' }).read('/read')
    await vi.advanceTimersByTimeAsync(11999)
    expect(fetch).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(1)
    await next
    expect(fetch).toHaveBeenCalledTimes(2)
  })
  it.each([401, 403, 404])('读取%d保留拒绝分类、不等待慢正文', async (status) => {
    vi.mocked(fetch).mockResolvedValueOnce(new Response(new ReadableStream(), { status }))
    await expect(scope().read('/read')).rejects.toMatchObject({ status })
    expect(state.parsed).not.toHaveBeenCalled()
  })
  it('单轮最多20次实际发送且WS不占REST次数，pace不能借新的轮预算', async () => {
    const reading = scope({ kind: 'FULL' })
    expect(reading.tryReserveWebSocket()).toBe(true)
    for (let n = 0; n < 20; n++) {
      await reading.read('/read')
      if (n % 4 === 3) await vi.advanceTimersByTimeAsync(1000)
    }
    await expect(reading.read('/read')).rejects.toThrow('次数')
    expect(fetch).toHaveBeenCalledTimes(20)
  })
  it('同一完整轮累计8MiB之后下一响应整体拒绝，实际字节仍留在全局窗口', async () => {
    vi.mocked(fetch).mockImplementation(async () => response(4 * 1024 * 1024))
    const reading = scope({ kind: 'FULL' })
    await reading.read('/read')
    await reading.read('/read')
    await expect(reading.read('/read')).rejects.toThrow('字节')
    expect(state.parsed).toHaveBeenCalledTimes(2)
    expect(fetch).toHaveBeenCalledTimes(3)
  })
  it('单个响应超过4MiB拒绝，不把前4MiB截断为成功正文', async () => {
    vi.mocked(fetch).mockResolvedValueOnce(response(4 * 1024 * 1024 + 1))
    await expect(scope({ kind: 'FULL' }).read('/read')).rejects.toThrow('字节')
    expect(state.parsed).not.toHaveBeenCalled()
  })
  it('握手每轮一次、跨关闭换身份每60秒四次，额度不足仍能REST', async () => {
    for (let n = 0; n < 4; n++) {
      const reading = scope({ kind: 'FULL' })
      expect(reading.tryReserveWebSocket()).toBe(true)
      expect(reading.tryReserveWebSocket()).toBe(false)
      reading.close()
      state.epoch++
    }
    const degraded = scope({ kind: 'FULL' })
    expect(degraded.tryReserveWebSocket()).toBe(false)
    await degraded.read('/read')
    expect(fetch).toHaveBeenCalledTimes(1)
    degraded.close()
    await vi.advanceTimersByTimeAsync(59999)
    expect(scope().tryReserveWebSocket()).toBe(false)
    await vi.advanceTimersByTimeAsync(1)
    expect(scope().tryReserveWebSocket()).toBe(true)
  })
  it('握手不占REST次数，同一30秒deadline不因准备或读取推进', async () => {
    const reading = scope({ kind: 'FULL' })
    const deadline = reading.deadline
    await vi.advanceTimersByTimeAsync(25000)
    expect(reading.deadline).toBe(deadline)
    expect(reading.tryReserveWebSocket()).toBe(true)
    await reading.read('/read')
    await vi.advanceTimersByTimeAsync(5000)
    expect(() => reading.tryReserveWebSocket()).toThrow('取消')
    expect(reading.deadline).toBe(deadline)
  })
  it('取消/身份变化先于握手资格复核，拒绝使用失效凭据', () => {
    const closed = scope()
    closed.close()
    expect(() => closed.tryReserveWebSocket()).toThrow('取消')
    const stale = scope()
    state.epoch++
    expect(() => stale.tryReserveWebSocket()).toThrow('取消')
    const changedToken = scope()
    state.accessToken = 'replacement'
    expect(() => changedToken.tryReserveWebSocket()).toThrow('取消')
    expect(scope().tryReserveWebSocket()).toBe(true)
  })
  it('解析后复核截止，过期结果不能交付', async () => {
    state.parsed.mockImplementation(() => {
      vi.advanceTimersByTime(30000)
      return {}
    })
    await expect(scope().read('/read')).rejects.toThrow('取消')
  })
})
