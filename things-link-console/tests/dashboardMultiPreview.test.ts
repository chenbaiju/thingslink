import { describe, expect, it, vi } from 'vitest'
import {
  validateDashboardSchemaV1,
  parseDashboardRuntimeResponse
} from '@things-link/client-contracts/dashboard/v1'
import {
  loadDesignerDevicePreview,
  refreshDesignerCurrent,
  type DesignerCurrentPlan,
  type PreviewDevice
} from '../src/features/dashboard/device-preview'
const versionId = '22222222-2222-4222-8222-222222222222'
const id = (n: number) => `11111111-1111-4111-8111-${String(n).padStart(12, '0')}`
const model = {
  versionId,
  digest: 'a'.repeat(64),
  digestAlgorithm: 'PG_JSONB_TEXT_V1_SHA256',
  profile: 'TC_PROPERTY_COMPOSITE_V1'
}
const parse = (value: unknown) =>
  parseDashboardRuntimeResponse(new TextEncoder().encode(JSON.stringify(value)))
function fixture() {
  const table = (name: string, variableKey = 'devices', y = 0) => ({
    id: name,
    kind: 'TABLE',
    componentVersion: '1.0.0',
    layout: { x: 0, y, w: 12, h: 10 },
    props: { mode: 'DEVICE_VALUES', rowLimit: 1, columns: [{ id: 'temperature', label: '温度' }] },
    bindings: {
      columns: [
        {
          id: 'temperature',
          value: { source: 'CURRENT_VALUE', device: { variableKey }, propertyKey: 'temperature' }
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
        key: 'devices',
        type: 'DEVICE_MULTI',
        title: '设备',
        modelKey: 'model',
        defaultDeviceIds: [id(1), id(2)],
        maxItems: 20
      }
    ],
    pages: [
      { id: 'main', title: '主页', components: [table('first'), table('second', 'devices', 10)] }
    ]
  }
  const schema = () =>
    validateDashboardSchemaV1(new TextEncoder().encode(JSON.stringify(input))).schema
  const snapshots = vi.fn(async ({ devices }: { devices: PreviewDevice[] }) =>
    parse({
      devices: devices.map((device) => ({
        deviceId: device.deviceId,
        status: 'AVAILABLE',
        name: `设备${device.deviceId.slice(-1)}`,
        deviceStatus: 'ONLINE',
        lastOnlineAt: null,
        currentModelVersionId: versionId
      })),
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
  const current = vi.fn(async ({ devices }: { devices: PreviewDevice[] }) =>
    parse({
      devices: devices.map((device, index) => ({
        deviceId: device.deviceId,
        status: 'AVAILABLE',
        values: [
          {
            propertyKey: 'temperature',
            state: 'VALUE',
            value: index + 0.5,
            occurredAt: '2026-09-08T00:00:00Z',
            reportedModelVersionId: versionId
          }
        ]
      }))
    })
  )
  return {
    input,
    schema,
    table,
    snapshots,
    current,
    ports: { snapshots, current, checkCurrent: vi.fn() }
  }
}
describe('完整多设备当前值计划', () => {
  it('同变量两表共享去重查询，所有选中设备与每格精确值都保留', async () => {
    const f = fixture(),
      rows = await loadDesignerDevicePreview(f.schema(), 'main', f.ports)
    expect(f.snapshots).toHaveBeenCalledTimes(1)
    expect(f.snapshots.mock.calls[0]![0].devices).toHaveLength(2)
    expect(f.current).toHaveBeenCalledTimes(1)
    for (const row of rows) {
      expect(row.table?.rows.map((item) => item.name)).toEqual(['设备1', '设备2'])
      expect(row.table?.rows.map((item) => item.cells[0]!.text)).toEqual(['0.50 ℃', '1.50 ℃'])
      expect(row.table?.rowLimit).toBe(1)
    }
  })
  it('内存选择覆盖但不改默认，明确空选择不回退首台或发请求', async () => {
    const f = fixture(),
      source = f.schema()
    const rows = await loadDesignerDevicePreview(source, 'main', f.ports, { devices: [id(3)] })
    expect(rows[0]!.table?.rows.map((item) => item.deviceId)).toEqual([id(3)])
    expect(f.input.variables[0]!.defaultDeviceIds).toEqual([id(1), id(2)])
    f.snapshots.mockClear()
    f.current.mockClear()
    const empty = await loadDesignerDevicePreview(source, 'main', f.ports, { devices: [] })
    expect(empty[0]!.text).toBe('请选择设备')
    expect(empty[0]!.table?.rows).toEqual([])
    expect(f.snapshots).not.toHaveBeenCalled()
    expect(f.current).not.toHaveBeenCalled()
  })
  it('多个变量的全页设备并集超过20时在任何HTTP前拒绝', async () => {
    const f = fixture()
    f.input.variables.push({ ...f.input.variables[0]!, key: 'others', defaultDeviceIds: [] })
    f.input.pages[0]!.components.push(f.table('third', 'others', 20))
    await expect(
      loadDesignerDevicePreview(f.schema(), 'main', f.ports, {
        devices: Array.from({ length: 20 }, (_, n) => id(n + 1)),
        others: [id(21)]
      })
    ).rejects.toThrow()
    expect(f.snapshots).not.toHaveBeenCalled()
  })
  it('当前值读取期间失权清所有表格旧值，不以目录可见代替复核', async () => {
    const f = fixture()
    f.current.mockImplementationOnce(async ({ devices }) =>
      parse({
        devices: devices.map((device) => ({
          deviceId: device.deviceId,
          status: 'NOT_AVAILABLE',
          values: []
        }))
      })
    )
    const rows = await loadDesignerDevicePreview(f.schema(), 'main', f.ports)
    for (const row of rows)
      for (const device of row.table!.rows)
        for (const cell of device.cells) {
          expect(cell.text).toBe('设备不可用或无权访问')
          expect(cell.detail).toBeUndefined()
        }
  })
  it('每个变量拒绝重复/超额选择；当前页以外组件不参与读取', async () => {
    const f = fixture()
    await expect(
      loadDesignerDevicePreview(f.schema(), 'main', f.ports, { devices: [id(1), id(1)] })
    ).rejects.toThrow()
    f.input.pages.push({ id: 'other', title: '其他', components: [f.table('hidden')] })
    const rows = await loadDesignerDevicePreview(f.schema(), 'main', f.ports)
    expect(rows.map((row) => row.componentId)).toEqual(['first', 'second'])
  })
  it.each([50, 200])('属性预算%s在请求前拒绝且不截断', async (limit) => {
    const f = fixture()
    f.input.pages[0]!.components = []
    const deviceIds = limit === 50 ? [id(1)] : [id(1), id(2), id(3), id(4), id(5)]
    const count = limit === 50 ? 51 : 41
    for (let offset = 0; offset < count; offset += 10) {
      const table = f.table(`table${offset}`, 'devices', offset * 2)
      const keys = Array.from(
        { length: Math.min(10, count - offset) },
        (_, n) => `key_${offset + n}`
      )
      table.props.columns = keys.map((key) => ({ id: key, label: key }))
      table.bindings.columns = keys.map((key) => ({
        id: key,
        value: { source: 'CURRENT_VALUE', device: { variableKey: 'devices' }, propertyKey: key }
      }))
      f.input.pages[0]!.components.push(table)
    }
    await expect(
      loadDesignerDevicePreview(f.schema(), 'main', f.ports, { devices: deviceIds })
    ).rejects.toThrow()
    expect(f.snapshots).not.toHaveBeenCalled()
  })
})

it('dirty规范计划保留多表选择顺序并复制每格，失败整批不污染', async () => {
  const f = fixture()
  let plan!: DesignerCurrentPlan
  const rows = await loadDesignerDevicePreview(
    f.schema(),
    'main',
    {
      ...f.ports,
      beforeCurrentRead: async (next) => {
        plan = next
      }
    },
    { devices: [id(2), id(1)] }
  )
  expect(plan.devices.map((device) => device.deviceId)).toEqual([id(1), id(2)])
  const next = await refreshDesignerCurrent(plan, rows, {
    ...f.ports,
    current: (body) => f.current({ devices: [...body.devices] })
  })
  for (let i = 0; i < rows.length; i++) {
    expect(next[i]!.table).not.toBe(rows[i]!.table)
    expect(next[i]!.table!.rows.map((row) => row.deviceId)).toEqual([id(2), id(1)])
    expect(next[i]!.table!.rows.map((row) => row.cells[0]!.text)).toEqual(['1.50 ℃', '0.50 ℃'])
    expect(next[i]!.table!.rows[0]!.cells[0]).not.toBe(rows[i]!.table!.rows[0]!.cells[0])
  }
  f.current.mockResolvedValueOnce(
    parse({
      devices: [
        {
          deviceId: id(1),
          status: 'AVAILABLE',
          values: [{ propertyKey: 'temperature', state: 'NO_VALUE' }]
        },
        { deviceId: id(2), status: 'AVAILABLE', values: [] }
      ]
    })
  )
  await expect(
    refreshDesignerCurrent(plan, rows, {
      ...f.ports,
      current: (body) => f.current({ devices: [...body.devices] })
    })
  ).rejects.toThrow()
  expect(rows[0]!.table!.rows[1]!.cells[0]!.text).toBe('0.50 ℃')
})
