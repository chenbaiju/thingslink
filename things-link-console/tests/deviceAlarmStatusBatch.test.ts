import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const f = vi.hoisted(() => ({
  epoch: 1,
  mode: 'valid',
  scopes: [] as { read: ReturnType<typeof vi.fn>; close: ReturnType<typeof vi.fn> }[]
}))
vi.mock('@/utils/http/identity-scope', () => ({ currentIdentityEpoch: () => f.epoch }))
vi.mock('@/api/designer-read-scope', () => ({
  createDesignerReadScope: () => {
    const scope = { read: vi.fn(), close: vi.fn() }
    f.scopes.push(scope)
    scope.read.mockImplementation(async (path: string) =>
      response(new URL(path, 'http://local').searchParams.getAll('deviceId'))
    )
    return scope
  }
}))
const id = (n: number) => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`
const response = (ids: string[]) => ({
  observedAt: '2026-09-30T12:00:00Z',
  devices: (f.mode === 'missing' ? ids.slice(1) : ids).map((deviceId) => ({
    deviceId,
    modelVersionId: id(99),
    state: f.mode === 'unknown' ? 'UNKNOWN' : 'NORMAL'
  }))
})
let request: typeof import('@/api/device-alarm-status').requestDeviceAlarmStatus
beforeEach(async () => {
  vi.useFakeTimers()
  vi.resetModules()
  f.epoch = 1
  f.mode = 'valid'
  f.scopes.length = 0
  request = (await import('@/api/device-alarm-status')).requestDeviceAlarmStatus
})
afterEach(() => vi.useRealTimers())

describe('visible device alarm batches', () => {
  it('combines 25 rows into bounded 20 and 5 requests and covers every row', async () => {
    const tickets = Array.from({ length: 25 }, (_, index) => request(id(50), id(index)))
    await vi.runAllTimersAsync()
    const values = await Promise.all(tickets.map((ticket) => ticket.result))
    expect(f.scopes).toHaveLength(2)
    expect(
      f.scopes.map(
        (scope) =>
          new URL(scope.read.mock.calls[0]![0], 'http://local').searchParams.getAll('deviceId')
            .length
      )
    ).toEqual([20, 5])
    expect(values.map((value) => value.deviceId)).toEqual(
      Array.from({ length: 25 }, (_, index) => id(index))
    )
    expect(values.every((value) => value.state === 'NORMAL' && !!value.observedAt)).toBe(true)
  })
  it('isolates projects, coalesces duplicate devices and cancels queued rows', async () => {
    const a = request(id(50), id(1))
    const b = request(id(50), id(1))
    const c = request(id(51), id(1))
    const cancelled = request(id(50), id(2))
    const rejected = cancelled.result.catch(() => 'cancelled')
    cancelled.cancel()
    await vi.runAllTimersAsync()
    await Promise.all([a.result, b.result, c.result])
    expect(await rejected).toBe('cancelled')
    expect(f.scopes).toHaveLength(2)
    expect(
      new URL(f.scopes[0]!.read.mock.calls[0]![0], 'http://local').searchParams.getAll('deviceId')
    ).toEqual([id(1)])
  })
  it('does not abort another row when a member of the shared request disappears', async () => {
    const a = request(id(50), id(1))
    const b = request(id(50), id(2))
    const rejection = a.result.catch(() => 'cancelled')
    vi.advanceTimersByTime(0)
    a.cancel()
    expect(f.scopes[0]!.close).not.toHaveBeenCalled()
    expect((await b.result).deviceId).toBe(id(2))
    expect(await rejection).toBe('cancelled')
    expect(f.scopes[0]!.close).toHaveBeenCalled()
  })
  it('rejects stale identity responses before updating any row', async () => {
    const a = request(id(50), id(1))
    const b = request(id(50), id(2))
    const results = Promise.allSettled([a.result, b.result])
    vi.advanceTimersByTime(0)
    f.epoch++
    expect((await results).every((value) => value.status === 'rejected')).toBe(true)
  })
  it.each(['missing', 'unknown'])('rejects %s response for every requested row', async (mode) => {
    f.mode = mode
    const a = request(id(50), id(1))
    const b = request(id(50), id(2))
    const results = Promise.allSettled([a.result, b.result])
    await vi.runAllTimersAsync()
    expect((await results).every((value) => value.status === 'rejected')).toBe(true)
  })
  it('aborts the shared scope when all rows disappear', async () => {
    const a = request(id(50), id(1))
    const b = request(id(50), id(2))
    const results = Promise.allSettled([a.result, b.result])
    vi.advanceTimersByTime(0)
    a.cancel()
    b.cancel()
    expect(f.scopes[0]!.close).toHaveBeenCalled()
    expect((await results).every((value) => value.status === 'rejected')).toBe(true)
  })
  it('retries by reading a fresh snapshot without reusing the prior result', async () => {
    const first = request(id(50), id(1))
    await vi.runAllTimersAsync()
    await first.result
    const second = request(id(50), id(1))
    await vi.runAllTimersAsync()
    await second.result
    expect(f.scopes).toHaveLength(2)
  })
})
