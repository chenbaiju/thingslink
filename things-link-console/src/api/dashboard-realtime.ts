import {
  isStrictJsonNumber,
  parseDashboardRuntimeResponse
} from '@things-link/client-contracts/dashboard/v1'
import { useUserStore } from '@/store/modules/user'
import { currentIdentityEpoch } from '@/utils/http/identity-scope'

export interface DesignerRealtimeDevice {
  readonly deviceId: string
  readonly propertyKeys: readonly string[]
}
export type DesignerRealtimeState = 'CONNECTING' | 'SUBSCRIBED' | 'REST_READY' | 'CLOSED'
export interface DesignerRealtimeOptions {
  projectId: string
  devices: readonly DesignerRealtimeDevice[]
  signal: AbortSignal
  deadline: number
  tryReserve(): boolean
  onDirty(): void
  onState(state: DesignerRealtimeState): void
  onRevoked(): void
  onFailure?(error: Error): void
  socketFactory?(url: string, protocols: string[]): WebSocket
}
export interface DesignerRealtime {
  start(): Promise<'SUBSCRIBED' | 'REST_READY'>
  takeDirty(): readonly DesignerRealtimeDevice[]
  close(deadline?: number): Promise<void>
}
export class DesignerRealtimeCloseError extends Error {
  constructor() {
    super('实时连接未在关闭截止前确认断开，不能建立替代连接')
    this.name = 'DesignerRealtimeCloseError'
  }
}
export class DesignerRealtimeCancelledError extends Error {
  constructor() {
    super('实时连接所属预览已取消或身份已变化')
    this.name = 'AbortError'
  }
}
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
const PROTOCOL = 'tc.dashboard.properties.v1'
const encoder = new TextEncoder()
function check(value: unknown): asserts value {
  if (!value) throw new Error('实时订阅或控制帧不符合合同')
}
function exact(value: unknown, keys: string[]): asserts value is Record<string, unknown> {
  check(
    value &&
      typeof value === 'object' &&
      !Array.isArray(value) &&
      Object.keys(value).sort().join(',') === [...keys].sort().join(',')
  )
}
/** 可信API配置只决定Origin；新Console协议始终用精确WS路径，不携带查询参数或凭据。 */
function socketUrl(): string {
  const base = new URL(import.meta.env.VITE_API_URL || '/', window.location.href)
  check(['http:', 'https:'].includes(base.protocol) && !base.username && !base.password)
  const url = new URL('/ws/dashboard/properties', base)
  url.protocol = base.protocol === 'https:' ? 'wss:' : 'ws:'
  return url.href
}

/** 只接Console身份和无值失效提示；降级必须先确认旧连接物理关闭，不自动重连。 */
export function createDesignerRealtime(options: DesignerRealtimeOptions): DesignerRealtime {
  check(UUID.test(options.projectId) && options.devices.length >= 1 && options.devices.length <= 20)
  const allowed = new Map<string, Set<string>>()
  let count = 0
  for (const device of options.devices) {
    check(
      UUID.test(device.deviceId) &&
        !allowed.has(device.deviceId) &&
        device.propertyKeys.length >= 1 &&
        device.propertyKeys.length <= 50
    )
    const keys = new Set(device.propertyKeys)
    check(
      keys.size === device.propertyKeys.length &&
        [...keys].every((key) => /^[A-Za-z0-9_-]{1,64}$/.test(key))
    )
    allowed.set(device.deviceId, keys)
    count += keys.size
  }
  check(count <= 200)
  const devices = [...allowed].map(([deviceId, keys]) => ({ deviceId, propertyKeys: [...keys] }))
  const requestId = crypto.randomUUID()
  const payload = JSON.stringify({
    type: 'SUBSCRIBE',
    projectId: options.projectId,
    requestId,
    devices
  })
  check(encoder.encode(payload).byteLength <= 32768)
  const identity = currentIdentityEpoch()
  const token = useUserStore().accessToken
  const factory = options.socketFactory ?? ((url, protocols) => new WebSocket(url, protocols))
  let socket: WebSocket | undefined
  let state: DesignerRealtimeState = 'CLOSED'
  let started = false
  let businessClosed = false
  let sent = false
  let subscriptionId: string | undefined
  let pendingSubscription: string | undefined
  let dirty = new Map<string, Set<string>>()
  let notified = false
  let ackTimer: ReturnType<typeof setTimeout> | undefined
  let closeTimer: ReturnType<typeof setTimeout> | undefined
  let closePromise: Promise<void> | undefined
  let closeFailed = false
  let completeClose: (() => void) | undefined
  let startPromise: Promise<'SUBSCRIBED' | 'REST_READY'> | undefined
  let resolveStart: ((value: 'SUBSCRIBED' | 'REST_READY') => void) | undefined
  let rejectStart: ((cause: unknown) => void) | undefined
  let ackDeadline = 0
  const current = () =>
    !options.signal.aborted &&
    currentIdentityEpoch() === identity &&
    !!token &&
    useUserStore().accessToken === token
  function transition(next: DesignerRealtimeState) {
    state = next
    if (current()) options.onState(next)
  }
  function clearDirty() {
    dirty.clear()
    notified = false
  }
  function clearAckTimer() {
    if (ackTimer !== undefined) clearTimeout(ackTimer)
    ackTimer = undefined
  }
  function cleanup() {
    clearAckTimer()
    if (closeTimer !== undefined) clearTimeout(closeTimer)
    closeTimer = undefined
    options.signal.removeEventListener('abort', abort)
    socket?.removeEventListener('open', opened)
    socket?.removeEventListener('message', message)
    socket?.removeEventListener('error', failed)
    socket?.removeEventListener('close', closed)
  }
  /** 已订阅连接可跨过建立轮截止；后续关闭用调用方的新轮截止或独立5秒。 */
  function physicalClose(deadline = performance.now() + 5000): Promise<void> {
    if (closePromise && !closeFailed) return closePromise
    closeFailed = false
    businessClosed = true
    clearAckTimer()
    clearDirty()
    closePromise = new Promise<void>((resolve, reject) => {
      completeClose = () => {
        completeClose = undefined
        cleanup()
        resolve()
      }
      if (!socket || socket.readyState === WebSocket.CLOSED) {
        completeClose()
        return
      }
      // 上次超时已移除监听；显式重试只再等旧连接关闭，不建立替代socket。
      socket.addEventListener('close', closed)
      const remaining = Math.max(0, Math.min(5000, deadline - performance.now()))
      closeTimer = setTimeout(() => {
        completeClose = undefined
        closeFailed = true
        cleanup()
        reject(new DesignerRealtimeCloseError())
      }, remaining)
      try {
        socket.close(1000, 'preview ended')
        if (socket.readyState === WebSocket.CLOSED) completeClose?.()
      } catch {
        // close调用异常也不能推断物理已关闭；仅观察readyState/close事件或等待硬截止。
        if (socket.readyState === WebSocket.CLOSED) completeClose?.()
      }
    })
    return closePromise
  }
  function settleStart(value: 'SUBSCRIBED' | 'REST_READY') {
    resolveStart?.(value)
    resolveStart = undefined
    rejectStart = undefined
  }
  function rejectOpening(cause: unknown) {
    rejectStart?.(cause)
    resolveStart = undefined
    rejectStart = undefined
  }
  function reportCloseFailure(cause: unknown) {
    rejectOpening(cause)
    if (current())
      options.onFailure?.(cause instanceof Error ? cause : new DesignerRealtimeCloseError())
  }
  function fallback(revoked = false) {
    if (businessClosed) return
    const deadline = state === 'CONNECTING' ? options.deadline : performance.now() + 5000
    const reportRevoked = revoked && current()
    if (reportRevoked) options.onRevoked()
    void physicalClose(deadline).then(() => {
      if (!current()) {
        rejectOpening(new DesignerRealtimeCancelledError())
        return
      }
      transition('REST_READY')
      settleStart('REST_READY')
    }, reportCloseFailure)
  }
  function abort() {
    // 先围栏业务回调；close事件不受身份围栏阻挡，仍用于物理关闭证明。
    void physicalClose(state === 'CONNECTING' ? options.deadline : performance.now() + 5000).then(
      () => rejectOpening(new DesignerRealtimeCancelledError()),
      reportCloseFailure
    )
  }
  function guard(): boolean {
    if (businessClosed) return false
    if (!current()) {
      abort()
      return false
    }
    return true
  }
  function opened() {
    if (!guard()) return
    if (
      state !== 'CONNECTING' ||
      sent ||
      performance.now() >= ackDeadline ||
      socket?.protocol !== PROTOCOL
    ) {
      fallback()
      return
    }
    try {
      sent = true
      socket.send(payload)
    } catch {
      fallback()
    }
  }
  function message(event: MessageEvent) {
    if (!guard()) return
    try {
      check(sent && typeof event.data === 'string' && event.data.length <= 32768)
      const bytes = encoder.encode(event.data)
      check(bytes.byteLength <= 32768)
      const value = parseDashboardRuntimeResponse(bytes)
      check(
        value && typeof value === 'object' && !Array.isArray(value) && !isStrictJsonNumber(value)
      )
      if (value.type === 'SUBSCRIBED') {
        exact(value, ['type', 'requestId', 'subscriptionId', 'count'])
        check(
          state === 'CONNECTING' &&
            performance.now() < ackDeadline &&
            value.requestId === requestId &&
            typeof value.subscriptionId === 'string' &&
            UUID.test(value.subscriptionId) &&
            isStrictJsonNumber(value.count) &&
            value.count.lexical === String(count) &&
            (!pendingSubscription || pendingSubscription === value.subscriptionId)
        )
        subscriptionId = value.subscriptionId
        clearAckTimer()
        transition('SUBSCRIBED')
        if (!guard()) return
        settleStart('SUBSCRIBED')
        if (dirty.size && !notified) {
          notified = true
          options.onDirty()
        }
        return
      }
      exact(value, ['type', 'subscriptionId', 'devices'])
      check(
        value.type === 'INVALIDATE' &&
          typeof value.subscriptionId === 'string' &&
          UUID.test(value.subscriptionId) &&
          (subscriptionId
            ? value.subscriptionId === subscriptionId
            : !pendingSubscription || pendingSubscription === value.subscriptionId) &&
          Array.isArray(value.devices) &&
          value.devices.length >= 1 &&
          value.devices.length <= allowed.size
      )
      // ACK之前只保留待核的有界键集合；ACK必须匹配该subscription，不提前触发业务读取。
      if (!subscriptionId) pendingSubscription = value.subscriptionId
      const incoming = new Map<string, Set<string>>()
      let incomingCount = 0
      for (const item of value.devices) {
        exact(item, ['deviceId', 'propertyKeys'])
        check(
          typeof item.deviceId === 'string' &&
            allowed.has(item.deviceId) &&
            !incoming.has(item.deviceId) &&
            Array.isArray(item.propertyKeys) &&
            item.propertyKeys.length >= 1 &&
            item.propertyKeys.length <= 50
        )
        const keys = new Set<string>()
        for (const key of item.propertyKeys) {
          check(typeof key === 'string' && allowed.get(item.deviceId)!.has(key) && !keys.has(key))
          keys.add(key)
          incomingCount++
        }
        incoming.set(item.deviceId, keys)
      }
      check(incomingCount <= 200)
      for (const [deviceId, keys] of incoming) {
        const pending = dirty.get(deviceId) ?? new Set<string>()
        keys.forEach((key) => pending.add(key))
        dirty.set(deviceId, pending)
      }
      if (state === 'SUBSCRIBED' && !notified) {
        notified = true
        options.onDirty()
      }
    } catch {
      fallback()
    }
  }
  function failed() {
    if (guard()) fallback()
  }
  function closed(event: CloseEvent) {
    if (completeClose) {
      completeClose()
      return
    }
    if (businessClosed) return
    if (!current()) {
      abort()
      return
    }
    fallback(event.code === 1008)
  }
  return {
    start(): Promise<'SUBSCRIBED' | 'REST_READY'> {
      if (startPromise) return startPromise
      startPromise = new Promise((resolve, reject) => {
        resolveStart = resolve
        rejectStart = reject
      })
      if (businessClosed || !current()) {
        rejectOpening(new DesignerRealtimeCancelledError())
        return startPromise
      }
      started = true
      if (performance.now() >= options.deadline || !options.tryReserve()) {
        transition('REST_READY')
        settleStart('REST_READY')
        return startPromise
      }
      ackDeadline = Math.min(options.deadline, performance.now() + 5000)
      transition('CONNECTING')
      if (businessClosed || !current()) {
        abort()
        return startPromise
      }
      options.signal.addEventListener('abort', abort, { once: true })
      ackTimer = setTimeout(() => fallback(), Math.max(0, ackDeadline - performance.now()))
      try {
        socket = factory(socketUrl(), [PROTOCOL, `bearer.${token}`])
        socket.addEventListener('open', opened)
        socket.addEventListener('message', message)
        socket.addEventListener('error', failed)
        socket.addEventListener('close', closed)
      } catch {
        fallback()
      }
      return startPromise
    },
    takeDirty(): readonly DesignerRealtimeDevice[] {
      if (!guard() || state !== 'SUBSCRIBED') return []
      const taken = dirty
      dirty = new Map()
      notified = false
      return Object.freeze(
        [...taken].map(([deviceId, keys]) =>
          Object.freeze({ deviceId, propertyKeys: Object.freeze([...keys]) })
        )
      )
    },
    close(deadline = performance.now() + 5000): Promise<void> {
      const result = physicalClose(deadline)
      void result.then(() => {
        if (started) transition('CLOSED')
        rejectOpening(new DesignerRealtimeCancelledError())
      }, reportCloseFailure)
      return result
    }
  }
}
