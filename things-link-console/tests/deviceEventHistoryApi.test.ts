import { afterEach, expect, it, vi } from 'vitest'
import { eventHistoryQuery, fetchDeviceEvents } from '@/api/device-event-history'
vi.mock('@/store/modules/user', () => ({ useUserStore: () => ({ accessToken: 'ram-only' }) }))
const filters = () => ({ eventKey: '', level: '', thingModelVersionId: '', from: '', to: '' })
afterEach(() => {
  vi.unstubAllGlobals()
  vi.unstubAllEnvs()
})
it('封闭过滤与原游标，坏版本/时间/键明确拒绝', () => {
  const q = eventHistoryQuery({ ...filters(), eventKey: 'alarm', level: 'WARNING' }, 'cursor')
  expect(q.get('cursor')).toBe('cursor')
  expect(q.get('limit')).toBe('20')
  const from = '2026-10-06T00:00:00.000000001Z',
    to = '2026-10-06T00:00:00.000000002Z'
  const nanoseconds = eventHistoryQuery({ ...filters(), from, to })
  expect(nanoseconds.get('from')).toBe(from)
  expect(nanoseconds.get('to')).toBe(to)
  for (const bad of [
    { eventKey: '_bad' },
    { level: 'future' },
    { thingModelVersionId: 'bad' },
    { from: 'today' },
    { from: '2026-10-06T24:00:00Z' }
  ])
    expect(() => eventHistoryQuery({ ...filters(), ...bad })).toThrow()
})
it('合法小写时间和UTC闰秒原文传递，游标不重写时间语义', () => {
  for (const [from, to] of [
    ['2026-10-06t00:00:00.000000001z', '2026-10-06t00:00:00.000000002z'],
    ['2016-12-31T23:59:60Z', '2017-01-01T00:00:00Z']
  ]) {
    const query = eventHistoryQuery({ ...filters(), from: from!, to: to! }, 'original_cursor')
    expect(query.get('from')).toBe(from)
    expect(query.get('to')).toBe(to)
    expect(query.get('cursor')).toBe('original_cursor')
  }
})
it('真实fetch流边界只读且超限和服务端私正文不回显', async () => {
  const fetch = vi.fn().mockImplementation(() =>
    Promise.resolve(
      new Response('{"code":30072,"message":"private-secret","traceId":"trace","details":[]}', {
        status: 404,
        headers: { 'content-type': 'application/json' }
      })
    )
  )
  vi.stubGlobal('fetch', fetch)
  await expect(fetchDeviceEvents('p', 'd', filters())).rejects.toMatchObject({
    code: 30072,
    status: 404
  })
  await expect(fetchDeviceEvents('p', 'd', filters())).rejects.not.toThrow('private-secret')
  expect(fetch.mock.calls[0]![1]).toMatchObject({
    method: 'GET',
    cache: 'no-store',
    redirect: 'error'
  })
  fetch.mockImplementation(() =>
    Promise.resolve(
      new Response(new Uint8Array(8 * 1024 * 1024 + 1), {
        headers: { 'content-type': 'application/json' }
      })
    )
  )
  await expect(fetchDeviceEvents('p', 'd', filters())).rejects.toThrow('不可用')
})

it('读取遵循配置的API根，不绕过部署路径', async () => {
  vi.stubEnv('VITE_API_URL', '/gateway/')
  const fetch = vi.fn().mockResolvedValue(
    new Response('{"code":30072,"message":"controlled","traceId":"trace","details":[]}', {
      status: 404,
      headers: { 'content-type': 'application/json' }
    })
  )
  vi.stubGlobal('fetch', fetch)
  await expect(fetchDeviceEvents('p', 'd', filters())).rejects.toMatchObject({ code: 30072 })
  expect(fetch.mock.calls[0]?.[0]).toBe('/gateway/api/v1/projects/p/devices/d/events?limit=20')
})
