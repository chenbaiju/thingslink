import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import Statistics from '@/views/dashboard/overview/components/OverviewStatistics.vue'
import { fetchOverviewTrends, type OverviewTrends } from '@/api/overview-trends'
import { bucketTimes, formatTrendValue, trendCsv } from '@/views/dashboard/overview/trend-format'
import { invalidateIdentity } from '@/utils/http/identity-scope'

vi.mock('@/api/overview-trends', () => ({ fetchOverviewTrends: vi.fn() }))
const response = (projectId = 'p'): OverviewTrends => ({
  projectId,
  source: 'SIMULATED',
  from: '2026-10-08T00:00:00Z',
  to: '2026-10-09T00:00:00Z',
  stepHours: 1,
  charts: [
    {
      key: 'mqtt',
      title: '设备MQTT流量',
      unit: 'BYTES',
      aggregation: 'SUM',
      series: [{ key: 'bytes.mqttTotal', label: 'MQTT总流量', values: [0, null, 2048] }]
    }
  ]
})
let page: VueWrapper
function render() {
  page = mount(Statistics, {
    props: { projectId: 'p', identity: 'p:u:t' },
    global: {
      stubs: {
        ElCard: { template: '<article><slot /></article>' },
        ElTag: { template: '<span><slot /></span>' },
        ElSkeleton: true,
        OverviewTrendChart: true,
        ElButton: {
          props: ['disabled', 'loading'],
          template: '<button :disabled="disabled || loading"><slot /></button>'
        },
        ElRadioGroup: {
          name: 'ElRadioGroup',
          props: ['modelValue'],
          template: '<div><slot /></div>'
        },
        ElRadioButton: { template: '<button><slot /></button>' }
      }
    }
  })
}
beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(fetchOverviewTrends).mockResolvedValue(response())
})
afterEach(() => page?.unmount())
it('reads the current project and explicitly marks simulation', async () => {
  render()
  await flushPromises()
  expect(fetchOverviewTrends).toHaveBeenCalledWith('p', 1)
  expect(page.text()).toContain('模拟统计')
  expect(page.findAll('overview-trend-chart-stub')).toHaveLength(1)
})
it('clears a changed range and refuses an old response', async () => {
  let first!: (s: OverviewTrends) => void
  vi.mocked(fetchOverviewTrends).mockReturnValueOnce(
    new Promise((resolve) => {
      first = resolve
    })
  )
  render()
  page.findComponent({ name: 'ElRadioGroup' }).vm.$emit('update:modelValue', 30)
  await flushPromises()
  expect(fetchOverviewTrends).toHaveBeenLastCalledWith('p', 30)
  first({ ...response(), source: 'OBSERVED' })
  await flushPromises()
  expect(page.text()).toContain('模拟统计')
})
it('rejects a mismatched project and late previous project result', async () => {
  let first!: (s: OverviewTrends) => void
  vi.mocked(fetchOverviewTrends).mockReturnValueOnce(
    new Promise((resolve) => {
      first = resolve
    })
  )
  render()
  await page.setProps({ projectId: 'q', identity: 'q:u:t' })
  await flushPromises()
  expect(page.text()).toContain('统计读取失败')
  first(response())
  await flushPromises()
  expect(page.findAll('overview-trend-chart-stub')).toHaveLength(0)
  vi.mocked(fetchOverviewTrends).mockResolvedValue(response('q'))
  await page
    .findAll('button')
    .find((b) => b.text() === '刷新')!
    .trigger('click')
  await flushPromises()
  expect(page.findAll('overview-trend-chart-stub')).toHaveLength(1)
})
it('preserves same-range results on failure and clears on identity change', async () => {
  render()
  await flushPromises()
  vi.mocked(fetchOverviewTrends).mockRejectedValue(new Error('offline'))
  await page
    .findAll('button')
    .find((b) => b.text() === '刷新')!
    .trigger('click')
  await flushPromises()
  expect(page.text()).toContain('当前保留上次结果')
  expect(page.findAll('overview-trend-chart-stub')).toHaveLength(1)
  await page.setProps({ identity: 'p:another:t' })
  await flushPromises()
  expect(page.findAll('overview-trend-chart-stub')).toHaveLength(0)
})
it('refuses a result after logout and disables export for missing samples', async () => {
  let complete!: (s: OverviewTrends) => void
  vi.mocked(fetchOverviewTrends).mockReturnValueOnce(
    new Promise((resolve) => {
      complete = resolve
    })
  )
  render()
  invalidateIdentity()
  complete(response())
  await flushPromises()
  expect(page.findAll('overview-trend-chart-stub')).toHaveLength(0)
  vi.mocked(fetchOverviewTrends).mockResolvedValue({
    ...response(),
    source: 'NONE',
    charts: [
      { ...response().charts![0], series: [{ key: 'k', label: '空', values: [null, null] }] }
    ]
  })
  await page.setProps({ identity: 'p:new:t' })
  await flushPromises()
  expect(
    page
      .findAll('button')
      .find((b) => b.text() === '导出')!
      .attributes('disabled')
  ).toBeDefined()
})
it('exports UTC buckets, raw bytes, source and empty missing values; formats explicit zero', () => {
  const s = response()
  const csv = trendCsv(s, s.charts![0])
  expect(csv).toContain('"SIMULATED"')
  expect(csv).toContain('"2026-10-08T01:00:00.000Z","2026-10-08T02:00:00.000Z",""')
  expect(csv).toContain('"2048"')
  expect(bucketTimes(s)).toHaveLength(3)
  expect(formatTrendValue(2048, 'BYTES')).toBe('2 KB')
  expect(formatTrendValue(null)).toBe('—')
  expect(formatTrendValue(0)).toBe('0')
  s.charts![0].series![0].label = '=1+1'
  expect(trendCsv(s, s.charts![0])).toContain('"\'=1+1"')
})
