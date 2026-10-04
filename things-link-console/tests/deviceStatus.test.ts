import { describe, expect, it } from 'vitest'
import { deviceStatusLabel, deviceStatusTag } from '@/utils/deviceStatus'

/** A6-07：详情态不得把缺状态当作「未激活」，列表与详情共用同一套映射。 */
describe('设备状态映射（A6-07）', () => {
  it('已知状态映射中文与标签类型', () => {
    expect(deviceStatusLabel('ONLINE')).toBe('在线')
    expect(deviceStatusTag('ONLINE')).toBe('success')
    expect(deviceStatusLabel('OFFLINE')).toBe('离线')
    expect(deviceStatusTag('OFFLINE')).toBe('danger')
    expect(deviceStatusLabel('INACTIVE')).toBe('未激活')
  })

  it('缺状态不冒充「未激活」，返回中性占位', () => {
    expect(deviceStatusLabel(undefined)).toBe('—')
    expect(deviceStatusLabel('')).toBe('—')
    expect(deviceStatusTag(undefined)).toBe('info')
    expect(deviceStatusTag('')).toBe('info')
  })

  it('未知状态原样透传，不静默改成已知值', () => {
    expect(deviceStatusLabel('WEIRD')).toBe('WEIRD')
    expect(deviceStatusTag('WEIRD')).toBe('info')
  })
})
