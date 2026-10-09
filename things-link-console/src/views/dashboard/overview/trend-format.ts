import type { OverviewTrend, OverviewTrends } from '@/api/overview-trends'

export function formatTrendValue(value: number | null | undefined, unit?: string): string {
  if (typeof value !== 'number' || !Number.isFinite(value)) return '—'
  if (unit !== 'BYTES') return new Intl.NumberFormat('zh-CN').format(value)
  const units = ['B', 'KB', 'MB', 'GB', 'TB', 'PB']
  let size = value
  let index = 0
  while (size >= 1024 && index < units.length - 1) {
    size /= 1024
    index++
  }
  return `${Number(size.toFixed(index ? 2 : 0))} ${units[index]}`
}

/** 所有图表共用服务端窗口；时区在页面显示时转换，CSV保留明确UTC和原始字节。 */
export function bucketTimes(snapshot: OverviewTrends): string[] {
  const length = snapshot.charts?.[0]?.series?.[0]?.values?.length ?? 0
  const start = Date.parse(snapshot.from ?? '')
  return Array.from({ length }, (_, i) =>
    new Date(start + i * (snapshot.stepHours ?? 1) * 3600000).toISOString()
  )
}

function cell(value: string | number | null | undefined) {
  const text = String(value ?? '')
  // 即使将来中文标签可编辑，导出也不允许表格公式注入。
  const safe = /^[=+\-@\t\r]/.test(text) ? `'${text}` : text
  return `"${safe.replaceAll('"', '""')}"`
}

export function trendCsv(snapshot: OverviewTrends, chart: OverviewTrend): string {
  const times = bucketTimes(snapshot)
  const series = chart.series ?? []
  const rows: (string | number | null | undefined)[][] = [
    [
      '项目',
      snapshot.projectId,
      '来源',
      snapshot.source,
      '聚合',
      chart.aggregation,
      '单位',
      chart.unit
    ],
    ['UTC桶起点', 'UTC桶终点', ...series.map((s) => s.label)],
    ...times.map((time, index) => [
      time,
      new Date(Date.parse(time) + (snapshot.stepHours ?? 1) * 3600000).toISOString(),
      ...series.map((s) => s.values?.[index])
    ])
  ]
  return '\uFEFF' + rows.map((row) => row.map(cell).join(',')).join('\r\n')
}
