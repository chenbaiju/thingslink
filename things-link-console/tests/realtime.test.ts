import { beforeEach, afterEach, expect, it, vi } from 'vitest'
const identity = vi.hoisted(() => ({ epoch: 1 }))
vi.mock('@/utils/http/identity-scope', () => ({ currentIdentityEpoch: () => identity.epoch }))
import { RealtimeClient } from '../src/utils/realtime'
class Socket {
  protocol = 'tc-v1'
  send = vi.fn<(data: string) => void>()
  close = vi.fn()
  onopen?: () => void
  onmessage?: (event: { data: string }) => void
  onclose?: (event: { code: number; reason: string }) => void
  onerror?: () => void
  frame(data: unknown) {
    this.onmessage?.({ data: JSON.stringify(data) })
  }
  ack(overrides = {}) {
    this.frame({
      type: 'SUBSCRIBED',
      requestId: JSON.parse(this.send.mock.calls[0]![0]).requestId,
      subscriptionId: '11111111-1111-4111-8111-111111111111',
      subscriptionCount: 1,
      ...overrides
    })
  }
}
function fixture() {
  const sockets: Socket[] = []
  const onBatch = vi.fn(),
    onConnected = vi.fn(),
    onStatus = vi.fn()
  const factory = vi.fn(() => {
    const socket = new Socket()
    sockets.push(socket)
    return socket as unknown as WebSocket
  })
  const client = new RealtimeClient({
    accessToken: () => 'test-token',
    onBatch,
    onConnected,
    onStatus,
    socketFactory: factory
  })
  const connect = () => client.connect([{ deviceId: 'device', propertyKeys: ['temperature'] }])
  connect()
  return { client, sockets, onBatch, onConnected, onStatus, factory, connect }
}
const batch = {
  type: 'PROPERTY_BATCH',
  projectId: 'project',
  deviceId: 'device',
  properties: { temperature: 1 },
  occurredAt: '2026-09-12T00:00:00Z',
  shadowVersion: 0
}
beforeEach(() => {
  identity.epoch = 1
  vi.useFakeTimers()
})
afterEach(() => vi.useRealTimers())
it('只在关联 ACK 后补拉，旧消息缺序号仍交由状态模型降级', () => {
  const f = fixture(),
    socket = f.sockets[0]!
  socket.onopen?.()
  socket.frame(batch)
  expect(f.onBatch).not.toHaveBeenCalled()
  expect(f.onConnected).not.toHaveBeenCalled()
  socket.ack({ requestId: 'wrong' })
  socket.ack({ subscriptionCount: 2 })
  expect(f.onConnected).not.toHaveBeenCalled()
  socket.ack()
  socket.ack()
  socket.frame(batch)
  expect(f.onConnected).toHaveBeenCalledTimes(1)
  expect(f.onBatch).toHaveBeenCalledTimes(1)
  expect(f.factory.mock.calls[0]?.length).toBe(2)
  f.client.close()
})
it('更换订阅后迟到 ACK、属性和 close 不污染新连接', () => {
  const f = fixture(),
    old = f.sockets[0]!
  old.onopen?.()
  f.connect()
  const next = f.sockets[1]!
  next.onopen?.()
  next.ack()
  old.ack()
  old.frame(batch)
  old.onclose?.({ code: 1012, reason: '' })
  vi.advanceTimersByTime(4000)
  expect(f.sockets).toHaveLength(2)
  expect(f.onConnected).toHaveBeenCalledTimes(1)
  next.frame(batch)
  expect(f.onBatch).toHaveBeenCalledTimes(1)
  f.client.close()
})
it('身份切换拒绝旧回调和重连', () => {
  const f = fixture(),
    socket = f.sockets[0]!
  socket.onopen?.()
  identity.epoch++
  socket.ack()
  socket.frame(batch)
  socket.onclose?.({ code: 1012, reason: '' })
  vi.advanceTimersByTime(6000)
  expect(f.onConnected).not.toHaveBeenCalled()
  expect(f.onBatch).not.toHaveBeenCalled()
  expect(f.sockets).toHaveLength(1)
  f.client.close()
})
it('ACK 超时和协议不匹配不会展示已连接', () => {
  const f = fixture(),
    socket = f.sockets[0]!
  socket.protocol = 'other'
  socket.onopen?.()
  expect(socket.send).not.toHaveBeenCalled()
  vi.advanceTimersByTime(5000)
  expect(socket.close).toHaveBeenCalled()
  expect(f.onConnected).not.toHaveBeenCalled()
  f.client.close()
})
it('取消后不重连，非订阅属性不能进入回调', () => {
  const f = fixture(),
    socket = f.sockets[0]!
  socket.onopen?.()
  socket.ack()
  socket.frame({ ...batch, properties: { secret: 1 } })
  expect(f.onBatch).not.toHaveBeenCalled()
  f.client.close()
  socket.onclose?.({ code: 1012, reason: '' })
  vi.advanceTimersByTime(6000)
  expect(f.sockets).toHaveLength(1)
})

it('暂态 ACK 超时在当前身份内退避重连，永久协议不匹配仍停止', () => {
  const f = fixture(),
    first = f.sockets[0]!
  first.onopen?.()
  vi.advanceTimersByTime(5000)
  expect(first.close).toHaveBeenCalledWith(4000, 'subscription timeout')
  first.onclose?.({ code: 4000, reason: 'subscription timeout' })
  vi.advanceTimersByTime(1500)
  expect(f.sockets).toHaveLength(2)
  const second = f.sockets[1]!
  second.onopen?.()
  second.ack()
  expect(f.onConnected).toHaveBeenCalledTimes(1)
  f.client.close()
})
