import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
const identity = vi.hoisted(() => ({ epoch: 1, token: 'console-test-token' }))
vi.mock('@/store/modules/user', () => ({ useUserStore: () => ({ accessToken: identity.token }) }))
vi.mock('@/utils/http/identity-scope', () => ({ currentIdentityEpoch: () => identity.epoch }))
import {
  createDesignerRealtime,
  DesignerRealtimeCloseError,
  DesignerRealtimeCancelledError,
  type DesignerRealtimeDevice
} from '@/api/dashboard-realtime'
const projectId = '11111111-1111-4111-8111-111111111111'
const deviceId = '22222222-2222-4222-8222-222222222222'
const subscriptionId = '33333333-3333-4333-8333-333333333333'
const otherId = '44444444-4444-4444-8444-444444444444'
const protocol = 'tc.dashboard.properties.v1'
class Socket extends EventTarget {
  readyState = WebSocket.CONNECTING as number
  protocol = protocol
  send = vi.fn<(data: string) => void>()
  close = vi.fn(() => {
    this.readyState = WebSocket.CLOSING
  })
  open() {
    this.readyState = WebSocket.OPEN
    this.dispatchEvent(new Event('open'))
  }
  frame(value: unknown) {
    this.raw(JSON.stringify(value))
  }
  raw(data: unknown) {
    this.dispatchEvent(new MessageEvent('message', { data }))
  }
  closed(code = 1000) {
    this.readyState = WebSocket.CLOSED
    this.dispatchEvent(new CloseEvent('close', { code }))
  }
}
function fixture(
  devices: readonly DesignerRealtimeDevice[] = [
    { deviceId, propertyKeys: ['temperature', 'phase-a', '1st'] }
  ]
) {
  const socket = new Socket()
  const signal = new AbortController()
  const onState = vi.fn(),
    onDirty = vi.fn(),
    onRevoked = vi.fn(),
    onFailure = vi.fn()
  const factory = vi.fn(() => socket as unknown as WebSocket)
  const tryReserve = vi.fn(() => true)
  const realtime = createDesignerRealtime({
    projectId,
    devices,
    signal: signal.signal,
    deadline: 30_000,
    tryReserve,
    onState,
    onDirty,
    onRevoked,
    onFailure,
    socketFactory: factory
  })
  const ack = (changes = {}) => {
    const request = JSON.parse(socket.send.mock.calls[0]![0]) as { requestId: string }
    socket.frame({
      type: 'SUBSCRIBED',
      requestId: request.requestId,
      subscriptionId,
      count: devices.reduce((n, d) => n + d.propertyKeys.length, 0),
      ...changes
    })
  }
  const hint = (changes = {}) =>
    socket.frame({
      type: 'INVALIDATE',
      subscriptionId,
      devices: [{ deviceId, propertyKeys: ['temperature'] }],
      ...changes
    })
  return {
    realtime,
    socket,
    signal,
    factory,
    onState,
    onDirty,
    onRevoked,
    onFailure,
    tryReserve,
    ack,
    hint
  }
}
async function flush() {
  await Promise.resolve()
  await Promise.resolve()
  await Promise.resolve()
}
beforeEach(() => {
  vi.useFakeTimers()
  vi.setSystemTime(0)
  vi.spyOn(performance, 'now').mockImplementation(() => Date.now())
  vi.stubEnv('VITE_API_URL', 'https://console.example.test/api/')
  identity.epoch = 1
  identity.token = 'console-test-token'
})
afterEach(() => {
  vi.clearAllTimers()
  vi.useRealTimers()
  vi.restoreAllMocks()
  vi.unstubAllEnvs()
})

describe('Console实时订阅身份与ACK', () => {
  it('精确WS路径和子协议传Console凭据，唯一SUBSCRIBE无App上下文', async () => {
    const f = fixture()
    const started = f.realtime.start()
    expect(f.realtime.start()).toBe(started)
    expect(f.factory).toHaveBeenCalledWith('wss://console.example.test/ws/dashboard/properties', [
      protocol,
      'bearer.console-test-token'
    ])
    f.socket.open()
    const sent = JSON.parse(f.socket.send.mock.calls[0]![0])
    expect(Object.keys(sent).sort()).toEqual(['devices', 'projectId', 'requestId', 'type'])
    expect(sent).toMatchObject({
      type: 'SUBSCRIBE',
      projectId,
      devices: [{ deviceId, propertyKeys: ['temperature', 'phase-a', '1st'] }]
    })
    f.ack()
    expect(await started).toBe('SUBSCRIBED')
    expect(f.tryReserve).toHaveBeenCalledTimes(1)
    expect(f.socket.send).toHaveBeenCalledTimes(1)
    const closed = f.realtime.close()
    f.socket.closed()
    await closed
  })
  it.each(['wrong-request', 'string-count', 'float-count', 'extra-field', 'wrong-subscription'])(
    '坏ACK %s 必须确认关闭才允许REST',
    async (variant) => {
      const f = fixture()
      const started = f.realtime.start()
      let result: string | undefined
      void started.then((value) => {
        result = value
      })
      f.socket.open()
      if (variant === 'float-count') {
        const request = JSON.parse(f.socket.send.mock.calls[0]![0])
        f.socket.raw(
          JSON.stringify({
            type: 'SUBSCRIBED',
            requestId: request.requestId,
            subscriptionId,
            count: 3
          }).replace('"count":3', '"count":3.0')
        )
      } else
        f.ack(
          variant === 'wrong-request'
            ? { requestId: 'other' }
            : variant === 'string-count'
              ? { count: '3' }
              : variant === 'extra-field'
                ? { privateValue: 1 }
                : { subscriptionId: 'bad' }
        )
      await flush()
      expect(result).toBeUndefined()
      expect(f.socket.close).toHaveBeenCalledTimes(1)
      expect(f.onState).not.toHaveBeenCalledWith('REST_READY')
      f.socket.closed()
      expect(await started).toBe('REST_READY')
    }
  )
  it('回选bearer而非业务子协议不发订阅，不能泄露凭据进payload', async () => {
    const f = fixture()
    const started = f.realtime.start()
    f.socket.protocol = 'bearer.console-test-token'
    f.socket.open()
    expect(f.socket.send).not.toHaveBeenCalled()
    f.socket.closed()
    expect(await started).toBe('REST_READY')
  })
  it('无握手预算直接REST且不创建socket', async () => {
    const f = fixture()
    f.tryReserve.mockReturnValue(false)
    expect(await f.realtime.start()).toBe('REST_READY')
    expect(f.factory).not.toHaveBeenCalled()
  })
  it('CONNECTING通知同步取消时不得再创建带旧凭据的socket', async () => {
    const f = fixture()
    f.onState.mockImplementation((state: string) => {
      if (state === 'CONNECTING') f.signal.abort()
    })
    await expect(f.realtime.start()).rejects.toBeInstanceOf(DesignerRealtimeCancelledError)
    expect(f.factory).not.toHaveBeenCalled()
  })
  it('计划在握手前拒绝重复设备、重复键及超过200键', () => {
    expect(() => fixture([{ deviceId, propertyKeys: ['a', 'a'] }])).toThrow('合同')
    expect(() =>
      fixture([
        { deviceId, propertyKeys: ['a'] },
        { deviceId, propertyKeys: ['b'] }
      ])
    ).toThrow('合同')
    const devices = Array.from({ length: 5 }, (_, index) => ({
      deviceId: `${String(index).padStart(8, '0')}-1111-4111-8111-111111111111`,
      propertyKeys: Array.from({ length: 50 }, (_, key) => `key${key}`)
    }))
    expect(() => fixture(devices)).toThrow('合同')
  })
  it('ACK5秒超时关闭，迟到ACK不恢复SUBSCRIBED', async () => {
    const f = fixture()
    const started = f.realtime.start()
    f.socket.open()
    await vi.advanceTimersByTimeAsync(5000)
    f.ack()
    expect(f.onState).not.toHaveBeenCalledWith('SUBSCRIBED')
    f.socket.closed()
    expect(await started).toBe('REST_READY')
  })
})

describe('有界无值提示及代次围栏', () => {
  it('ACK前提示待关联核实后才通知，交换dirty后在途新提示不丢失', async () => {
    const f = fixture()
    const started = f.realtime.start()
    f.socket.open()
    f.hint()
    expect(f.onDirty).not.toHaveBeenCalled()
    expect(f.realtime.takeDirty()).toEqual([])
    f.ack()
    await started
    expect(f.onDirty).toHaveBeenCalledTimes(1)
    f.hint()
    expect(f.onDirty).toHaveBeenCalledTimes(1)
    const taken = f.realtime.takeDirty()
    expect(taken).toEqual([{ deviceId, propertyKeys: ['temperature'] }])
    expect(Object.isFrozen(taken) && Object.isFrozen(taken[0]!.propertyKeys)).toBe(true)
    f.hint({ devices: [{ deviceId, propertyKeys: ['phase-a'] }] })
    expect(f.onDirty).toHaveBeenCalledTimes(2)
    expect(f.realtime.takeDirty()).toEqual([{ deviceId, propertyKeys: ['phase-a'] }])
    const closed = f.realtime.close()
    f.socket.closed()
    await closed
  })
  it('ACK前提示subscription与ACK不符拒绝整链并清dirty', async () => {
    const f = fixture()
    const started = f.realtime.start()
    f.socket.open()
    f.hint({ subscriptionId: otherId })
    f.ack()
    f.socket.closed()
    expect(await started).toBe('REST_READY')
    expect(f.onDirty).not.toHaveBeenCalled()
    expect(f.realtime.takeDirty()).toEqual([])
  })
  it.each(['device', 'key', 'duplicate', 'value', 'large', 'binary', 'duplicate-json-key'])(
    '非法提示 %s 关闭清集合，不把业务值或XSS交给组件',
    async (variant) => {
      const f = fixture()
      const started = f.realtime.start()
      f.socket.open()
      f.ack()
      await started
      f.hint()
      expect(f.onDirty).toHaveBeenCalledTimes(1)
      if (variant === 'device')
        f.hint({ devices: [{ deviceId: otherId, propertyKeys: ['temperature'] }] })
      else if (variant === 'key') f.hint({ devices: [{ deviceId, propertyKeys: ['unknown'] }] })
      else if (variant === 'duplicate')
        f.hint({ devices: [{ deviceId, propertyKeys: ['temperature', 'temperature'] }] })
      else if (variant === 'value') f.hint({ value: '<script>danger()</script>' })
      else if (variant === 'large') f.socket.raw('中'.repeat(11000))
      else if (variant === 'binary') f.socket.raw(new ArrayBuffer(1))
      else f.socket.raw('{"type":"INVALIDATE","type":"INVALIDATE"}')
      expect(f.realtime.takeDirty()).toEqual([])
      f.hint()
      expect(f.onDirty).toHaveBeenCalledTimes(1)
      f.socket.closed()
      await flush()
      expect(f.onState).toHaveBeenLastCalledWith('REST_READY')
    }
  )
  it('明确1008通知完整确权，依赖1011只降级REST且不自动重连', async () => {
    for (const code of [1008, 1011]) {
      const f = fixture()
      const started = f.realtime.start()
      f.socket.open()
      f.ack()
      await started
      f.socket.closed(code)
      await flush()
      expect(f.onRevoked).toHaveBeenCalledTimes(code === 1008 ? 1 : 0)
      expect(f.onState).toHaveBeenLastCalledWith('REST_READY')
      await vi.advanceTimersByTimeAsync(1000)
      expect(f.factory).toHaveBeenCalledTimes(1)
    }
  })
  it.each(['token', 'epoch', 'abort'])(
    '身份或信号变化 %s 先封闭业务回调，仍确认真实关闭',
    async (kind) => {
      const f = fixture()
      const started = f.realtime.start()
      f.socket.open()
      f.ack()
      await started
      const states = f.onState.mock.calls.length
      if (kind === 'token') identity.token = 'replacement'
      else if (kind === 'epoch') identity.epoch++
      else f.signal.abort()
      f.hint()
      expect(f.onDirty).not.toHaveBeenCalled()
      expect(f.socket.close).toHaveBeenCalledTimes(1)
      const closed = f.realtime.close()
      f.socket.closed(1008)
      await closed
      expect(f.onState).toHaveBeenCalledTimes(states)
      expect(f.onRevoked).not.toHaveBeenCalled()
    }
  )
})

describe('关闭确认与不可提前放行', () => {
  it('重复close共享Promise，直到真正close事件才完成', async () => {
    const f = fixture()
    const started = f.realtime.start()
    f.socket.open()
    f.ack()
    await started
    const first = f.realtime.close()
    expect(f.realtime.close()).toBe(first)
    let done = false
    void first.then(() => {
      done = true
    })
    await flush()
    expect(done).toBe(false)
    f.socket.closed()
    await first
    expect(done).toBe(true)
  })
  it('关闭不确认明确失败，后续仍CLOSING只能等同一旧socket，关闭后可恢复', async () => {
    const f = fixture()
    const started = f.realtime.start()
    f.socket.open()
    f.ack()
    await started
    const first = f.realtime.close()
    const rejected = expect(first).rejects.toBeInstanceOf(DesignerRealtimeCloseError)
    await vi.advanceTimersByTimeAsync(5000)
    await rejected
    expect(f.onFailure).toHaveBeenCalledTimes(1)
    const second = f.realtime.close()
    expect(second).not.toBe(first)
    expect(f.factory).toHaveBeenCalledTimes(1)
    f.socket.closed()
    await second
    expect(await f.realtime.close()).toBeUndefined()
  })
  it('已超时后实际CLOSED可重新确认，不永久锁死已关闭连接', async () => {
    const f = fixture()
    const started = f.realtime.start()
    f.socket.open()
    f.ack()
    await started
    const first = f.realtime.close()
    const rejected = expect(first).rejects.toBeInstanceOf(DesignerRealtimeCloseError)
    await vi.advanceTimersByTimeAsync(5000)
    await rejected
    f.socket.closed()
    await expect(f.realtime.close()).resolves.toBeUndefined()
  })
  it('ACK失败后close也不确认时start拒绝，不伪装REST_READY', async () => {
    const f = fixture()
    const started = f.realtime.start()
    const rejected = expect(started).rejects.toBeInstanceOf(DesignerRealtimeCloseError)
    f.socket.open()
    f.ack({ count: 99 })
    await vi.advanceTimersByTimeAsync(5000)
    await rejected
    expect(f.onState).not.toHaveBeenCalledWith('REST_READY')
  })
  it('正常已订阅超过原30秒后仍能用独立5秒关闭，不受旧建连截止误杀', async () => {
    const f = fixture()
    const started = f.realtime.start()
    f.socket.open()
    f.ack()
    await started
    await vi.advanceTimersByTimeAsync(61_000)
    const closed = f.realtime.close()
    await vi.advanceTimersByTimeAsync(4000)
    f.socket.closed()
    await closed
    expect(f.onFailure).not.toHaveBeenCalled()
  })
  it('后续close显式新轮deadline比5秒更短时按剩余时间拒绝', async () => {
    const f = fixture()
    const started = f.realtime.start()
    f.socket.open()
    f.ack()
    await started
    const closed = f.realtime.close(performance.now() + 1000)
    const rejected = expect(closed).rejects.toBeInstanceOf(DesignerRealtimeCloseError)
    await vi.advanceTimersByTimeAsync(999)
    expect(f.onFailure).not.toHaveBeenCalled()
    await vi.advanceTimersByTimeAsync(1)
    await rejected
  })
  it('建连期间主动取消阻止ACK并等待关闭后拒绝start', async () => {
    const f = fixture()
    const started = f.realtime.start()
    const rejected = expect(started).rejects.toBeInstanceOf(DesignerRealtimeCancelledError)
    f.signal.abort()
    f.socket.open()
    expect(f.socket.send).not.toHaveBeenCalled()
    f.socket.closed()
    await rejected
  })
})
