import { describe, expect, it, vi } from 'vitest'
import {
  validateDashboardSchemaV1,
  parseDashboardRuntimeResponse,
  type HistoryQuery,
  type HistoryResult
} from '@things-link/client-contracts/dashboard/v1'
import {
  loadDesignerDevicePreview,
  refreshDesignerCurrent,
  type DesignerCurrentPlan
} from '../src/features/dashboard/device-preview'
const deviceId = '11111111-1111-4111-8111-111111111111'
const versionId = '22222222-2222-4222-8222-222222222222'
const model = {
  versionId,
  digestAlgorithm: 'PG_JSONB_TEXT_V1_SHA256',
  digest: 'a'.repeat(64),
  profile: 'TC_PROPERTY_COMPOSITE_V1'
}
const parse = (value: unknown) =>
  parseDashboardRuntimeResponse(new TextEncoder().encode(JSON.stringify(value)))
function fixture() {
  const chart = (id: string, y: number, propertyKey = 'temperature') => ({
    id,
    kind: 'LINE_CHART',
    componentVersion: '1.0.0',
    layout: { x: 0, y, w: 12, h: 10 },
    props: { series: [{ id: 'first', label: '温度历史' }] },
    bindings: {
      series: [
        {
          id: 'first',
          value: {
            source: 'HISTORY_SERIES',
            device: { variableKey: 'device' },
            propertyKey,
            timeRangeVariableKey: 'time',
            granularity: 'RAW',
            aggregation: 'AVG'
          }
        }
      ]
    }
  })
  const input = {
    schemaVersion: 'tc.dashboard/v1',
    presentation: { mode: 'RESPONSIVE_GRID' },
    models: [{ key: 'model', ...model }],
    variables: [
      {
        key: 'device',
        type: 'DEVICE_SINGLE',
        title: '设备',
        modelKey: 'model',
        defaultDeviceId: deviceId
      },
      {
        key: 'time',
        type: 'TIME_RANGE',
        title: '时间',
        defaultPreset: 'LAST_1_HOUR',
        allowedPresets: ['LAST_1_HOUR', 'LAST_24_HOURS', 'LAST_7_DAYS']
      }
    ],
    pages: [{ id: 'main', title: '主页', components: [chart('one', 0), chart('two', 10)] }]
  }
  const snapshots = vi.fn(async () =>
    parse({
      devices: [
        {
          deviceId,
          status: 'AVAILABLE',
          name: '设备',
          deviceStatus: 'ONLINE',
          lastOnlineAt: null,
          currentModelVersionId: versionId
        }
      ],
      models: [
        {
          ...model,
          properties: [
            {
              propertyKey: 'temperature',
              dataType: 'NUMBER',
              unit: '℃',
              minimumValue: null,
              maximumValue: null,
              enumOptions: null,
              onLabel: null,
              offLabel: null
            }
          ]
        }
      ]
    })
  )
  const current = vi.fn()
  const history = vi.fn(
    async (query: HistoryQuery): Promise<HistoryResult> => ({
      queryId: query.queryId,
      status: 'READY',
      requestedGranularity: query.granularity,
      actualGranularity: 'ONE_MINUTE',
      aggregation: query.aggregation,
      points: []
    })
  )
  const ports = {
    snapshots,
    current,
    history,
    checkCurrent: vi.fn(),
    now: () => Date.parse('2026-09-08T02:00:00Z')
  }
  const schema = () =>
    validateDashboardSchemaV1(new TextEncoder().encode(JSON.stringify(input))).schema
  return { input, schema, ports, chart }
}
describe('版本化草稿历史', () => {
  it('同轮UTC窗口与完整查询键去重，历史属性不误读current', async () => {
    const f = fixture()
    const rows = await loadDesignerDevicePreview(f.schema(), 'main', f.ports)
    expect(f.ports.current).not.toHaveBeenCalled()
    expect(f.ports.history).toHaveBeenCalledTimes(1)
    expect(f.ports.snapshots.mock.calls[0]).toBeDefined()
    expect(f.ports.history.mock.calls[0]![0]).toMatchObject({
      from: '2026-09-08T01:00:00.000Z',
      to: '2026-09-08T02:00:00.000Z',
      expectedModelVersionId: versionId
    })
    expect(rows.map((row) => row.history?.series[0]?.result?.actualGranularity)).toEqual([
      'ONE_MINUTE',
      'ONE_MINUTE'
    ])
  })
  it('运行时间不修改默认，无设备不请求，非法时间不降级', async () => {
    const f = fixture()
    await loadDesignerDevicePreview(f.schema(), 'main', f.ports, {}, { time: 'LAST_7_DAYS' })
    expect(f.ports.history.mock.calls[0]![0].from).toBe('2026-09-01T02:00:00.000Z')
    expect(f.input.variables[1]?.defaultPreset).toBe('LAST_1_HOUR')
    f.ports.snapshots.mockClear()
    f.ports.history.mockClear()
    const rows = await loadDesignerDevicePreview(f.schema(), 'main', f.ports, { device: [] })
    expect(f.ports.snapshots).not.toHaveBeenCalled()
    expect(rows[0]?.history?.series[0]?.text).toBe('请选择设备')
    await expect(
      loadDesignerDevicePreview(f.schema(), 'main', f.ports, {}, { time: 'CUSTOM' })
    ).rejects.toThrow()
  })
  it('设备不可用清图并跳过历史，业务拒绝仅标记关联序列', async () => {
    const f = fixture()
    f.ports.snapshots.mockResolvedValueOnce(
      parse({ devices: [{ deviceId, status: 'NOT_AVAILABLE' }], models: [] })
    )
    const rows = await loadDesignerDevicePreview(f.schema(), 'main', f.ports)
    expect(rows[0]?.history?.series[0]?.text).toBe('设备不可用或无权访问')
    expect(f.ports.history).not.toHaveBeenCalled()
    f.ports.history.mockImplementation(async (query) => ({
      queryId: query.queryId,
      status: 'NON_NUMERIC',
      points: []
    }))
    const rejected = await loadDesignerDevicePreview(f.schema(), 'main', f.ports)
    expect(rejected[1]?.history?.series[0]?.text).toContain('非数值')
  })
  it('11条独立查询在任何HTTP前拒绝', async () => {
    const f = fixture()
    f.input.pages[0]!.components = Array.from({ length: 11 }, (_, index) =>
      f.chart(`chart${index}`, index * 10, `property${index}`)
    )
    await expect(loadDesignerDevicePreview(f.schema(), 'main', f.ports)).rejects.toThrow('预览上限')
    expect(f.ports.snapshots).not.toHaveBeenCalled()
  })
  it('迟到历史在发布结果前复核代次', async () => {
    const f = fixture()
    f.ports.history.mockImplementation(async (query) => {
      f.ports.checkCurrent.mockImplementation(() => {
        throw new Error('stale')
      })
      return { queryId: query.queryId, status: 'READY', points: [] }
    })
    await expect(loadDesignerDevicePreview(f.schema(), 'main', f.ports)).rejects.toThrow('stale')
  })
})

it('历史专用页提供空current计划，dirty不滚动UTC窗口或重读历史', async () => {
  const f = fixture()
  let plan!: DesignerCurrentPlan
  const rows = await loadDesignerDevicePreview(f.schema(), 'main', {
    ...f.ports,
    beforeCurrentRead: async (next) => {
      plan = next
    }
  })
  expect(plan.devices).toEqual([])
  const query = f.ports.history.mock.calls[0]![0]
  const next = await refreshDesignerCurrent(plan, rows, f.ports)
  expect(next).not.toBe(rows)
  expect(next[0]).toBe(rows[0])
  expect(next[0]!.history!.series[0]!.result).toBe(rows[0]!.history!.series[0]!.result)
  expect(f.ports.history).toHaveBeenCalledOnce()
  expect(f.ports.history.mock.calls[0]![0]).toBe(query)
  expect(f.ports.current).not.toHaveBeenCalled()
  expect(f.ports.snapshots).toHaveBeenCalledOnce()
})
