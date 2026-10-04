import {
  parseDashboardRuntimeResponse,
  isStrictJsonNumber
} from '@things-link/client-contracts/dashboard/v1'
import { useUserStore } from '@/store/modules/user'
import { currentIdentityEpoch } from '@/utils/http/identity-scope'

export type DesignerReadKind = 'FULL' | 'INTERACTION'
const RESPONSE_BYTES = 4 * 1024 * 1024
const TOTAL_BYTES = 64 * 1024 * 1024
const INTERACTION_BYTES = 56 * 1024 * 1024
/** 标签单例不随轮次、身份、隐藏或取消重置。 */
const requestTimes: { at: number; interaction: boolean }[] = []
/** 每秒合并真实接收量，最多61桶，不按chunk无限建对象。 */
const receivedBuckets = new Map<number, { total: number; interaction: number }>()
let reservedBytes = 0
let interactionReservedBytes = 0
let inFlight = 0
let currentInFlight = 0
let retryAfter = 0
/** WS升级独立计数，关闭、换代次或失败均不退还已保留握手。 */
const handshakeTimes: number[] = []
const listeners = new Set<() => void>()
/** 容量释放事件只唤醒资格复核，不替订阅方发请求。 */
export function subscribeDesignerReadBudget(listener: () => void): () => void {
  listeners.add(listener)
  return () => {
    listeners.delete(listener)
  }
}
function changed() {
  for (const listener of [...listeners]) listener()
}
function prune(now: number) {
  while (requestTimes.length && requestTimes[0]!.at <= now - 60_000) requestTimes.shift()
  for (const second of receivedBuckets.keys())
    if ((second + 1) * 1000 <= now - 60_000) receivedBuckets.delete(second)
}
function received(interaction: boolean) {
  return [...receivedBuckets.values()].reduce(
    (sum, bucket) => sum + (interaction ? bucket.interaction : bucket.total),
    0
  )
}
/** 未知在途释放时间返回null；已知窗口边界用绝对单调时钟，禁止开轮试探额度。 */
export function designerReadEligibility(
  kind: DesignerReadKind,
  options: { current?: boolean } = {}
): { eligible: boolean; nextAvailableAt: number | null } {
  const now = performance.now()
  prune(now)
  let next = Math.max(now, retryAfter)
  let unknown = inFlight >= 4 || (!!options.current && currentInFlight >= 1)
  const countAt = (entries: typeof requestTimes, maximum: number, duration: number) => {
    if (entries.length >= maximum)
      next = Math.max(next, entries[entries.length - maximum]!.at + duration)
  }
  countAt(
    requestTimes.filter((entry) => entry.at > now - 1000),
    4,
    1000
  )
  countAt(requestTimes, 120, 60_000)
  if (kind === 'INTERACTION')
    countAt(
      requestTimes.filter((entry) => entry.interaction),
      100,
      60_000
    )
  const bytesAt = (interaction: boolean, maximum: number, reserved: number) => {
    let total = received(interaction) + reserved + RESPONSE_BYTES
    if (total <= maximum) return
    for (const [second, bucket] of [...receivedBuckets].sort(([a], [b]) => a - b)) {
      total -= interaction ? bucket.interaction : bucket.total
      if (total <= maximum) {
        next = Math.max(next, (second + 1) * 1000 + 60_000)
        return
      }
    }
    unknown = true
  }
  bytesAt(false, TOTAL_BYTES, reservedBytes)
  if (kind === 'INTERACTION') bytesAt(true, INTERACTION_BYTES, interactionReservedBytes)
  return { eligible: !unknown && next <= now, nextAvailableAt: unknown ? null : next }
}
function reserveRequest(kind: DesignerReadKind, options: { current?: boolean }) {
  if (!designerReadEligibility(kind, options).eligible) return undefined
  const interaction = kind === 'INTERACTION'
  const current = options.current === true
  requestTimes.push({ at: performance.now(), interaction })
  inFlight++
  if (current) currentInFlight++
  reservedBytes += RESPONSE_BYTES
  if (interaction) interactionReservedBytes += RESPONSE_BYTES
  let remaining = RESPONSE_BYTES
  let released = false
  changed()
  return {
    received(count: number) {
      const now = performance.now()
      prune(now)
      const second = Math.floor(now / 1000)
      const bucket = receivedBuckets.get(second) ?? { total: 0, interaction: 0 }
      bucket.total += count
      if (interaction) bucket.interaction += count
      receivedBuckets.set(second, bucket)
      const consumed = Math.min(remaining, count)
      remaining -= consumed
      reservedBytes -= consumed
      if (interaction) interactionReservedBytes -= consumed
      if (
        count > consumed ||
        received(false) + reservedBytes > TOTAL_BYTES ||
        (interaction && received(true) + interactionReservedBytes > INTERACTION_BYTES)
      )
        throw new Error('读取字节超过滑动窗口预算')
    },
    release() {
      if (released) return
      released = true
      reservedBytes -= remaining
      if (interaction) interactionReservedBytes -= remaining
      inFlight--
      if (current) currentInFlight--
      changed()
    }
  }
}

/** 一轮显式草稿读取；不隐式刷新认证，401终止本轮，避免脱离预算重放。 */
export function createDesignerReadScope(
  options: {
    kind?: DesignerReadKind
    onWait?: (nextAvailableAt: number | null | undefined) => void
  } = {}
) {
  const kind = options.kind ?? 'INTERACTION'
  const identity = currentIdentityEpoch()
  const token = useUserStore().accessToken
  const controller = new AbortController()
  const deadline = performance.now() + 30_000
  const timer = setTimeout(() => controller.abort(), 30_000)
  let bytes = 0
  let attempts = 0
  let handshakeReserved = false
  const check = () => {
    if (
      controller.signal.aborted ||
      performance.now() >= deadline ||
      currentIdentityEpoch() !== identity ||
      useUserStore().accessToken !== token ||
      !token
    ) {
      controller.abort()
      throw new Error('读取已取消或登录状态已变化')
    }
  }
  const pace = async (requestOptions: { current?: boolean } = {}) => {
    try {
      for (;;) {
        check()
        const eligibility = designerReadEligibility(kind, requestOptions)
        if (eligibility.eligible) return
        options.onWait?.(eligibility.nextAvailableAt)
        if (eligibility.nextAvailableAt !== null && eligibility.nextAvailableAt >= deadline)
          throw new Error('读取等待超过本轮截止')
        await new Promise<void>((resolve, reject) => {
          let wait: ReturnType<typeof setTimeout> | undefined
          const cleanup = () => {
            if (wait !== undefined) clearTimeout(wait)
            unsubscribe()
            controller.signal.removeEventListener('abort', cancel)
          }
          const wake = () => {
            cleanup()
            resolve()
          }
          const cancel = () => {
            cleanup()
            reject(new Error('读取已取消'))
          }
          const unsubscribe = subscribeDesignerReadBudget(wake)
          controller.signal.addEventListener('abort', cancel, { once: true })
          if (eligibility.nextAvailableAt !== null)
            wait = setTimeout(
              wake,
              Math.max(1, Math.ceil(eligibility.nextAvailableAt - performance.now()))
            )
        })
      }
    } finally {
      options.onWait?.(undefined)
    }
  }
  return {
    /** 同一轮准备、旧连接关闭及握手共用原单调截止，调用方不能重新计时。 */
    get deadline(): number {
      return deadline
    },
    tryReserveWebSocket(): boolean {
      check()
      const now = performance.now()
      while (handshakeTimes.length && handshakeTimes[0]! <= now - 60_000) handshakeTimes.shift()
      if (handshakeReserved || handshakeTimes.length >= 4) return false
      handshakeReserved = true
      handshakeTimes.push(now)
      return true
    },
    close() {
      clearTimeout(timer)
      controller.abort()
    },
    pace,
    async read(
      path: string,
      body?: unknown,
      requestOptions: { current?: boolean } = {}
    ): Promise<unknown> {
      let reservation: ReturnType<typeof reserveRequest>
      for (;;) {
        await pace(requestOptions)
        check()
        if (attempts >= 20) throw new Error('读取次数超过预算')
        reservation = reserveRequest(kind, requestOptions)
        if (reservation) {
          attempts++
          break
        }
        // 同次唤醒竞争额度时重新等待事件/时间边界，不发请求试探、不忙轮询。
      }
      try {
        // 路由由本模块API常量构造，不允许重定向带走Console凭据。
        const response = await fetch(`${import.meta.env.VITE_API_URL.replace(/\/$/, '')}${path}`, {
          method: body === undefined ? 'GET' : 'POST',
          headers: {
            Authorization: `Bearer ${token}`,
            Accept: 'application/json',
            ...(body === undefined ? {} : { 'Content-Type': 'application/json' })
          },
          body: body === undefined ? undefined : JSON.stringify(body),
          signal: controller.signal,
          cache: 'no-store',
          credentials: 'omit',
          redirect: 'error'
        })
        // 响应头先登记跨轮等待，即使原轮已过期也不退款服务端限流期限。
        if (response.status === 429) {
          const raw = response.headers.get('Retry-After')
          const delay =
            raw && /^\d+$/.test(raw)
              ? Number(raw) * 1000
              : raw
                ? Math.max(0, Date.parse(raw) - Date.now())
                : 0
          if (Number.isFinite(delay)) {
            retryAfter = Math.max(retryAfter, performance.now() + delay)
            changed()
          }
        }
        check()
        // 明确失权/项目不可见在响应头阶段清理，不能等错误正文缓慢结束才撤掉旧事实。
        if ([401, 403, 404].includes(response.status)) {
          controller.abort()
          await response.body?.cancel().catch(() => undefined)
          throw Object.assign(
            new Error(
              response.status === 401 ? '登录已失效，请重新登录后重试' : '设备读取权限已变化'
            ),
            { status: response.status }
          )
        }
        const reader = response.body?.getReader()
        if (!reader) throw new Error('响应正文缺失')
        const chunks: Uint8Array[] = []
        let size = 0
        try {
          for (;;) {
            const chunk = await reader.read()
            if (chunk.done) {
              check()
              break
            }
            // 先记录收到的真实字节，取消/换身份也不能把在途正文退款。
            try {
              reservation.received(chunk.value.byteLength)
            } catch (error) {
              controller.abort()
              throw error
            }
            check()
            size += chunk.value.byteLength
            bytes += chunk.value.byteLength
            if (size > 4 * 1024 * 1024 || bytes > 8 * 1024 * 1024) {
              controller.abort()
              throw new Error('读取字节超过预算')
            }
            chunks.push(chunk.value)
          }
        } finally {
          await reader.cancel().catch(() => undefined)
          reader.releaseLock()
        }
        check()
        if (
          !response.ok &&
          (response.status !== 400 ||
            !response.headers.get('content-type')?.includes('application/json'))
        )
          throw Object.assign(new Error('设备读取被拒绝或服务不可用'), { status: response.status })
        if (!response.headers.get('content-type')?.includes('application/json'))
          throw new Error('响应类型错误')
        const data = new Uint8Array(size)
        let offset = 0
        for (const chunk of chunks) {
          data.set(chunk, offset)
          offset += chunk.byteLength
        }
        const parsed = parseDashboardRuntimeResponse(data)
        check()
        if (!response.ok) {
          const code =
            parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed.code : undefined
          const businessCode =
            code !== undefined &&
            isStrictJsonNumber(code) &&
            ['10001', '30058'].includes(code.lexical)
              ? Number(code.lexical)
              : undefined
          throw Object.assign(new Error('设备读取被拒绝或服务不可用'), {
            status: response.status,
            ...(response.status === 400 && businessCode ? { code: businessCode } : {})
          })
        }
        return parsed
      } finally {
        reservation.release()
      }
    }
  }
}
export type DesignerReadScope = ReturnType<typeof createDesignerReadScope>
