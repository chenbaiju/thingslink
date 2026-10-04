import { describe, expect, it, vi } from 'vitest'
import {
  parseDashboardRuntimeResponse,
  validateDashboardSchemaV1,
  serializeRuntimeValue
} from '@things-link/client-contracts/dashboard/v1'
import {
  loadDesignerDevicePreview,
  refreshDesignerCurrent,
  DesignerCurrentRecoveryRequired,
  type DesignerCurrentPlan,
  type PreviewRow
} from '../src/features/dashboard/device-preview'
const deviceId = '11111111-1111-4111-8111-111111111111'
const versionId = '22222222-2222-4222-8222-222222222222'
const model = {
  versionId,
  digest: 'a'.repeat(64),
  digestAlgorithm: 'PG_JSONB_TEXT_V1_SHA256',
  profile: 'TC_PROPERTY_COMPOSITE_V1'
}
const parse = (value: unknown) =>
  parseDashboardRuntimeResponse(new TextEncoder().encode(JSON.stringify(value)))
function fixture() {
  const component = (id: string, kind: string, propertyKey: string, props: unknown) => ({
    id,
    kind,
    componentVersion: '1.0.0',
    layout: { x: 0, y: id === 'gauge' ? 0 : id === 'json' ? 10 : 20, w: 12, h: 10 },
    props,
    bindings: { value: { source: 'CURRENT_VALUE', device: { variableKey: 'device' }, propertyKey } }
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
        required: true,
        modelKey: 'model',
        defaultDeviceId: deviceId
      }
    ],
    pages: [
      {
        id: 'main',
        title: '主页面',
        components: [
          component('gauge', 'GAUGE', 'temperature', { scaleMode: 'EXPLICIT', min: 0, max: 10 }),
          component('json', 'JSON_VIEW', 'details', { initialExpandDepth: 1 }),
          component('list', 'TABLE', 'samples', { mode: 'LIST_VALUE', rowLimit: 2 })
        ]
      }
    ]
  }
  const schema = () =>
    validateDashboardSchemaV1(new TextEncoder().encode(JSON.stringify(input))).schema
  const property = (propertyKey: string, dataType: string) => ({
    propertyKey,
    dataType,
    unit: null,
    minimumValue: null,
    maximumValue: null,
    enumOptions: null,
    onLabel: null,
    offLabel: null
  })
  const snapshot = {
    devices: [
      {
        deviceId,
        status: 'AVAILABLE',
        name: '真实设备',
        deviceStatus: 'ONLINE',
        lastOnlineAt: null,
        currentModelVersionId: versionId
      }
    ],
    models: [
      {
        ...model,
        properties: [
          { ...property('temperature', 'NUMBER'), minimumValue: 0, maximumValue: 100 },
          property('details', 'OBJECT'),
          property('samples', 'LIST')
        ]
      }
    ]
  }
  const values = [
    { propertyKey: 'temperature', value: 12.5 },
    {
      propertyKey: 'details',
      value: { large: 9007199254740991, text: '<img src=x onerror=alert(1)>' }
    },
    { propertyKey: 'samples', value: ['一', '二', '三'] }
  ].map((item) => ({
    ...item,
    state: 'VALUE',
    occurredAt: '2026-09-08T00:00:00Z',
    reportedModelVersionId: versionId
  }))
  const snapshots = vi.fn(async () => parse(snapshot) as unknown)
  const current = vi.fn(
    async () =>
      parseDashboardRuntimeResponse(
        new TextEncoder().encode(
          JSON.stringify({
            devices: [
              {
                deviceId,
                status: 'AVAILABLE',
                values: [...values].sort((a, b) => a.propertyKey.localeCompare(b.propertyKey))
              }
            ]
          }).replace('9007199254740991', '9007199254740993')
        )
      ) as unknown
  )
  return { input, schema, snapshot, values, ports: { snapshots, current, checkCurrent: vi.fn() } }
}
describe('完整当前值草稿预览', () => {
  it('共用一轮元数据/当前值，保留超量程事实、精确JSON和完整列表', async () => {
    const f = fixture(),
      rows = await loadDesignerDevicePreview(f.schema(), 'main', f.ports)
    expect(rows[0]?.text).toBe('12.50')
    expect(rows[0]?.gauge).toEqual({ minimum: '0', maximum: '10', percent: 100, outOfRange: true })
    expect(serializeRuntimeValue(rows[1]!.composite!.value)).toContain('9007199254740993')
    expect(rows[2]?.composite).toMatchObject({
      mode: 'LIST',
      value: ['一', '二', '三'],
      rowLimit: 2
    })
    expect(f.ports.snapshots).toHaveBeenCalledTimes(1)
    expect(f.ports.current).toHaveBeenCalledTimes(1)
  })
  it('MODEL使用权威元数据，不猜量程；非法模型边界不能伪装有效仪表', async () => {
    const f = fixture()
    f.input.pages[0]!.components[0]!.props = { scaleMode: 'MODEL' }
    const rows = await loadDesignerDevicePreview(f.schema(), 'main', f.ports)
    expect(rows[0]!.gauge).toMatchObject({ maximum: '100', outOfRange: false })
    f.snapshot.models[0]!.properties[0]!.maximumValue = 0
    await expect(loadDesignerDevicePreview(f.schema(), 'main', f.ports)).rejects.toThrow()
  })
  it.each(['NO_VALUE', 'SOURCE_MODEL_MISMATCH', 'SOURCE_VERSION_UNKNOWN', 'CONTRACT_MISMATCH'])(
    '复合值%s不保留旧树',
    async (state) => {
      const f = fixture()
      f.ports.current.mockResolvedValueOnce({
        devices: [
          {
            deviceId,
            status: 'AVAILABLE',
            values: [...f.values]
              .sort((a, b) => a.propertyKey.localeCompare(b.propertyKey))
              .map((item) => ({ propertyKey: item.propertyKey, state }))
          }
        ]
      })
      const rows = await loadDesignerDevicePreview(f.schema(), 'main', f.ports)
      expect(rows.every((row) => !row.composite && !row.gauge)).toBe(true)
    }
  )
  it.each([[Array(257).fill('超限')], [[1, '不同类型']], [null]])(
    '拒绝非法完整LIST %j',
    async (invalid) => {
      const f = fixture()
      ;(f.values[2] as { value: unknown }).value = invalid
      await expect(loadDesignerDevicePreview(f.schema(), 'main', f.ports)).rejects.toThrow()
    }
  )
  it('禁止用错误模型类型呈现复合内容；实际current失权清整页', async () => {
    const f = fixture()
    f.snapshot.models[0]!.properties[1]!.dataType = 'TEXT'
    await expect(loadDesignerDevicePreview(f.schema(), 'main', f.ports)).rejects.toThrow()
    f.ports.current.mockResolvedValueOnce({
      devices: [{ deviceId, status: 'NOT_AVAILABLE', values: [] }]
    })
    const rows = await loadDesignerDevicePreview(f.schema(), 'main', f.ports)
    expect(
      rows.every((row) => row.text === '设备不可用或无权访问' && !row.composite && !row.gauge)
    ).toBe(true)
  })
})

describe('独立当前值计划原子刷新', () => {
  async function loaded() {
    const f = fixture()
    let plan!: DesignerCurrentPlan
    const rows = await loadDesignerDevicePreview(f.schema(), 'main', {
      ...f.ports,
      beforeCurrentRead: async (next) => {
        plan = next
      }
    })
    return { f, rows, plan }
  }
  it('元信息后等待ACK，当前键排序去重且不可变', async () => {
    const f = fixture()
    let ack!: () => void
    const gate = new Promise<void>((resolve) => {
      ack = resolve
    })
    const beforeCurrentRead = vi.fn(async (_plan: DesignerCurrentPlan) => gate)
    const loading = loadDesignerDevicePreview(f.schema(), 'main', { ...f.ports, beforeCurrentRead })
    await vi.waitFor(() => expect(beforeCurrentRead).toHaveBeenCalledOnce())
    expect(f.ports.snapshots).toHaveBeenCalledOnce()
    expect(f.ports.current).not.toHaveBeenCalled()
    const plan = beforeCurrentRead.mock.calls[0]![0]
    expect(plan.devices[0]!.propertyKeys).toEqual(['details', 'samples', 'temperature'])
    expect(Object.isFrozen(plan.devices[0]!.propertyKeys)).toBe(true)
    ack()
    await loading
    expect(f.ports.current).toHaveBeenCalledOnce()
  })
  it('元信息错误不能触发订阅', async () => {
    const f = fixture(),
      beforeCurrentRead = vi.fn(async () => {})
    f.ports.snapshots.mockResolvedValueOnce({ devices: [], models: [] })
    await expect(
      loadDesignerDevicePreview(f.schema(), 'main', { ...f.ports, beforeCurrentRead })
    ).rejects.toThrow()
    expect(beforeCurrentRead).not.toHaveBeenCalled()
  })
  it('只读一次current并保持非当前行引用与精确数字品牌', async () => {
    const { f, rows, plan } = await loaded()
    const history: PreviewRow = {
      componentId: 'history',
      title: '历史',
      text: '完成',
      history: { showLegend: true, series: [] }
    }
    const alarm: PreviewRow = {
      componentId: 'alarm',
      title: '告警',
      text: '完成',
      alarm: { showClearedAt: true, pageCursor: 'cursor' }
    }
    const next = await refreshDesignerCurrent(plan, [...rows, history, alarm], f.ports)
    expect(f.ports.current).toHaveBeenCalledTimes(2)
    expect(f.ports.snapshots).toHaveBeenCalledOnce()
    expect(next[3]).toBe(history)
    expect(next[4]).toBe(alarm)
    expect(next[1]).not.toBe(rows[1])
    expect(serializeRuntimeValue(next[1]!.composite!.value)).toContain('9007199254740993')
    expect(serializeRuntimeValue(rows[1]!.composite!.value)).toContain('9007199254740993')
  })
  it('NO_VALUE清旧图形与时间，不修改前一轮展示', async () => {
    const { f, rows, plan } = await loaded()
    f.ports.current.mockResolvedValueOnce({
      devices: [
        {
          deviceId,
          status: 'AVAILABLE',
          values: plan.devices[0]!.propertyKeys.map((propertyKey) => ({
            propertyKey,
            state: 'NO_VALUE'
          }))
        }
      ]
    })
    const next = await refreshDesignerCurrent(plan, rows, f.ports)
    expect(next.every((row) => !row.gauge && !row.composite && !row.detail)).toBe(true)
    expect(rows[0]!.gauge).toBeDefined()
    expect(rows[1]!.composite).toBeDefined()
    expect(rows[2]!.detail).toBeDefined()
  })
  it('后部属性非法不能局部修改前部复合树', async () => {
    const { f, rows, plan } = await loaded()
    f.ports.current.mockResolvedValueOnce({
      devices: [
        {
          deviceId,
          status: 'AVAILABLE',
          values: [
            { propertyKey: 'details', state: 'NO_VALUE' },
            { propertyKey: 'samples', state: 'NO_VALUE' },
            { propertyKey: 'temperature', state: 'INVALID' }
          ]
        }
      ]
    })
    await expect(refreshDesignerCurrent(plan, rows, f.ports)).rejects.toThrow()
    expect(rows[0]!.gauge).toBeDefined()
    expect(rows[1]!.composite).toBeDefined()
    expect(rows[2]!.composite).toBeDefined()
  })
  it.each(['NOT_AVAILABLE', 'MODEL_MISMATCH'])('设备%s要求完整恢复', async (status) => {
    const { f, rows, plan } = await loaded()
    f.ports.current.mockResolvedValueOnce({ devices: [{ deviceId, status, values: [] }] })
    await expect(refreshDesignerCurrent(plan, rows, f.ports)).rejects.toBeInstanceOf(
      DesignerCurrentRecoveryRequired
    )
    expect(rows[0]!.gauge).toBeDefined()
  })
  it('身份过期围栏拒绝迟到响应', async () => {
    const { f, rows, plan } = await loaded()
    const checkCurrent = vi
      .fn()
      .mockImplementationOnce(() => {})
      .mockImplementation(() => {
        throw new Error('stale')
      })
    await expect(
      refreshDesignerCurrent(plan, rows, { current: f.ports.current, checkCurrent })
    ).rejects.toThrow('stale')
    expect(rows[0]!.gauge).toBeDefined()
  })
})

it.each(['NOT_AVAILABLE', 'MODEL_MISMATCH'])(
  '首current %s等待旧订阅关闭一次才返回',
  async (status) => {
    const f = fixture()
    f.ports.current.mockResolvedValueOnce({ devices: [{ deviceId, status, values: [] }] })
    let close!: () => void
    const gate = new Promise<void>((resolve) => {
      close = resolve
    })
    const beforeCurrentRead = vi.fn(async () => {})
    const afterCurrentInvalidation = vi.fn(async () => gate)
    let completed = false
    const loading = loadDesignerDevicePreview(f.schema(), 'main', {
      ...f.ports,
      beforeCurrentRead,
      afterCurrentInvalidation
    }).then((rows) => {
      completed = true
      return rows
    })
    await vi.waitFor(() => expect(afterCurrentInvalidation).toHaveBeenCalledOnce())
    expect(beforeCurrentRead).toHaveBeenCalledOnce()
    expect(completed).toBe(false)
    close()
    const rows = await loading
    expect(afterCurrentInvalidation).toHaveBeenCalledOnce()
    expect(rows.every((row) => !row.gauge && !row.composite)).toBe(true)
  }
)
it('首current失配后的关闭失败直接传播，不返回部分结果', async () => {
  const f = fixture()
  f.ports.current.mockResolvedValueOnce({
    devices: [{ deviceId, status: 'MODEL_MISMATCH', values: [] }]
  })
  const afterCurrentInvalidation = vi.fn(async () => {
    throw new Error('close failed')
  })
  await expect(
    loadDesignerDevicePreview(f.schema(), 'main', { ...f.ports, afterCurrentInvalidation })
  ).rejects.toThrow('close failed')
  expect(afterCurrentInvalidation).toHaveBeenCalledOnce()
})
it('有效首current不关闭已确认订阅', async () => {
  const f = fixture(),
    afterCurrentInvalidation = vi.fn(async () => {})
  await loadDesignerDevicePreview(f.schema(), 'main', { ...f.ports, afterCurrentInvalidation })
  expect(afterCurrentInvalidation).not.toHaveBeenCalled()
})
