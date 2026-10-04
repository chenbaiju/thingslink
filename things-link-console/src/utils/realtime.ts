import { currentIdentityEpoch } from '@/utils/http/identity-scope'

/** 旧设备详情实时增量；接受序号与模型来源按属性传递。 */
export interface PropertyBatchMessage {
  type: 'PROPERTY_BATCH'
  projectId: string
  deviceId: string
  occurredAt: string
  shadowVersion: number
  properties: Record<string, unknown>
  reportedRevisions?: Record<string, string>
  thingModelVersionIds?: Record<string, string>
}
interface RealtimeClientOptions {
  accessToken: () => string
  onBatch: (message: PropertyBatchMessage) => void
  onConnected: (reconnected: boolean) => Promise<void> | void
  onStatus?: (status: 'connecting' | 'connected' | 'disconnected' | 'quota_exceeded') => void
  socketFactory?: (url: string, protocols: string[]) => WebSocket
}
const record = (value: unknown): value is Record<string, unknown> =>
  value !== null && typeof value === 'object' && !Array.isArray(value)

/** 每次订阅建立独立会话，所有回调受连接实例和身份代次围栏。ACK 不是数据水位。 */
export class RealtimeClient {
  private socket?: WebSocket
  private reconnectTimer?: ReturnType<typeof setTimeout>
  private ackTimer?: ReturnType<typeof setTimeout>
  private stopped = true
  private generation = 0
  private reconnectCount = 0
  private openedOnce = false
  private subscriptions: Array<{ deviceId: string; propertyKeys: string[] }> = []
  constructor(private readonly options: RealtimeClientOptions) {}

  /** 更换订阅不复用旧连接，避免没有 subscriptionId 的旧属性帧进入新会话。 */
  connect(subscriptions: Array<{ deviceId: string; propertyKeys: string[] }>) {
    this.close()
    this.subscriptions = subscriptions.map((item) => ({
      deviceId: item.deviceId,
      propertyKeys: [...new Set(item.propertyKeys)]
    }))
    this.stopped = false
    this.open()
  }
  /** 主动关闭立即使业务回调失效；旧 close 不能清掉新 socket。 */
  close() {
    this.stopped = true
    this.generation++
    if (this.reconnectTimer) clearTimeout(this.reconnectTimer)
    if (this.ackTimer) clearTimeout(this.ackTimer)
    this.reconnectTimer = this.ackTimer = undefined
    const previous = this.socket
    this.socket = undefined
    previous?.close(1000, 'detail closed')
    this.options.onStatus?.('disconnected')
  }
  private open() {
    const token = this.options.accessToken()
    if (!token || this.stopped) return
    const generation = ++this.generation,
      identity = currentIdentityEpoch()
    const scheme = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
    const basePath = String(import.meta.env.VITE_API_URL || '').replace(/^https?:\/\/[^/]+/, '')
    const path = `${basePath.replace(/\/$/, '')}/api/v1/realtime/ws`.replace(/\/+/g, '/')
    const socket = (
      this.options.socketFactory ?? ((url, protocols) => new WebSocket(url, protocols))
    )(`${scheme}//${window.location.host}${path}`, ['tc-v1', `bearer.${token}`])
    this.socket = socket
    const active = () =>
      !this.stopped &&
      this.socket === socket &&
      this.generation === generation &&
      currentIdentityEpoch() === identity
    const requestId = crypto.randomUUID()
    const count = this.subscriptions.reduce((total, item) => total + item.propertyKeys.length, 0)
    let acknowledged = false
    this.options.onStatus?.('connecting')
    this.ackTimer = setTimeout(() => {
      if (active() && !acknowledged) socket.close(4000, 'subscription timeout')
    }, 5000)
    socket.onopen = () => {
      if (!active()) return
      if (socket.protocol !== 'tc-v1') {
        socket.close(1000, 'protocol mismatch')
        return
      }
      socket.send(
        JSON.stringify({ type: 'SUBSCRIBE', requestId, subscriptions: this.subscriptions })
      )
    }
    socket.onmessage = (event) => {
      if (!active() || typeof event.data !== 'string') return
      try {
        const message: unknown = JSON.parse(event.data)
        if (!record(message)) return
        if (message.type === 'SUBSCRIBED') {
          if (
            acknowledged ||
            message.requestId !== requestId ||
            typeof message.subscriptionId !== 'string' ||
            !/^[0-9a-f-]{36}$/i.test(message.subscriptionId) ||
            message.subscriptionCount !== count
          )
            return
          acknowledged = true
          if (this.ackTimer) clearTimeout(this.ackTimer)
          this.ackTimer = undefined
          const reconnected = this.openedOnce
          this.openedOnce = true
          this.reconnectCount = 0
          this.options.onStatus?.('connected')
          // ACK 前增量要求这次 PG 权威补拉恢复；读取失败不宣称已恢复，也不形成未处理的 Promise。
          Promise.resolve(this.options.onConnected(reconnected)).catch(() => {
            if (active()) this.options.onStatus?.('disconnected')
          })
        } else if (
          acknowledged &&
          message.type === 'PROPERTY_BATCH' &&
          typeof message.projectId === 'string' &&
          typeof message.deviceId === 'string' &&
          typeof message.occurredAt === 'string' &&
          record(message.properties) &&
          (message.reportedRevisions === undefined || record(message.reportedRevisions)) &&
          (message.thingModelVersionIds === undefined || record(message.thingModelVersionIds))
        ) {
          const subscribed = this.subscriptions.find((item) => item.deviceId === message.deviceId)
          if (
            !subscribed ||
            Object.keys(message.properties).some((key) => !subscribed.propertyKeys.includes(key))
          )
            return
          this.options.onBatch(message as unknown as PropertyBatchMessage)
        }
      } catch {
        /* 非 JSON 帧不取得事实资格。 */
      }
    }
    socket.onclose = (event) => {
      if (!active()) return
      this.socket = undefined
      if (this.ackTimer) clearTimeout(this.ackTimer)
      this.ackTimer = undefined
      const quotaExceeded =
        event.code === 1008 && event.reason === 'tenant connection quota exceeded'
      this.options.onStatus?.(quotaExceeded ? 'quota_exceeded' : 'disconnected')
      if (event.code !== 1000 && event.code !== 1008) this.scheduleReconnect(identity)
    }
    socket.onerror = () => {
      if (active()) socket.close()
    }
  }
  private scheduleReconnect(identity: number) {
    if (this.reconnectTimer || this.stopped) return
    const delay = Math.min(1000 * 2 ** this.reconnectCount, 30_000) + Math.random() * 500
    this.reconnectCount++
    this.reconnectTimer = setTimeout(() => {
      this.reconnectTimer = undefined
      if (!this.stopped && currentIdentityEpoch() === identity) this.open()
    }, delay)
  }
}
