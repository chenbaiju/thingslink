import { describe, expect, it } from 'vitest'
import { alarmDistribution } from '@/utils/alarm-distribution'
import type { OverviewResponse } from '@/api/overview'

const snapshot = (): OverviewResponse => ({
  devices: { total: 12 },
  alarmRate: { available: true, value: 5 / 12 },
  alarmSeverityDeviceCounts: { NORMAL: 7, CRITICAL: 1, MAJOR: 1, MINOR: 1, WARNING: 1, INFO: 1 }
})

describe('概要告警设备分布', () => {
  it('直接展示服务端的六类设备计数，不从百分比估算设备数', () => {
    const result = alarmDistribution(snapshot())
    expect(result.available).toBe(true)
    expect(result.alarmDevices).toBe(5)
    expect(result.data).toEqual([
      { name: '正常', value: 7 },
      { name: '严重', value: 1 },
      { name: '主要', value: 1 },
      { name: '次要', value: 1 },
      { name: '警告', value: 1 },
      { name: '提示', value: 1 }
    ])
    expect(new Set(result.colors).size).toBe(6)
  })
  it('零设备快照可用且告警数量为零', () => {
    const result = alarmDistribution({
      devices: { total: 0 },
      alarmRate: { available: true, value: 0 },
      alarmSeverityDeviceCounts: { NORMAL: 0, CRITICAL: 0, MAJOR: 0, MINOR: 0, WARNING: 0, INFO: 0 }
    })
    expect(result.available).toBe(true)
    expect(result.alarmDevices).toBe(0)
  })
  it('旧后端缺失分布不能显示为全部正常', () => {
    expect(
      alarmDistribution({ devices: { total: 12 }, alarmRate: { available: true, value: 0 } })
        .available
    ).toBe(false)
    expect(alarmDistribution().available).toBe(false)
  })
  it.each(['missing', 'negative', 'fraction', 'total', 'rate', 'unavailable'])(
    '拒绝不完整或不一致的快照：%s',
    (fault) => {
      const value = snapshot()
      if (fault === 'missing') value.alarmSeverityDeviceCounts!.INFO = undefined
      if (fault === 'negative') value.alarmSeverityDeviceCounts!.INFO = -1
      if (fault === 'fraction') value.alarmSeverityDeviceCounts!.INFO = 0.5
      if (fault === 'total') value.devices!.total = 11
      if (fault === 'rate') value.alarmRate!.value = 0.99
      if (fault === 'unavailable') value.alarmRate!.available = false
      const result = alarmDistribution(value)
      expect(result.available).toBe(false)
      expect(result.data).toEqual([])
      expect(result.alarmDevices).toBeUndefined()
    }
  )
})
