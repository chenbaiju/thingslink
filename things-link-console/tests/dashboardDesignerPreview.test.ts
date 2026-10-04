import { describe, expect, it, vi } from 'vitest'
import {
  validateDashboardSchemaV1,
  parseDashboardRuntimeResponse
} from '@things-link/client-contracts/dashboard/v1'
import { emptyDashboard } from '../src/features/dashboard/designer-model'
import { loadDesignerDevicePreview } from '../src/features/dashboard/device-preview'
const deviceId = '11111111-1111-4111-8111-111111111111'
const versionId = '22222222-2222-4222-8222-222222222222'
const model = {
  versionId,
  digestAlgorithm: 'PG_JSONB_TEXT_V1_SHA256',
  digest: 'a'.repeat(64),
  profile: 'TC_PROPERTY_COMPOSITE_V1'
}
const value = {
  propertyKey: 'temperature',
  state: 'VALUE',
  value: 1,
  occurredAt: '2026-09-08T00:00:00Z',
  reportedModelVersionId: versionId
}
function fixture() {
  const schema = validateDashboardSchemaV1(
    new TextEncoder().encode(
      JSON.stringify({
        ...emptyDashboard(),
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
            title: '首页',
            components: [
              {
                id: 'value',
                kind: 'VALUE_CARD',
                componentVersion: '1.0.0',
                layout: { x: 0, y: 0, w: 12, h: 10 },
                props: { title: '温度', precision: 0 },
                bindings: {
                  value: {
                    source: 'CURRENT_VALUE',
                    device: { variableKey: 'device' },
                    propertyKey: 'temperature'
                  }
                }
              },
              {
                id: 'status',
                kind: 'STATUS',
                componentVersion: '1.0.0',
                layout: { x: 12, y: 0, w: 12, h: 10 },
                props: { title: '状态' },
                bindings: { status: { source: 'DEVICE_STATUS', device: { variableKey: 'device' } } }
              }
            ]
          }
        ]
      })
    )
  ).schema
  const snapshot = {
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
            unit: null,
            minimumValue: null,
            maximumValue: null,
            enumOptions: null,
            onLabel: null,
            offLabel: null
          }
        ]
      }
    ]
  }
  const snapshots = vi.fn(async () => snapshot as unknown)
  const current = vi.fn(
    async (): Promise<unknown> =>
      parseDashboardRuntimeResponse(
        new TextEncoder().encode(
          JSON.stringify({ devices: [{ deviceId, status: 'AVAILABLE', values: [value] }] }).replace(
            '"value":1',
            '"value":9007199254740993'
          )
        )
      )
  )
  return { schema, snapshot, ports: { snapshots, current, checkCurrent: vi.fn() } }
}
describe('草稿单设备快照验证', () => {
  it('去重状态和属性请求，精确展示大整数而不经过IEEE754', async () => {
    const { schema, ports } = fixture()
    const result = await loadDesignerDevicePreview(schema, 'main', ports)
    expect(result.map((entry) => entry.text)).toEqual(['9007199254740993', '在线'])
    expect(ports.snapshots.mock.calls[0]).toEqual([
      {
        models: [model],
        devices: [{ deviceId, expectedModelVersionId: versionId, propertyKeys: ['temperature'] }]
      }
    ])
    expect(ports.current).toHaveBeenCalledTimes(1)
  })
  it('设备不可用不再读当前值，也不展示旧状态', async () => {
    const { schema, ports } = fixture()
    ports.snapshots.mockResolvedValueOnce({
      devices: [{ deviceId, status: 'NOT_AVAILABLE' }],
      models: []
    })
    const result = await loadDesignerDevicePreview(schema, 'main', ports)
    expect(result.every((entry) => entry.text === '设备不可用或无权访问')).toBe(true)
    expect(ports.current).not.toHaveBeenCalled()
  })
  it('当前值复验发现撤权时连同状态快照一起清理', async () => {
    const { schema, ports } = fixture()
    ports.current.mockResolvedValueOnce({
      devices: [{ deviceId, status: 'MODEL_MISMATCH', values: [] }]
    })
    const result = await loadDesignerDevicePreview(schema, 'main', ports)
    expect(result.every((entry) => entry.text === '设备物模型已变更')).toBe(true)
  })
  it.each(['NO_VALUE', 'SOURCE_VERSION_UNKNOWN', 'SOURCE_MODEL_MISMATCH', 'CONTRACT_MISMATCH'])(
    '缺值分支%s不伪造0',
    async (state) => {
      const { schema, ports } = fixture()
      ports.current.mockResolvedValueOnce({
        devices: [
          { deviceId, status: 'AVAILABLE', values: [{ propertyKey: 'temperature', state }] }
        ]
      })
      const result = await loadDesignerDevicePreview(schema, 'main', ports)
      expect(result[0]!.text).not.toBe('0')
      expect(result[0]!.detail).toBeUndefined()
    }
  )
  it.each([
    { reportedModelVersionId: deviceId },
    { occurredAt: '2026-02-30T00:00:00Z' },
    { propertyKey: 'other' },
    { value: 12 }
  ])('拒绝错误当前值身份、时间及失去词法的数字 %j', async (patch) => {
    const { schema, ports } = fixture()
    const preciseValue = parseDashboardRuntimeResponse(
      new TextEncoder().encode('{"value":1}')
    ).value
    ports.current.mockResolvedValueOnce({
      devices: [
        { deviceId, status: 'AVAILABLE', values: [{ ...value, value: preciseValue, ...patch }] }
      ]
    })
    await expect(loadDesignerDevicePreview(schema, 'main', ports)).rejects.toThrow()
  })
  it('模型摘要漂移停止整轮，迟到检查失败后不继续读current', async () => {
    const { schema, snapshot, ports } = fixture()
    snapshot.models[0]!.digest = 'b'.repeat(64)
    await expect(loadDesignerDevicePreview(schema, 'main', ports)).rejects.toThrow()
    expect(ports.current).not.toHaveBeenCalled()
    const next = fixture()
    next.ports.checkCurrent
      .mockImplementationOnce(() => undefined)
      .mockImplementationOnce(() => {
        throw new Error('stale')
      })
    await expect(loadDesignerDevicePreview(next.schema, 'main', next.ports)).rejects.toThrow(
      'stale'
    )
    expect(next.ports.current).not.toHaveBeenCalled()
  })
})
