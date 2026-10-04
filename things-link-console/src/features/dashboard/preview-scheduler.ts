/** 完整校准与交互共享一个顶层任务；状态不含业务值或宿主身份。 */
export interface PreviewSchedulerState {
  readonly busy: 'idle' | 'full' | 'interaction'
  readonly latched: boolean
  readonly fullPending: boolean
  /** undefined无等待，null需预算变更通知，number为单调时钟下次资格时点。 */
  readonly waitingUntil: number | null | undefined
  readonly error?: unknown
}
type TimerHandle = ReturnType<typeof setTimeout>
export interface PreviewSchedulerPorts {
  runFull(signal: AbortSignal): Promise<void>
  eligible(kind: 'FULL' | 'INTERACTION'): { eligible: boolean; nextAvailableAt: number | null }
  subscribeBudget(callback: () => void): () => void
  onState(state: PreviewSchedulerState): void
  onInvalidate(): void
  now?: () => number
  setTimer?: (callback: () => void, delay: number) => TimerHandle
  clearTimer?: (handle: TimerHandle) => void
}
/** 尚未开始的冲突请求不消耗API预算，也不伪装网络失败。 */
export class PreviewSchedulerBusyError extends Error {
  constructor() {
    super('当前读取尚未结束，请稍后重试')
    this.name = 'PreviewSchedulerBusyError'
  }
}
/** 生命周期主动取消独立于网络失败；已发工作仍须真正退出才能释放调度槽。 */
export class PreviewSchedulerCancelledError extends Error {
  constructor() {
    super('预览读取已取消')
    this.name = 'AbortError'
  }
}
interface Interaction {
  key: string
  work(signal: AbortSignal): Promise<unknown>
  promise: Promise<unknown>
  resolve(value: unknown): void
  reject(reason: unknown): void
}
interface Running {
  kind: 'full' | 'interaction'
  controller: AbortController
  interaction?: Interaction
  cancelled: boolean
  timeoutError?: Error
  timer?: TimerHandle
}

/** 数据合同§5：只有完整成功重置60秒校准；资格等待不创建新轮，失败后自动循环锁存。 */
export function createPreviewScheduler(ports: PreviewSchedulerPorts) {
  const now = ports.now ?? (() => performance.now())
  const setTimer = ports.setTimer ?? ((callback, delay) => setTimeout(callback, delay))
  const clearTimer = ports.clearTimer ?? ((handle) => clearTimeout(handle))
  let disposed = false
  let suspended = false
  let fullPending = false
  let latched = false
  let error: unknown
  let waitingUntil: number | null | undefined
  let calibrationAt: number | undefined
  let wakeTimer: TimerHandle | undefined
  let active: Running | undefined
  let interaction: Interaction | undefined
  let pumping = false

  function publish() {
    if (!disposed)
      ports.onState(
        Object.freeze({
          busy: active?.kind ?? 'idle',
          latched,
          fullPending,
          waitingUntil,
          ...(error === undefined ? {} : { error })
        })
      )
  }
  function clearWake() {
    if (wakeTimer !== undefined) clearTimer(wakeTimer)
    wakeTimer = undefined
  }
  function wakeAt(time: number | undefined) {
    if (time === undefined || !Number.isFinite(time) || time <= now()) return
    wakeTimer = setTimer(() => {
      wakeTimer = undefined
      pump()
    }, time - now())
  }
  function cancelActive() {
    if (!active || active.cancelled) return
    active.cancelled = true
    if (active.timer !== undefined) clearTimer(active.timer)
    active.timer = undefined
    active.controller.abort(new PreviewSchedulerCancelledError())
  }
  function cancelPendingInteraction() {
    const pending = interaction
    interaction = undefined
    pending?.reject(new PreviewSchedulerCancelledError())
  }
  function prioritizeFull() {
    fullPending = true
    cancelPendingInteraction()
    if (active?.kind === 'interaction') cancelActive()
  }
  function latch(cause: unknown, full: boolean) {
    latched = true
    error = cause
    fullPending = false
    calibrationAt = undefined
    if (full) ports.onInvalidate()
  }
  function start(kind: 'full' | 'interaction', next?: Interaction) {
    const running: Running = {
      kind,
      controller: new AbortController(),
      interaction: next,
      cancelled: false
    }
    active = running
    waitingUntil = undefined
    if (kind === 'full') {
      fullPending = false
      ports.onInvalidate()
    }
    // 轮次已开始后，预算等待和实际工作共用固定30秒；超时不提前假装释放真实在途槽。
    running.timer = setTimer(() => {
      running.timer = undefined
      if (running.cancelled || disposed) return
      const failure = new Error('预览读取超过本轮30秒截止')
      failure.name = 'TimeoutError'
      running.timeoutError = failure
      latch(failure, running.kind === 'full')
      running.controller.abort(failure)
      publish()
    }, 30_000)
    publish()
    Promise.resolve()
      .then(() => {
        if (running.cancelled) throw new PreviewSchedulerCancelledError()
        return kind === 'full'
          ? ports.runFull(running.controller.signal)
          : next!.work(running.controller.signal)
      })
      .then(
        (result) => finish(running, true, result),
        (cause: unknown) => finish(running, false, cause)
      )
  }
  function finish(running: Running, success: boolean, value: unknown) {
    if (running.timer !== undefined) clearTimer(running.timer)
    active = undefined
    if (running.cancelled || disposed) {
      running.interaction?.reject(new PreviewSchedulerCancelledError())
    } else if (!success || running.timeoutError) {
      // 已观察到的超时是首因；未超时时保留原工作异常，绝不包装丢失服务端原因。
      const cause = running.timeoutError ?? value
      latch(cause, running.kind === 'full')
      running.interaction?.reject(cause)
    } else {
      if (running.kind === 'full') calibrationAt = now() + 60_000
      running.interaction?.resolve(value)
    }
    pump()
  }
  function pump() {
    if (disposed || pumping) return
    pumping = true
    try {
      clearWake()
      waitingUntil = undefined
      if (suspended) {
        publish()
        return
      }
      if (!latched && calibrationAt !== undefined && now() >= calibrationAt) {
        calibrationAt = undefined
        prioritizeFull()
      }
      if (active) {
        if (active.kind === 'interaction' && !latched) wakeAt(calibrationAt)
        publish()
        return
      }
      if (!fullPending && !interaction) {
        if (!latched) wakeAt(calibrationAt)
        publish()
        return
      }
      const eligibility = ports.eligible(fullPending ? 'FULL' : 'INTERACTION')
      if (!eligibility.eligible) {
        // 无已知未来时点只订阅通知；不每秒试探、不用过去时间建立零延迟循环。
        waitingUntil =
          eligibility.nextAvailableAt !== null && eligibility.nextAvailableAt > now()
            ? eligibility.nextAvailableAt
            : null
        const next = waitingUntil === null ? undefined : waitingUntil
        wakeAt(
          !latched && calibrationAt !== undefined ? Math.min(next ?? Infinity, calibrationAt) : next
        )
        publish()
        return
      }
      if (fullPending) start('full')
      else {
        const next = interaction!
        interaction = undefined
        start('interaction', next)
        if (!latched) wakeAt(calibrationAt)
      }
    } finally {
      pumping = false
    }
  }
  const unsubscribe = ports.subscribeBudget(pump)
  publish()
  return {
    /** 实时物理关闭等外部失败也进入同一锁存；不借取消提前释放实际工作。 */
    fail(cause: unknown) {
      if (disposed) return
      cancelActive()
      cancelPendingInteraction()
      latch(cause, true)
      pump()
    },
    /** 普通重复点击合并当前完整轮；只有明确重试才能解除已有失败锁存。 */
    requestFull(explicit = false) {
      if (disposed) return
      if (explicit) {
        latched = false
        error = undefined
      }
      if (latched || (active?.kind === 'full' && !active.cancelled && !active.timeoutError)) {
        publish()
        return
      }
      if (active?.timeoutError) cancelActive()
      prioritizeFull()
      pump()
    },
    /** 有效依赖修正换代次；取消旧轮但等待其退出，不允许旧finally与新轮并发。 */
    dependencyChanged() {
      if (disposed) return
      latched = false
      error = undefined
      calibrationAt = undefined
      cancelActive()
      prioritizeFull()
      ports.onInvalidate()
      pump()
    },
    suspend() {
      if (disposed || suspended) return
      suspended = true
      fullPending = false
      calibrationAt = undefined
      clearWake()
      cancelActive()
      cancelPendingInteraction()
      waitingUntil = undefined
      ports.onInvalidate()
      publish()
    },
    resume() {
      if (disposed || !suspended) return
      suspended = false
      if (!latched) prioritizeFull()
      pump()
    },
    /** 同key复用原Promise；不同交互拒绝忙，最多保留一个等待预算的交互。 */
    runInteraction<T>(key: string, work: (signal: AbortSignal) => Promise<T>): Promise<T> {
      if (disposed || suspended) return Promise.reject(new PreviewSchedulerCancelledError())
      const existing = active?.interaction ?? interaction
      if (existing && existing.key === key && !active?.cancelled)
        return existing.promise as Promise<T>
      if (active || interaction || fullPending)
        return Promise.reject(new PreviewSchedulerBusyError())
      let resolve!: (value: unknown) => void
      let reject!: (cause: unknown) => void
      const promise = new Promise<unknown>((yes, no) => {
        resolve = yes
        reject = no
      })
      interaction = { key, work, promise, resolve, reject }
      pump()
      return promise as Promise<T>
    },
    dispose() {
      if (disposed) return
      disposed = true
      clearWake()
      unsubscribe()
      cancelActive()
      cancelPendingInteraction()
      ports.onInvalidate()
    }
  }
}
