import type { OverviewResponse } from '@/api/overview'

const categories = [
  { key: 'NORMAL', name: '正常', color: '#67C23A' },
  { key: 'CRITICAL', name: '严重', color: '#C62828' },
  { key: 'MAJOR', name: '主要', color: '#EF5350' },
  { key: 'MINOR', name: '次要', color: '#FF9800' },
  { key: 'WARNING', name: '警告', color: '#FBC02D' },
  { key: 'INFO', name: '提示', color: '#409EFF' }
] as const

/** 只展示同一服务端快照，缺失或不一致不可当作零告警。 */
export function alarmDistribution(snapshot?: OverviewResponse) {
  const counts = snapshot?.alarmSeverityDeviceCounts
  const total = snapshot?.devices?.total
  const rate = snapshot?.alarmRate
  const colors = categories.map(({ color }) => color)
  const unknown = { available: false, data: [], colors, alarmDevices: undefined }
  if (!counts || !Number.isSafeInteger(total) || total! < 0 || !rate?.available) return unknown
  const data = categories.map(({ key, name }) => ({ name, value: counts[key] }))
  if (data.some(({ value }) => !Number.isSafeInteger(value) || value! < 0)) return unknown
  if (data.reduce((sum, { value }) => sum + value!, 0) !== total) return unknown
  const alarmDevices = total! - counts.NORMAL!
  const expectedRate = total === 0 ? 0 : alarmDevices / total!
  if (
    typeof rate.value !== 'number' ||
    !Number.isFinite(rate.value) ||
    Math.abs(rate.value - expectedRate) > 1e-12
  )
    return unknown
  return {
    available: true,
    data: data.map(({ name, value }) => ({ name, value: value! })),
    colors,
    alarmDevices
  }
}
