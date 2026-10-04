import { createDesignerReadScope, type DesignerReadScope } from './designer-read-scope'
import { currentIdentityEpoch } from '@/utils/http/identity-scope'

import type { components } from '@/types/api/schema'

type Summary = components['schemas']['DeviceAlarmStatusResponse']
/** 已完成完整性验证的生成合同投影，附本批观测时间。 */
export type DeviceAlarmStatus = Required<NonNullable<Summary['devices']>[number]> & {
  observedAt: NonNullable<Summary['observedAt']>
}
type Ticket = {
  project: string
  device: string
  identity: number
  cancelled: boolean
  resolve: (value: DeviceAlarmStatus) => void
  reject: (reason: unknown) => void
  batch?: { tickets: Ticket[]; scope: DesignerReadScope }
}
const pending: Ticket[] = []
let scheduled = false
const uuid = /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/

function decode(source: unknown, ids: string[]): DeviceAlarmStatus[] {
  const value = source as { observedAt?: unknown; devices?: unknown }
  if (
    !value ||
    typeof value.observedAt !== 'string' ||
    !Number.isFinite(Date.parse(value.observedAt)) ||
    !Array.isArray(value.devices) ||
    value.devices.length !== ids.length
  )
    throw new Error('告警摘要不完整')
  const result = value.devices as Partial<DeviceAlarmStatus>[]
  if (
    result.some(
      (item, index) =>
        !item ||
        item.deviceId !== ids[index] ||
        typeof item.modelVersionId !== 'string' ||
        !uuid.test(item.modelVersionId) ||
        (item.state !== 'ACTIVE' && item.state !== 'NORMAL')
    )
  )
    throw new Error('告警摘要身份或状态不合法')
  return result.map((item) => ({ ...item, observedAt: value.observedAt }) as DeviceAlarmStatus)
}
async function run(tickets: Ticket[]) {
  const first = tickets[0]!
  const scope = createDesignerReadScope()
  const batch = { tickets, scope }
  tickets.forEach((ticket) => {
    ticket.batch = batch
  })
  const ids = [...new Set(tickets.map((ticket) => ticket.device))]
  try {
    if (first.identity !== currentIdentityEpoch()) throw new Error('读取身份已变化')
    const query = new URLSearchParams(ids.map((id) => ['deviceId', id]))
    const source = await scope.read(
      `/api/v1/projects/${first.project}/alarms/device-status?${query}`
    )
    if (first.identity !== currentIdentityEpoch()) throw new Error('读取身份已变化')
    const result = new Map(decode(source, ids).map((item) => [item.deviceId, item]))
    tickets.forEach((ticket) => {
      if (!ticket.cancelled) ticket.resolve(result.get(ticket.device)!)
    })
  } catch (error) {
    tickets.forEach((ticket) => {
      if (!ticket.cancelled) ticket.reject(error)
    })
  } finally {
    scope.close()
  }
}
function flush() {
  scheduled = false
  const waiting = pending.splice(0).filter((ticket) => !ticket.cancelled)
  const groups = new Map<string, Ticket[]>()
  for (const ticket of waiting) {
    const key = `${ticket.identity}:${ticket.project}`
    const group = groups.get(key) ?? []
    group.push(ticket)
    groups.set(key, group)
  }
  for (const group of groups.values()) {
    for (let index = 0; index < group.length; index += 20) void run(group.slice(index, index + 20))
  }
}
/** 同一事件轮的可见行合批；只取消最后订阅者才终止共享请求，不缓存跨刷新事实。 */
export function requestDeviceAlarmStatus(project: string, device: string) {
  let ticket: Ticket
  const result = new Promise<DeviceAlarmStatus>((resolve, reject) => {
    ticket = {
      project,
      device,
      identity: currentIdentityEpoch(),
      cancelled: false,
      resolve,
      reject
    }
    if (!uuid.test(project) || !uuid.test(device)) {
      reject(new Error('设备范围不合法'))
      return
    }
    pending.push(ticket)
    if (!scheduled) {
      scheduled = true
      setTimeout(flush, 0)
    }
  })
  return {
    result,
    cancel() {
      if (ticket.cancelled) return
      ticket.cancelled = true
      ticket.reject(new Error('设备告警读取已取消'))
      if (ticket.batch?.tickets.every((item) => item.cancelled)) ticket.batch.scope.close()
    }
  }
}
