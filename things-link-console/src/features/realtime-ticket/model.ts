import type { RealtimeTicket, RealtimeTicketRequest } from '@/api/realtime-ticket'
export interface TicketToolState {
  busy: boolean
  attempted: boolean
  ticket: RealtimeTicket | null
  error: string
}
/** 秘密只驻留当前组件内存；关闭、身份变化和到期均销毁，不保存恢复键。 */
export function createTicketTool(ports: {
  context: () => string
  allowed: () => boolean
  key: () => string
  issue: (body: RealtimeTicketRequest, key: string, signal: AbortSignal) => Promise<RealtimeTicket>
  changed: (state: TicketToolState) => void
}) {
  let generation = 0
  let controller: AbortController | undefined
  let timer: ReturnType<typeof setTimeout> | undefined
  let state: TicketToolState = { busy: false, attempted: false, ticket: null, error: '' }
  const publish = () => ports.changed({ ...state })
  function clear() {
    generation++
    controller?.abort()
    controller = undefined
    clearTimeout(timer)
    state = { busy: false, attempted: false, ticket: null, error: '' }
    publish()
  }
  async function submit(body: RealtimeTicketRequest) {
    if (!ports.allowed() || state.busy || state.attempted) return
    const request = ++generation,
      context = ports.context()
    const active = () => generation === request && context === ports.context() && ports.allowed()
    controller = new AbortController()
    const pendingController = controller
    const signal = pendingController.signal
    state = { busy: true, attempted: true, ticket: null, error: '' }
    publish()
    const deadline = setTimeout(() => pendingController.abort(), 15000)
    try {
      const ticket = await ports.issue(body, ports.key(), signal)
      if (!active()) return
      if (signal.aborted) throw Error('签发超时，结果未知；不会自动重签。')
      state.ticket = ticket
      timer = setTimeout(() => {
        if (!active()) return
        state.ticket = null
        state.error = '票据已到期，秘密已清除；需要继续时请明确创建新意图。'
        publish()
      }, ticket.remainingMs)
    } catch (error) {
      if (active())
        state.error = error instanceof Error ? error.message : '签发结果未知，不会自动重签。'
    } finally {
      clearTimeout(deadline)
      if (active()) {
        state.busy = false
        publish()
      }
    }
  }
  return { snapshot: () => ({ ...state }), submit, clear }
}
