import { describe, expect, it, vi } from 'vitest'
import {
  validateDashboardSchemaV1,
  parseDashboardRuntimeResponse,
  type AlarmQuery,
  type AlarmResult
} from '@things-link/client-contracts/dashboard/v1'
import { loadDesignerDevicePreview } from '@/features/dashboard/device-preview'
const id = (n: number) => `11111111-1111-4111-8111-${String(n).padStart(12, '0')}`
const versionId = id(90)
const model = {
  versionId,
  digestAlgorithm: 'PG_JSONB_TEXT_V1_SHA256',
  digest: 'a'.repeat(64),
  profile: 'TC_PROPERTY_COMPOSITE_V1'
}
const parse = (value: unknown) =>
  parseDashboardRuntimeResponse(new TextEncoder().encode(JSON.stringify(value)))
function fixture() {
  const component = (name: string, y: number) => ({
    id: name,
    kind: 'ALARM_LIST',
    componentVersion: '1.0.0',
    layout: { x: 0, y, w: 12, h: 10 },
    props: { pageSize: 1, showClearedAt: true },
    bindings: {
      alarms: {
        source: 'ALARM_LIST',
        devices: { variableKey: 'devices' },
        conditionStates: ['ACTIVE', 'PENDING'],
        ackStates: ['UNACKNOWLEDGED'],
        severities: ['MAJOR']
      }
    }
  })
  const input = {
    schemaVersion: 'tc.dashboard/v1',
    presentation: { mode: 'RESPONSIVE_GRID' },
    models: [{ key: 'model', ...model }],
    variables: [
      {
        key: 'devices',
        type: 'DEVICE_MULTI',
        title: '告警设备',
        modelKey: 'model',
        maxItems: 20,
        defaultDeviceIds: [id(2), id(1)]
      }
    ],
    pages: [
      { id: 'main', title: '主页', components: [component('first', 0), component('second', 10)] }
    ]
  }
  const schema = () =>
    validateDashboardSchemaV1(new TextEncoder().encode(JSON.stringify(input))).schema
  const snapshots = vi.fn(async () =>
    parse({
      devices: [id(2), id(1)].map((deviceId) => ({
        deviceId,
        status: 'AVAILABLE',
        currentModelVersionId: versionId,
        name: deviceId,
        deviceStatus: 'ONLINE',
        lastOnlineAt: null
      })),
      models: [{ ...model, properties: [] }]
    })
  )
  const alarms = vi.fn(
    async (query: AlarmQuery, componentId: string): Promise<AlarmResult> => ({
      componentId,
      queryId: query.queryId,
      status: 'READY',
      items: [],
      nextCursor: null,
      hasMore: false
    })
  )
  const ports = { snapshots, alarms, current: vi.fn(), checkCurrent: vi.fn() }
  return { input, schema, ports }
}
describe('告警页统一读取计划', () => {
  it('同设备集合/过滤/limit去重，无属性不读current', async () => {
    const f = fixture()
    f.input.pages[0]!.components[1]!.bindings.alarms.conditionStates.reverse()
    const rows = await loadDesignerDevicePreview(f.schema(), 'main', f.ports)
    expect(f.ports.alarms).toHaveBeenCalledTimes(1)
    expect(f.ports.current).not.toHaveBeenCalled()
    expect(f.ports.alarms.mock.calls[0]![0].devices.map((device) => device.deviceId)).toEqual([
      id(1),
      id(2)
    ])
    expect(rows.map((row) => row.alarm?.result?.componentId)).toEqual(['first', 'second'])
    expect(rows.every((row) => row.text === '当前筛选没有告警')).toBe(true)
  })
  it('任一设备不可用不能被最后可用设备覆盖成空列表', async () => {
    const f = fixture()
    f.ports.snapshots.mockResolvedValue(
      parse({
        devices: [
          { deviceId: id(2), status: 'NOT_AVAILABLE' },
          {
            deviceId: id(1),
            status: 'AVAILABLE',
            currentModelVersionId: versionId,
            name: '可用',
            deviceStatus: 'ONLINE',
            lastOnlineAt: null
          }
        ],
        models: [{ ...model, properties: [] }]
      })
    )
    const rows = await loadDesignerDevicePreview(f.schema(), 'main', f.ports)
    expect(f.ports.alarms).not.toHaveBeenCalled()
    expect(rows[0]?.alarm?.result).toBeUndefined()
    expect(rows[0]?.alarm?.query).toBeUndefined()
    expect(rows[0]?.text).toContain('不可用')
  })
  it('未选择不请求，10001只映射关联列表失败', async () => {
    const f = fixture()
    const empty = await loadDesignerDevicePreview(f.schema(), 'main', f.ports, { devices: [] })
    expect(f.ports.snapshots).not.toHaveBeenCalled()
    expect(empty[0]?.text).toBe('请选择设备')
    f.ports.alarms.mockImplementation(async (query, componentId) => ({
      componentId,
      queryId: query.queryId,
      status: 'CONFIGURATION_ERROR',
      items: [],
      nextCursor: null,
      hasMore: false
    }))
    const rejected = await loadDesignerDevicePreview(f.schema(), 'main', f.ports)
    expect(rejected[0]?.text).toContain('失效')
    expect(rejected[0]?.alarm?.result?.status).toBe('CONFIGURATION_ERROR')
  })
  it('回包前换代次不返回旧列表', async () => {
    const f = fixture()
    f.ports.alarms.mockImplementation(async (query, componentId) => {
      f.ports.checkCurrent.mockImplementation(() => {
        throw new Error('stale')
      })
      return {
        componentId,
        queryId: query.queryId,
        status: 'READY',
        items: [],
        nextCursor: null,
        hasMore: false
      }
    })
    await expect(loadDesignerDevicePreview(f.schema(), 'main', f.ports)).rejects.toThrow('stale')
  })
  it('完整首轮基础设施失败不发布部分成功，保留原始失败', async () => {
    const f = fixture()
    const failure = Object.assign(new Error('server'), { status: 500 })
    f.ports.alarms.mockRejectedValue(failure)
    await expect(loadDesignerDevicePreview(f.schema(), 'main', f.ports)).rejects.toBe(failure)
  })
})
