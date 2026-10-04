/** 同源页面间互斥所需的最小 Web Locks 契约，便于用确定性假锁测试双标签竞争。 */
export interface RefreshLockManager {
  request<T>(name: string, callback: () => Promise<T>): Promise<T>
}

/** 所有控制台标签共用的锁名；同源 Web Locks 会跨标签串行执行同名临界区。 */
const REFRESH_LOCK_NAME = 'things-link-auth-refresh'

/**
 * 访问令牌刷新协调器。
 *
 * 同一页面复用一个 in-flight Promise，避免首屏多个 401 重复刷新；不同页面再通过 Web Locks
 * 串行使用旋转型 HttpOnly refresh Cookie，避免两个标签同时提交旧 Cookie 而触发令牌族复用检测。
 * 不把 access token 写入 localStorage；不支持 Web Locks 的浏览器只退化为同标签单飞。
 */
export class RefreshCoordinator<T> {
  private readonly inFlight = new Map<number, Promise<T>>()

  constructor(
    private readonly refresh: (identity: number) => Promise<T>,
    private readonly locks: RefreshLockManager | undefined = browserLockManager()
  ) {}

  /** 同身份单飞；真正进入Web Lock后复验，旧组结束不能清掉新身份的刷新。 */
  run(identity = 0, validate: () => void = () => undefined): Promise<T> {
    validate()
    const existing = this.inFlight.get(identity)
    if (existing) return existing

    const execute = () => {
      validate()
      return this.refresh(identity).then(
        (value) => {
          validate()
          return value
        },
        (error: unknown) => {
          validate()
          throw error
        }
      )
    }
    const operation = this.locks ? this.locks.request(REFRESH_LOCK_NAME, execute) : execute()
    const pending = operation.finally(() => {
      if (this.inFlight.get(identity) === pending) this.inFlight.delete(identity)
    })
    this.inFlight.set(identity, pending)
    return pending
  }
}

/** @return 当前浏览器的 Web Locks 适配器；SSR/旧浏览器返回 undefined。 */
function browserLockManager(): RefreshLockManager | undefined {
  if (typeof navigator === 'undefined' || !navigator.locks) return undefined
  return {
    request: (name, callback) => navigator.locks.request(name, callback)
  }
}
