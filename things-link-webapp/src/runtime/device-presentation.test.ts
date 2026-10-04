import { describe, expect, it } from 'vitest'
import type { RuntimeNumber, RuntimeProperty } from './device-data'
import { acceptsDeviceSelection, deviceTableIdentity, localTablePage, formatDecimal, formatScalar, gaugePosition, stateLabel } from './device-presentation'
const number = (lexical: string): RuntimeNumber => ({ kind: 'NUMBER', lexical })
const property: RuntimeProperty = { propertyKey: 'temperature', dataType: 'NUMBER', unit: '℃', minimumValue: number('-50'), maximumValue: number('150'), enumOptions: null, onLabel: null, offLabel: null }

describe('设备纯呈现事实', () => {
  it('保留原十进制词法，四舍五入不修改事实或把非法值显示成零', () => {
    expect(formatDecimal(number('1.005'), 2)).toBe('1.01')
    expect(formatDecimal(number('-1.005'), 2)).toBe('-1.01')
    expect(formatDecimal(number('9007199254740993'), 2)).toBe('9007199254740993.00')
    expect(formatDecimal(number('0'), 2)).toBe('0.00')
    expect(formatDecimal(number('1e-300'), 6)).toBe('0.000000')
    expect(formatDecimal(number('1e-9999999'), 6)).toBe('0.000000')
    expect(formatDecimal(number('0e999999'), 6)).toBe('0.000000')
    const longCoefficient = `1005${'0'.repeat(99996)}e-99999`
    expect(formatDecimal(number(longCoefficient), 2)).toBe('1.01')
    expect(formatDecimal(number(`9995${'0'.repeat(99996)}e-99999`), 2)).toBe('10.00')
    expect(formatDecimal(number('1e309'), 2)).toBeNull()
    expect(formatScalar(number('12.50'), property, 1, 'MODEL')).toBe('12.5 ℃')
    expect(formatScalar(number('12.50'), property, 1, 'NONE')).toBe('12.5')
  })
  it('SWITCH零值和ENUM严格按模型，TEXT只传回原文', () => {
    expect(formatScalar(false, { ...property, dataType: 'SWITCH', unit: null, offLabel: '停机' }, 2, 'MODEL')).toBe('停机')
    expect(formatScalar(true, { ...property, dataType: 'SWITCH', unit: null }, 2, 'MODEL')).toBe('开启')
    expect(formatScalar('running', { ...property, dataType: 'ENUM', enumOptions: ['ready'], unit: null }, 2, 'MODEL')).toBeNull()
    expect(formatScalar('<script>文本</script>', { ...property, dataType: 'TEXT', unit: null }, 2, 'MODEL')).toBe('<script>文本</script>')
  })
  it('量程夹指针但保留真实越界事实，MODEL边界须合法', () => {
    expect(gaugePosition(number('175.25'), number('-50'), number('150'))).toEqual({ minimum: '-50', maximum: '150', percent: 100, outOfRange: true })
    expect(gaugePosition(number('1.0000000000000000001'), 0, 1)?.outOfRange).toBe(true)
    expect(gaugePosition(number('-0.0000000000000000001'), 0, 1)?.outOfRange).toBe(true)
    expect(gaugePosition(number('0'), number('-50'), number('150'))?.percent).toBe(25)
    for (const [low, high] of [[null, 1], [1, 1], [2, 1], [0, 1e13], [0, 1e-13], [0, number('1e-999')]] as const) {
      expect(gaugePosition(number('0'), low, high)).toBeNull()
    }
  })
  it('没有采集值、来源和模型失败不能折叠为同一空状态', () => {
    const states = ['UNSELECTED', 'NO_VALUE', 'SOURCE_VERSION_UNKNOWN', 'SOURCE_MODEL_MISMATCH', 'CONTRACT_MISMATCH', 'NOT_AVAILABLE', 'MODEL_MISMATCH']
    expect(new Set(states.map(state => stateLabel(state))).size).toBe(states.length)
    expect(stateLabel('UNSELECTED', false)).toBe('未选择设备')
    expect(stateLabel('INACTIVE')).toBe('未激活')
  })
  it('完整多选超上限或重复即拒绝，不截断且允许明确空集合', () => {
    expect(acceptsDeviceSelection(['a', 'b'], 2)).toBe(true)
    expect(acceptsDeviceSelection(['a', 'b', 'c'], 2)).toBe(false)
    expect(acceptsDeviceSelection(['a', 'a'], 2)).toBe(false)
    expect(acceptsDeviceSelection([], 2)).toBe(true)
  })
  it('rowLimit只控制本地页，所有行可达且撤选后页码回合法范围', () => {
    const rows = ['a', 'b', 'c']
    expect([0, 1, 2].flatMap(page => localTablePage(rows, page, 1).rows)).toEqual(rows)
    expect(localTablePage(['a'], 2, 1)).toEqual({ rows: ['a'], index: 0, count: 1, total: 1 })
    expect(localTablePage([], 2, 1)).toEqual({ rows: [], index: 0, count: 1, total: 0 })
  })
  it('表格以current撤权/模型漂移覆盖早先可见元信息，不展示旧名称', () => {
    const snapshot = { deviceId: 'a', status: 'AVAILABLE' as const, name: '旧私密设备名', deviceStatus: 'ONLINE' as const, lastOnlineAt: null, currentModelVersionId: 'model' }
    expect(deviceTableIdentity(snapshot, { deviceId: 'a', status: 'AVAILABLE', values: [] }).name).toBe('旧私密设备名')
    for (const status of ['NOT_AVAILABLE', 'MODEL_MISMATCH'] as const) {
      const result = deviceTableIdentity(snapshot, { deviceId: 'a', status, values: [] })
      expect(result.state).toBe(status)
      expect(result.name).not.toContain('旧私密设备名')
    }
  })

  it('快照明确不可用且不再读取current时保留权威设备状态', () => {
    expect(deviceTableIdentity({ deviceId: 'a', status: 'NOT_AVAILABLE' }, undefined)).toEqual({
      state: 'NOT_AVAILABLE', name: stateLabel('NOT_AVAILABLE'),
    })
    expect(deviceTableIdentity({ deviceId: 'a', status: 'MODEL_MISMATCH', currentModelVersionId: 'changed' }, undefined)).toEqual({
      state: 'MODEL_MISMATCH', name: stateLabel('MODEL_MISMATCH'),
    })
  })

})
