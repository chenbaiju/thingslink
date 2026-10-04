import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  createPreviewScheduler,
  PreviewSchedulerBusyError,
  PreviewSchedulerCancelledError,
  type PreviewSchedulerState
} from '@/features/dashboard/preview-scheduler'

function deferred<T = void>() {
  let resolve!: (value: T) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((yes, no) => {
    resolve = yes
    reject = no
  })
  return { promise, resolve, reject }
}
const schedulers: ReturnType<typeof createPreviewScheduler>[] = []
function fixture(runFull = vi.fn<(signal: AbortSignal) => Promise<void>>().mockResolvedValue()) {
  let budget = { eligible: true, nextAvailableAt: null as number | null }
  const states: PreviewSchedulerState[] = []
  const subscribers = new Set<() => void>()
  const invalidate = vi.fn()
  const eligible = vi.fn((_kind: 'FULL' | 'INTERACTION') => budget)
  const scheduler = createPreviewScheduler({
    runFull,
    eligible,
    onState: (state) => states.push(state),
    onInvalidate: invalidate,
    subscribeBudget: (callback) => {
      subscribers.add(callback)
      return () => subscribers.delete(callback)
    },
    now: () => Date.now()
  })
  schedulers.push(scheduler)
  return {
    scheduler,
    runFull,
    invalidate,
    eligible,
    subscribers,
    state: () => states.at(-1)!,
    budget(value: typeof budget, notify = true) {
      budget = value
      if (notify) subscribers.forEach((callback) => callback())
    }
  }
}
async function settled() {
  await Promise.resolve()
  await Promise.resolve()
  await Promise.resolve()
  await Promise.resolve()
}
beforeEach(() => {
  vi.useFakeTimers()
  vi.setSystemTime(0)
})
afterEach(() => {
  schedulers.splice(0).forEach((scheduler) => scheduler.dispose())
  vi.useRealTimers()
})

describe('预览顶层调度与独立完整校准', () => {
  it('完整轮在途点击合并，成功60秒后仅校准一次', async () => {
    const first = deferred()
    const f = fixture(vi.fn().mockReturnValueOnce(first.promise).mockResolvedValue(undefined))
    f.scheduler.requestFull(true)
    await settled()
    f.scheduler.requestFull()
    f.scheduler.requestFull(true)
    expect(f.runFull).toHaveBeenCalledTimes(1)
    first.resolve()
    await settled()
    expect(f.state().busy).toBe('idle')
    await vi.advanceTimersByTimeAsync(59_999)
    expect(f.runFull).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(1)
    expect(f.runFull).toHaveBeenCalledTimes(2)
  })
  it('交互成功不重置完整成功的60秒校准时钟', async () => {
    const f = fixture()
    f.scheduler.requestFull()
    await settled()
    await vi.advanceTimersByTimeAsync(55_000)
    expect(await f.scheduler.runInteraction('alarm:page2', async () => 'page2')).toBe('page2')
    await vi.advanceTimersByTimeAsync(5_000)
    expect(f.runFull).toHaveBeenCalledTimes(2)
  })
  it('完整校准优先取消正在交互，等待其真正退出后才释放单飞槽', async () => {
    const f = fixture()
    f.scheduler.requestFull()
    await settled()
    await vi.advanceTimersByTimeAsync(55_000)
    const pending = deferred<string>()
    let signal!: AbortSignal
    const read = f.scheduler.runInteraction('alarm', async (input) => {
      signal = input
      return pending.promise
    })
    const rejected = expect(read).rejects.toBeInstanceOf(PreviewSchedulerCancelledError)
    await settled()
    await vi.advanceTimersByTimeAsync(5_000)
    expect(signal.aborted).toBe(true)
    expect(f.state()).toMatchObject({ busy: 'interaction', fullPending: true, latched: false })
    expect(f.runFull).toHaveBeenCalledTimes(1)
    pending.resolve('late')
    await rejected
    await settled()
    expect(f.runFull).toHaveBeenCalledTimes(2)
  })
  it('同key返回同一Promise，不同key有界拒绝且不执行第二个work', async () => {
    const f = fixture()
    const pending = deferred<number>()
    const work = vi.fn(() => pending.promise)
    const first = f.scheduler.runInteraction('directory', work)
    const duplicate = f.scheduler.runInteraction('directory', work)
    expect(duplicate).toBe(first)
    const extra = vi.fn(async () => 2)
    await expect(f.scheduler.runInteraction('alarm', extra)).rejects.toBeInstanceOf(
      PreviewSchedulerBusyError
    )
    expect(extra).not.toHaveBeenCalled()
    await settled()
    expect(work).toHaveBeenCalledTimes(1)
    pending.resolve(7)
    expect(await first).toBe(7)
  })
  it('依赖连续变化只保留一个新FULL，并等待取消的旧FULL退出', async () => {
    const first = deferred()
    let signal!: AbortSignal
    const f = fixture(
      vi
        .fn()
        .mockImplementationOnce((input: AbortSignal) => {
          signal = input
          return first.promise
        })
        .mockResolvedValue(undefined)
    )
    f.scheduler.requestFull()
    await settled()
    f.scheduler.dependencyChanged()
    f.scheduler.dependencyChanged()
    expect(signal.aborted).toBe(true)
    expect(f.runFull).toHaveBeenCalledTimes(1)
    expect(f.state().fullPending).toBe(true)
    first.reject(new Error('旧代次迟到失败'))
    await settled()
    expect(f.runFull).toHaveBeenCalledTimes(2)
    expect(f.state().latched).toBe(false)
  })
})

describe('预算资格等待与固定截止', () => {
  it('未开始完整轮等预算超过30秒也不试探请求，取得资格才启动截止', async () => {
    const work = deferred()
    const f = fixture(vi.fn().mockReturnValue(work.promise))
    f.budget({ eligible: false, nextAvailableAt: 61_000 })
    f.scheduler.requestFull(true)
    expect(f.state()).toMatchObject({ busy: 'idle', waitingUntil: 61_000, fullPending: true })
    await vi.advanceTimersByTimeAsync(60_000)
    expect(f.runFull).not.toHaveBeenCalled()
    expect(f.state().latched).toBe(false)
    f.budget({ eligible: true, nextAvailableAt: null }, false)
    await vi.advanceTimersByTimeAsync(1_000)
    expect(f.runFull).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(29_999)
    expect(f.state().latched).toBe(false)
    work.resolve()
    await settled()
  })
  it('完整与交互按自身预算资格等待，不能拿FULL余量开启交互', async () => {
    const f = fixture()
    f.eligible.mockImplementation((kind) => ({ eligible: kind === 'FULL', nextAvailableAt: null }))
    const work = vi.fn(async () => 1)
    const waiting = f.scheduler.runInteraction('alarm', work)
    const cancelled = expect(waiting).rejects.toBeInstanceOf(PreviewSchedulerCancelledError)
    await settled()
    expect(f.eligible).toHaveBeenLastCalledWith('INTERACTION')
    expect(work).not.toHaveBeenCalled()
    f.scheduler.requestFull(true)
    await cancelled
    await settled()
    expect(f.eligible).toHaveBeenLastCalledWith('FULL')
    expect(f.runFull).toHaveBeenCalledTimes(1)
  })
  it('未知或已过期资格时点只等预算通知，不建立零延迟循环', async () => {
    const f = fixture()
    f.budget({ eligible: false, nextAvailableAt: 0 })
    f.scheduler.requestFull()
    expect(f.state().waitingUntil).toBeNull()
    const reads = f.eligible.mock.calls.length
    await vi.advanceTimersByTimeAsync(120_000)
    expect(f.eligible).toHaveBeenCalledTimes(reads)
    expect(f.runFull).not.toHaveBeenCalled()
    f.budget({ eligible: true, nextAvailableAt: null })
    await settled()
    expect(f.runFull).toHaveBeenCalledTimes(1)
  })
  it('待资格交互同key合并，完整意图取消交互而不运行它', async () => {
    const f = fixture()
    f.budget({ eligible: false, nextAvailableAt: null })
    const work = vi.fn(async () => 1)
    const pending = f.scheduler.runInteraction('alarm', work)
    const rejected = expect(pending).rejects.toBeInstanceOf(PreviewSchedulerCancelledError)
    expect(f.scheduler.runInteraction('alarm', work)).toBe(pending)
    f.scheduler.requestFull(true)
    await rejected
    f.budget({ eligible: true, nextAvailableAt: null })
    await settled()
    expect(work).not.toHaveBeenCalled()
    expect(f.runFull).toHaveBeenCalledTimes(1)
  })
  it('超时立即锁存而不释放真实在途槽，忽略abort的成功不能通过', async () => {
    const pending = deferred<number>()
    const f = fixture()
    let signal!: AbortSignal
    const task = f.scheduler.runInteraction('alarm', async (input) => {
      signal = input
      return pending.promise
    })
    const rejected = expect(task).rejects.toMatchObject({ name: 'TimeoutError' })
    await settled()
    await vi.advanceTimersByTimeAsync(30_000)
    expect(signal.aborted).toBe(true)
    expect(f.state()).toMatchObject({ busy: 'interaction', latched: true })
    expect(f.invalidate).not.toHaveBeenCalled()
    pending.resolve(1)
    await rejected
    expect(f.state().busy).toBe('idle')
    await vi.advanceTimersByTimeAsync(120_000)
    expect(f.runFull).not.toHaveBeenCalled()
  })
})

describe('失败锁存与生命周期收束', () => {
  it('保留完整轮原始失败，普通点击/预算通知/重新可见不解锁', async () => {
    const cause = new Error('原始数据库错误')
    const f = fixture(vi.fn().mockRejectedValueOnce(cause).mockResolvedValue(undefined))
    f.scheduler.requestFull()
    await settled()
    expect(f.state().error).toBe(cause)
    f.scheduler.requestFull()
    f.scheduler.suspend()
    f.scheduler.resume()
    f.budget({ eligible: true, nextAvailableAt: null })
    await vi.advanceTimersByTimeAsync(120_000)
    expect(f.runFull).toHaveBeenCalledTimes(1)
    expect(f.state().error).toBe(cause)
    f.scheduler.requestFull(true)
    await settled()
    expect(f.runFull).toHaveBeenCalledTimes(2)
    expect(f.state().latched).toBe(false)
    expect(f.state()).not.toHaveProperty('error')
  })
  it('交互原始失败只锁存自动校准，不清全域；显式交互重试不偷解锁', async () => {
    const f = fixture()
    f.scheduler.requestFull()
    await settled()
    const before = f.invalidate.mock.calls.length
    const cause = { status: 500, message: '首因' }
    await expect(
      f.scheduler.runInteraction('alarm', async () => {
        throw cause
      })
    ).rejects.toBe(cause)
    expect(f.state().error).toBe(cause)
    expect(f.invalidate).toHaveBeenCalledTimes(before)
    expect(await f.scheduler.runInteraction('alarm', async () => 7)).toBe(7)
    expect(f.state().latched).toBe(true)
    await vi.advanceTimersByTimeAsync(120_000)
    expect(f.runFull).toHaveBeenCalledTimes(1)
    f.scheduler.dependencyChanged()
    await settled()
    expect(f.runFull).toHaveBeenCalledTimes(2)
  })
  it('隐藏取消不新增锁存，恢复等待旧任务退出后完整重读', async () => {
    const old = deferred()
    const f = fixture(vi.fn().mockReturnValueOnce(old.promise).mockResolvedValue(undefined))
    f.scheduler.requestFull()
    await settled()
    f.scheduler.suspend()
    expect(f.state().latched).toBe(false)
    await vi.advanceTimersByTimeAsync(90_000)
    f.scheduler.resume()
    expect(f.runFull).toHaveBeenCalledTimes(1)
    old.reject(new Error('主动中止后的旧错误'))
    await settled()
    expect(f.runFull).toHaveBeenCalledTimes(2)
    expect(f.state().latched).toBe(false)
  })
  it('已超时再隐藏仍保留失败锁存，恢复不偷启新轮', async () => {
    const old = deferred()
    const f = fixture(vi.fn().mockReturnValue(old.promise))
    f.scheduler.requestFull()
    await settled()
    await vi.advanceTimersByTimeAsync(30_000)
    f.scheduler.suspend()
    f.scheduler.resume()
    old.resolve()
    await settled()
    expect(f.state().latched).toBe(true)
    expect(f.runFull).toHaveBeenCalledTimes(1)
  })
  it('超时FULL显式重试排一个新意图，旧成功不覆盖恢复或提前释放槽', async () => {
    const old = deferred()
    const f = fixture(vi.fn().mockReturnValueOnce(old.promise).mockResolvedValue(undefined))
    f.scheduler.requestFull()
    await settled()
    await vi.advanceTimersByTimeAsync(30_000)
    expect(f.state()).toMatchObject({
      busy: 'full',
      latched: true,
      error: { name: 'TimeoutError' }
    })
    f.scheduler.requestFull(true)
    expect(f.state()).toMatchObject({ busy: 'full', fullPending: true, latched: false })
    expect(f.runFull).toHaveBeenCalledTimes(1)
    old.resolve()
    // 旧轮settle之后才启动新轮；等待新轮自身Promise终态，不只等待第二次调用出现。
    await vi.advanceTimersByTimeAsync(0)
    expect(f.runFull).toHaveBeenCalledTimes(2)
    expect(f.state()).toMatchObject({ busy: 'idle', latched: false, fullPending: false })
    expect(f.state()).not.toHaveProperty('error')
    await vi.advanceTimersByTimeAsync(59_999)
    expect(f.runFull).toHaveBeenCalledTimes(2)
    await vi.advanceTimersByTimeAsync(1)
    expect(f.runFull).toHaveBeenCalledTimes(3)
  })
  it('dispose取消等待/在途并移除预算订阅与所有计时句柄', async () => {
    const f = fixture()
    f.budget({ eligible: false, nextAvailableAt: 20_000 })
    const waiting = f.scheduler.runInteraction('alarm', async () => 1)
    const rejected = expect(waiting).rejects.toBeInstanceOf(PreviewSchedulerCancelledError)
    expect(vi.getTimerCount()).toBe(1)
    f.scheduler.dispose()
    await rejected
    expect(f.subscribers.size).toBe(0)
    expect(vi.getTimerCount()).toBe(0)
    f.scheduler.requestFull(true)
    f.scheduler.resume()
    await vi.advanceTimersByTimeAsync(120_000)
    expect(f.runFull).not.toHaveBeenCalled()
    await expect(f.scheduler.runInteraction('later', async () => 1)).rejects.toBeInstanceOf(
      PreviewSchedulerCancelledError
    )
  })
})
