import { useUserStore } from '@/store/modules/user'
import { currentIdentityEpoch } from '@/utils/http/identity-scope'

export type TicketProtocol = 'WS' | 'MQTT'
export interface RealtimeTicketRequest {
  protocol: TicketProtocol
  eventTypes: ['device.property.report']
  devices: { deviceId: string; expectedModelVersionId: string; propertyKeys: string[] }[]
}
export interface RealtimeTicket {
  ticketId: string
  credential: string
  expiresAt: string
  protocol: TicketProtocol
  username?: string | null
  clientId?: string | null
  topic?: string | null
  endpoint?: string | null
  subprotocol?: string | null
  /** 只用于内存定时清理，不进入连接协议或浏览器存储。 */
  remainingMs: number
}
const uuid = /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/
export function validTicketRequest(body: RealtimeTicketRequest): boolean {
  return (
    ['WS', 'MQTT'].includes(body.protocol) &&
    body.eventTypes.length === 1 &&
    body.eventTypes[0] === 'device.property.report' &&
    body.devices.length >= 1 &&
    body.devices.length <= 20 &&
    new Set(body.devices.map((device) => device.deviceId)).size === body.devices.length &&
    body.devices.reduce((total, device) => total + device.propertyKeys.length, 0) <= 200 &&
    body.devices.every(
      (device) =>
        uuid.test(device.deviceId) &&
        uuid.test(device.expectedModelVersionId) &&
        device.propertyKeys.length >= 1 &&
        device.propertyKeys.length <= 50 &&
        device.propertyKeys.every(
          (key) => typeof key === 'string' && /^[A-Za-z0-9_-]{1,64}$/.test(key)
        ) &&
        new Set(device.propertyKeys).size === device.propertyKeys.length
    )
  )
}
const errors: Record<number, string> = {
  10001: '订阅范围无效，请重新读取设备物模型并选择属性。',
  10014: '原请求已完成，首次秘密不可重放。请明确创建新意图；旧票据仍可能占用额度。',
  80002: '当前环境未启用公开实时票据，请联系管理员确认配置。',
  80005: '共享连接或属性额度已满，请等待旧票据过期后再明确申请。',
  80006: '当前身份或设备范围已失效，请重新登录或读取当前模型。',
  80007: '实时租约暂不可用，未获得可用凭据。请稍后明确申请。'
}
/** 首次秘密不进入全局认证重放；每次显式签发只发送一次。 */
export async function issueRealtimeTicket(
  projectId: string,
  body: RealtimeTicketRequest,
  key: string,
  signal: AbortSignal
): Promise<RealtimeTicket> {
  if (!uuid.test(projectId) || !uuid.test(key) || !validTicketRequest(body))
    throw Error(errors[10001])
  const epoch = currentIdentityEpoch(),
    token = useUserStore().accessToken
  if (!token) throw Error('登录已失效，请重新登录。')
  const check = () => {
    if (signal.aborted || epoch !== currentIdentityEpoch() || token !== useUserStore().accessToken)
      throw Error('签发结果未知或身份已变化；不会自动重签。')
  }
  check()
  const started = performance.now()
  let response: Response
  try {
    response = await fetch(
      `${import.meta.env.VITE_API_URL.replace(/\/$/, '')}/api/v1/projects/${encodeURIComponent(projectId)}/realtime-tickets`,
      {
        method: 'POST',
        headers: {
          Authorization: `Bearer ${token}`,
          'Content-Type': 'application/json',
          Accept: 'application/json',
          'Idempotency-Key': key
        },
        body: JSON.stringify(body),
        credentials: 'omit',
        cache: 'no-store',
        redirect: 'error',
        signal
      }
    )
    check()
    if (response.status !== 201) {
      const error = (await response.json().catch(() => null)) as { code?: number } | null
      check()
      throw Error(
        errors[error?.code ?? 0] ??
          (response.status === 401 || response.status === 403
            ? '登录或设备读取权限已失效，请重新确认身份。'
            : '签发未完成或结果未知；不会自动重签。旧票据可能已占用额度。')
      )
    }
  } catch (error) {
    if (error instanceof TypeError || signal.aborted)
      throw Error('网络中断或超时，签发结果未知；不会自动重签。旧票据可能已占用额度。')
    throw error
  }
  let value: RealtimeTicket
  try {
    const parsed: unknown = await response.json()
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed))
      throw Error('invalid response')
    value = parsed as RealtimeTicket
  } catch {
    throw Error('首次秘密响应不完整且不可重放，请明确创建新意图。')
  }
  check()
  const serverTime = Date.parse(response.headers.get('Date') ?? '')
  const remainingMs = Math.min(
    300000,
    Date.parse(value.expiresAt) -
      (Number.isFinite(serverTime) ? serverTime + 1000 + (performance.now() - started) : Date.now())
  )
  if (
    !uuid.test(value.ticketId) ||
    value.protocol !== body.protocol ||
    typeof value.credential !== 'string' ||
    !new RegExp(`^tcrt1\\.${value.ticketId}\\.[A-Za-z0-9_-]{43}$`).test(value.credential) ||
    !Number.isFinite(remainingMs) ||
    remainingMs <= 0 ||
    (value.protocol === 'WS'
      ? value.endpoint !== '/api/open/v1/realtime/ws' || value.subprotocol !== 'tc-realtime-v1'
      : value.username !== `tc-app-v1:${value.ticketId}` ||
        value.clientId !== `tc-app-v1-${value.ticketId}` ||
        value.topic !== `tc/app/v1/${value.ticketId}/events`)
  )
    throw Error('首次秘密响应不完整、协议不匹配或已过期；不会保留或重放。')
  return { ...value, remainingMs }
}
