import { describe, expect, it, vi } from 'vitest'
import { RefreshCoordinator, type RefreshLockManager } from '@/utils/http/refresh-coordinator'

/** 创建由测试显式释放的 Promise，避免用 sleep 猜并发窗口。 */
function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => (resolve = done))
  return { promise, resolve }
}

/** 模拟浏览器跨标签共享的同名 Web Lock；不同协调器实例仍排入同一队列。 */
class FakeSharedLockManager implements RefreshLockManager {
  private tails = new Map<string, Promise<void>>()

  request<T>(name: string, callback: () => Promise<T>): Promise<T> {
    const previous = this.tails.get(name) ?? Promise.resolve()
    const result = previous.then(callback)
    this.tails.set(
      name,
      result.then(
        () => undefined,
        () => undefined
      )
    )
    return result
  }
}

describe('刷新协调器（401 单飞 / 双标签）', () => {
  it('同一标签多个 401 只执行一个刷新请求并共享结果', async () => {
    const pending = deferred<string>()
    const refresh = vi.fn(() => pending.promise)
    const coordinator = new RefreshCoordinator(refresh, undefined)

    const first = coordinator.run()
    const second = coordinator.run()
    expect(refresh).toHaveBeenCalledTimes(1)

    pending.resolve('token-1')
    await expect(Promise.all([first, second])).resolves.toEqual(['token-1', 'token-1'])
  })

  it('两个标签共享 Web Lock，旋转型 refresh Cookie 不会被并发使用', async () => {
    const locks = new FakeSharedLockManager()
    const releaseFirst = deferred<string>()
    let active = 0
    let maximumActive = 0
    const firstRefresh = vi.fn(async () => {
      active++
      maximumActive = Math.max(maximumActive, active)
      try {
        return await releaseFirst.promise
      } finally {
        active--
      }
    })
    const secondRefresh = vi.fn(async () => {
      active++
      maximumActive = Math.max(maximumActive, active)
      active--
      return 'token-tab-2'
    })
    const tab1 = new RefreshCoordinator(firstRefresh, locks)
    const tab2 = new RefreshCoordinator(secondRefresh, locks)

    const first = tab1.run()
    const second = tab2.run()
    await vi.waitFor(() => expect(firstRefresh).toHaveBeenCalledTimes(1))
    expect(secondRefresh).not.toHaveBeenCalled()

    releaseFirst.resolve('token-tab-1')
    await expect(Promise.all([first, second])).resolves.toEqual(['token-tab-1', 'token-tab-2'])
    expect(secondRefresh).toHaveBeenCalledTimes(1)
    expect(maximumActive).toBe(1)
  })
})

/** ADR0094：Web Lock排队不能让旧身份在真正入场时发送刷新。 */
describe('刷新队列身份围栏', () => {
  it('等待跨标签锁时身份改变，旧请求拒绝且不调用refresh，新身份仍能入场', async () => {
    const locks = new FakeSharedLockManager()
    const holding = deferred<string>()
    const holder = new RefreshCoordinator(() => holding.promise, locks)
    const entered = holder.run()
    let identity = 1
    const refresh = vi.fn(async () => 'new-token')
    const coordinator = new RefreshCoordinator(refresh, locks)
    const old = coordinator
      .run(1, () => {
        if (identity !== 1) throw new Error('stale')
      })
      .catch((error: unknown) => error)
    identity = 2
    const current = coordinator.run(2, () => {
      if (identity !== 2) throw new Error('stale')
    })
    holding.resolve('holder-token')
    await expect(entered).resolves.toBe('holder-token')
    expect(await old).toEqual(new Error('stale'))
    await expect(current).resolves.toBe('new-token')
    expect(refresh).toHaveBeenCalledTimes(1)
    expect(refresh).toHaveBeenCalledWith(2)
  })

  it('旧身份已失效时不能加入仍在途的同身份promise', async () => {
    const pending = deferred<string>()
    const refresh = vi.fn(() => pending.promise)
    const coordinator = new RefreshCoordinator(refresh, undefined)
    let valid = true
    const validate = () => {
      if (!valid) throw new Error('stale')
    }
    const first = coordinator.run(1, validate).catch((error: unknown) => error)
    valid = false
    expect(() => coordinator.run(1, validate)).toThrow('stale')
    pending.resolve('old-token')
    expect(await first).toEqual(new Error('stale'))
    expect(refresh).toHaveBeenCalledTimes(1)
  })
})
