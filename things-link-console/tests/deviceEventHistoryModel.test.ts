import { describe, expect, it, vi } from 'vitest'
import {
  decodeEvent,
  decodeEventPage,
  EventHistoryModel
} from '@/features/device/event-history-model'
const id = '01a1128c-1957-7407-a63c-84fa716af07f',
  device = '01a1128c-85ed-77e6-b94e-0096acb2b567'
const source = `{"messageId":"${id}","deviceId":"${device}","deviceTypeId":"${id}","eventKey":"alarm","level":"WARNING","thingModelVersionId":"${id}","modelVersion":"1.0.0","eligibility":"CURRENT","occurredAt":"2026-10-06T00:00:00Z","receivedAt":"2026-10-06T00:00:01Z","acceptedAt":"2026-10-06T00:00:02Z","params":{"integer":12345678901234567890123456789012345678,"decimal":9007199254740993.123456789,"scale":1.0,"tiny":1e-308,"text":"<script>bad</script>"},"paramsRedacted":true}`
const bytes = (text: string) => new TextEncoder().encode(text)
const row = () => decodeEvent(bytes(source), device)
const page = (items = [row()], nextCursor: string | null = null) => ({
  items,
  nextCursor,
  windowFrom: 'a',
  windowTo: 'b',
  retentionDays: 90
})
function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((yes) => {
    resolve = yes
  })
  return { promise, resolve }
}
describe('事件历史严格读取与会话', () => {
  it('38位整数、小数、scale与指数保词法并封闭字段', () => {
    for (const expected of [
      '12345678901234567890123456789012345678',
      '9007199254740993.123456789',
      '"scale": 1.0',
      '1e-308'
    ])
      expect(row().paramsText).toContain(expected)
    for (const bad of [
      source.replace('"params":{', '"extra":true,"params":{'),
      source.replace('"paramsRedacted":true', '"paramsRedacted":true,"paramsRedacted":false'),
      source + ' {}',
      source.replace('"CURRENT"', '"FUTURE"')
    ])
      expect(() => decodeEvent(bytes(bad), device)).toThrow()
    expect(() => decodeEvent(bytes(source), 'other')).toThrow()
  })
  it('实际100行超过4MiB仍严格分帧，未知/重复/尾随/超限不可用', () => {
    const params = JSON.stringify(
      Object.fromEntries(Array.from({ length: 99 }, (_, i) => ['p' + i, 'x'.repeat(600)]))
    )
    const big = source.replace(
      /"params":\{.*\},"paramsRedacted"/,
      `"params":${params},"paramsRedacted"`
    )
    const raw = `{"items":[${Array.from({ length: 100 }, (_, i) => big.replace(id, '01a1128c-1957-7407-a63c-' + i.toString(16).padStart(12, '0'))).join(',')}],"nextCursor":"next","windowFrom":"a","windowTo":"b","retentionDays":90}`
    expect(bytes(raw).length).toBeGreaterThan(4 * 1024 * 1024)
    expect(decodeEventPage(bytes(raw), device).items).toHaveLength(100)
    for (const bad of [
      raw.replace('"retentionDays":90', '"retentionDays":90,"unknown":true'),
      raw.replace('"nextCursor":"next"', '"nextCursor":"next","nextCursor":null'),
      raw + ' false',
      raw.replace('"items":[', '"items":[,')
    ])
      expect(() => decodeEventPage(bytes(bad), device)).toThrow()
    expect(() => decodeEventPage(new Uint8Array(8 * 1024 * 1024 + 1), device)).toThrow()
  })
  it('丢失items分帧部分也严格验证配对、转义键、空数组与cursor', () => {
    const suffix = ',"nextCursor":null,"windowFrom":"a","windowTo":"b","retentionDays":90}'
    for (const array of ['[}', '[{]', '[{"params":[}}]'])
      expect(() => decodeEventPage(bytes('{"items":' + array + suffix), device)).toThrow()
    const valid = '{"items":[]' + suffix
    expect(decodeEventPage(bytes(valid), device).items).toEqual([])
    expect(() =>
      decodeEventPage(
        bytes(valid.replace('"items":[]', String.raw`"items":[],"\u0069tems":[]`)),
        device
      )
    ).toThrow()
    for (const cursor of ['', 'not+url', 'x'.repeat(8193)])
      expect(() =>
        decodeEventPage(
          bytes(valid.replace('"nextCursor":null', '"nextCursor":' + JSON.stringify(cursor))),
          device
        )
      ).toThrow()
  })
  it('游标沿应用筛选、刷新/详情/错误/空态各自清理', async () => {
    const port = {
        list: vi.fn().mockResolvedValue(page([row()], 'next')),
        detail: vi.fn().mockResolvedValue(row())
      },
      model = new EventHistoryModel(port, () => 1)
    model.scope('p', device, true)
    await Promise.resolve()
    model.filters.eventKey = 'empty'
    await model.refresh()
    model.filters.eventKey = 'not-applied'
    await model.next()
    expect(port.list.mock.calls.at(-1)?.slice(2, 4)).toEqual([
      { eventKey: 'empty', level: '', thingModelVersionId: '', from: '', to: '' },
      'next'
    ])
    await model.open(id)
    expect(model.detail?.paramsRedacted).toBe(true)
    model.clearDetail()
    expect(model.detail).toBeUndefined()
    port.list.mockResolvedValueOnce(page([]))
    await model.refresh()
    expect(model.items).toEqual([])
    expect(model.error).toBe('')
    port.detail.mockRejectedValueOnce(new Error('secret'))
    await model.open(id)
    expect(model.detailError).not.toContain('secret')
    port.list.mockRejectedValueOnce(new Error('private'))
    await model.refresh()
    expect(model.error).toContain('不可用')
    expect(model.nextCursor).toBeUndefined()
  })
  it('身份ABA/close及迟到finally不能复活旧结果或清新loading', async () => {
    let epoch = 1
    const old = deferred<ReturnType<typeof page>>(),
      current = deferred<ReturnType<typeof page>>()
    const port = {
        list: vi
          .fn()
          .mockReturnValueOnce(old.promise)
          .mockReturnValueOnce(current.promise)
          .mockResolvedValue(page()),
        detail: vi.fn().mockResolvedValue(row())
      },
      model = new EventHistoryModel(port, () => epoch)
    model.scope('p', device, true)
    epoch = 2
    model.scope('q', device, true)
    old.resolve(page([], 'old'))
    await Promise.resolve()
    await Promise.resolve()
    expect(model.loading).toBe(true)
    epoch = 3
    model.scope('p', device, true)
    await Promise.resolve()
    current.resolve(page([], 'old'))
    await Promise.resolve()
    expect(model.items).toHaveLength(1)
    expect(model.nextCursor).toBeUndefined()
    const late = deferred<ReturnType<typeof row>>()
    port.detail.mockReturnValueOnce(late.promise)
    void model.open(id)
    model.filters.eventKey = 'old'
    model.close()
    late.resolve(row())
    await Promise.resolve()
    expect(model.items).toEqual([])
    expect(model.detail).toBeUndefined()
    expect(model.filters.eventKey).toBe('')
    expect(model.loading).toBe(false)
  })
})

it('上一页复用应用筛选和历史游标，翻页清除详情，刷新和关闭重置页码', async () => {
  const list = vi
    .fn()
    .mockImplementation(async (_p, _d, _f, cursor) =>
      page([], cursor === 'page-3' ? null : cursor === 'page-2' ? 'page-3' : 'page-2')
    )
  const model = new EventHistoryModel({ list, detail: vi.fn().mockResolvedValue(row()) }, () => 0)
  model.scope('project', device, true)
  await Promise.resolve()
  model.filters.eventKey = 'alarm'
  await model.refresh()
  await model.next()
  await model.next()
  expect(model.pageIndex).toBe(2)
  await model.open(id)
  expect(model.detail).toBeDefined()
  model.filters.eventKey = 'draft'
  await model.previous()
  expect(model.pageIndex).toBe(1)
  expect(model.detail).toBeUndefined()
  expect(list.mock.lastCall?.[2].eventKey).toBe('alarm')
  expect(list.mock.lastCall?.[3]).toBe('page-2')
  await model.previous()
  expect(list.mock.lastCall?.[3]).toBeUndefined()
  await model.next()
  await model.refresh()
  expect(model.pageIndex).toBe(0)
  expect(list.mock.lastCall?.[2].eventKey).toBe('draft')
  await model.next()
  model.close()
  expect(model.pageIndex).toBe(0)
  model.scope('project', 'other-device', true)
  await Promise.resolve()
  expect(list.mock.lastCall?.[1]).toBe('other-device')
  expect(list.mock.lastCall?.[3]).toBeUndefined()
})
