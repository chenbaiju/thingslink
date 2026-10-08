import { afterEach, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { PropertyHistoryModel, decodePropertyPage } from '@/features/device/property-history-model'
import { fetchDevicePropertyHistory, propertyHistoryQuery } from '@/api/device-property-history'
vi.mock('@/store/modules/user', () => ({ useUserStore: () => ({ accessToken: 'ram-only' }) }))
const filters = () => ({ propertyKey: '', from: '', to: '' })
const bytes = (value: string) => new TextEncoder().encode(value)
const point = (type: string, value: string) =>
  `{"deviceId":"device","propertyKey":"sample","ts":"2026-10-07T00:00:00.000000001Z","value":${value},"dataType":"${type}","thingModelVersionId":null,"modelVersion":"LEGACY_UNVERSIONED","quality":0}`
const page = (type: string, value: string) =>
  bytes(`{"items":[${point(type, value)}],"nextCursor":null,"hasMore":false}`)
const deferred = () => {
  let resolve!: (v: any) => void
  const promise = new Promise<any>((r) => {
    resolve = r
  })
  return { promise, resolve }
}
afterEach(() => {
  vi.unstubAllGlobals()
  vi.unstubAllEnvs()
})
it.each([
  ['NUMBER', '9007199254740993123456789', '9007199254740993123456789'],
  ['NUMBER', '1.234567890123456789e+77', '1.234567890123456789e+77'],
  ['TEXT', '"<img src=x onerror=alert(1)>"', '"<img src=x onerror=alert(1)>"'],
  ['ENUM', '"ON"', '"ON"'],
  ['SWITCH', 'false', 'false'],
  [
    'OBJECT',
    '{"count":9007199254740993123456789,"nested":{"n":-0,"ok":true}}',
    '{"count": 9007199254740993123456789, "nested": {"n": -0, "ok": true}}'
  ],
  [
    'LIST',
    '[9007199254740993123456789,{"x":1.2300},false,null]',
    '[9007199254740993123456789, {"x": 1.2300}, false, null]'
  ]
])('保真呈现 %s 值 %s', (type, value, rendered) => {
  const result = decodePropertyPage(page(type, value), 'device')
  expect(result.items[0]?.valueText).toBe(rendered)
  expect(result.items[0]?.ts).toBe('2026-10-07T00:00:00.000000001Z')
  expect(result.items[0]?.thingModelVersionId).toBeNull()
})
it('拒绝跨设备、重复键、类型矛盾和伪分页，而不把私正文变成空数据', () => {
  expect(() => decodePropertyPage(page('NUMBER', '1'), 'other')).toThrow()
  expect(() => decodePropertyPage(page('OBJECT', '{"a":1,"a":2}'), 'device')).toThrow()
  expect(() => decodePropertyPage(page('NUMBER', '"1"'), 'device')).toThrow()
  expect(() =>
    decodePropertyPage(bytes('{"items":[],"nextCursor":null,"hasMore":true}'), 'device')
  ).toThrow()
})
it('只传合同筛选和原游标，保留纳秒和from含to不含的输入', () => {
  const from = '2026-10-07T00:00:00.000000001Z',
    to = '2026-10-07T00:00:00.000000002Z'
  const query = propertyHistoryQuery({ propertyKey: 'text', from, to }, 'opaque_cursor')
  expect(Object.fromEntries(query)).toEqual({
    limit: '20',
    propertyKey: 'text',
    from,
    to,
    cursor: 'opaque_cursor'
  })
  expect(() => propertyHistoryQuery({ ...filters(), from: 'today' })).toThrow()
  expect(() => propertyHistoryQuery({ ...filters(), propertyKey: 'a&other=b' })).toThrow()
})
it('运输层使用原始字节保持大整数，认证只进header', async () => {
  const fetch = vi.fn().mockResolvedValue(
    new Response(page('OBJECT', '{"n":9007199254740993}'), {
      headers: { 'content-type': 'application/json' }
    })
  )
  vi.stubGlobal('fetch', fetch)
  expect(
    (await fetchDevicePropertyHistory('project', 'device', filters())).items[0]?.valueText
  ).toBe('{"n": 9007199254740993}')
  expect(fetch.mock.calls[0]?.[0]).toContain(
    '/projects/project/devices/device/telemetry/property?limit=20'
  )
  expect(fetch.mock.calls[0]?.[1]).toMatchObject({
    method: 'GET',
    cache: 'no-store',
    redirect: 'error',
    headers: { Authorization: 'Bearer ram-only' }
  })
})
it('套餐窗口503只显示安全错误，不回显服务端正文', async () => {
  vi.stubGlobal(
    'fetch',
    vi.fn().mockImplementation(() =>
      Promise.resolve(
        new Response('{"code":50048,"message":"private_body","traceId":"trace","details":[]}', {
          status: 503,
          headers: { 'content-type': 'application/json' }
        })
      )
    )
  )
  await expect(fetchDevicePropertyHistory('project', 'device', filters())).rejects.toMatchObject({
    status: 503,
    code: 50048
  })
  await expect(fetchDevicePropertyHistory('project', 'device', filters())).rejects.not.toThrow(
    'private_body'
  )
})
it.each(['device', 'project', 'identity', 'close'])('%s变更拒绝迟响应与旧游标', async (change) => {
  let epoch = 0
  const old = deferred()
  const list = vi
    .fn()
    .mockReturnValueOnce(old.promise)
    .mockResolvedValue({ items: [{ valueText: 'NEW' }], nextCursor: undefined, hasMore: false })
  const model = new PropertyHistoryModel({ list }, () => epoch)
  model.scope('project', 'device', true)
  if (change === 'device') model.scope('project', 'other', true)
  if (change === 'project') model.scope('other', 'device', true)
  if (change === 'identity') {
    epoch++
    model.scope('project', 'device', true)
  }
  if (change === 'close') model.close()
  old.resolve({ items: [{ valueText: 'PRIVATE_OLD' }], nextCursor: 'old-cursor', hasMore: true })
  await flushPromises()
  expect(model.items.some((item) => item.valueText === 'PRIVATE_OLD')).toBe(false)
  expect(model.nextCursor).toBeUndefined()
})
it('翻页绑定已应用筛选，刷新移除旧游标；失败明确并可恢复', async () => {
  const list = vi.fn().mockResolvedValue({ items: [], nextCursor: 'first-cursor', hasMore: true })
  const model = new PropertyHistoryModel({ list }, () => 0)
  model.scope('project', 'device', true)
  await flushPromises()
  model.filters.propertyKey = 'new-filter'
  await model.next()
  expect(list.mock.calls[1]?.[2]).toEqual(filters())
  expect(list.mock.calls[1]?.[3]).toBe('first-cursor')
  list.mockRejectedValueOnce(new Error('private_error'))
  await model.refresh()
  expect(list.mock.calls[2]?.[2].propertyKey).toBe('new-filter')
  expect(list.mock.calls[2]?.[3]).toBeUndefined()
  expect(model.items).toEqual([])
  expect(model.nextCursor).toBeUndefined()
  expect(model.error).toContain('不可用')
  expect(model.error).not.toContain('private_error')
  await model.refresh()
  expect(model.error).toBe('')
})
