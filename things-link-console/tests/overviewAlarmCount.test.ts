import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { reactive, nextTick } from 'vue'
import Overview from '@/views/dashboard/overview/index.vue'
import { fetchProjectOverview, type OverviewResponse } from '@/api/overview'
import { invalidateIdentity } from '@/utils/http/identity-scope'
const state = vi.hoisted(() => ({ user: {} as any }))
vi.mock('@/store/modules/user', () => ({ useUserStore: () => state.user }))
vi.mock('@/api/overview', () => ({ fetchProjectOverview: vi.fn() }))
const snapshot = (n = 5): OverviewResponse => ({
  devices: { total: 6, online: 2, active24h: 3 },
  alarmRate: { available: true, value: n / 6 },
  alarmSeverityDeviceCounts: { NORMAL: 6 - n, CRITICAL: n, MAJOR: 0, MINOR: 0, WARNING: 0, INFO: 0 }
})
let page: VueWrapper
function render() {
  page = mount(Overview, {
    global: {
      directives: { loading: {} },
      stubs: {
        ElCard: { template: '<article><slot/></article>' },
        ElSkeleton: {
          props: ['loading'],
          template: '<div><slot v-if="loading" name="template"/><slot v-else/></div>'
        },
        ElSkeletonItem: { template: '<i class="skeleton"/>' },
        ElEmpty: { props: ['description'], template: '<p>{{description}}</p>' },
        OverviewStatistics: true,
        ArtRingChart: true,
        ArtSvgIcon: true
      }
    }
  })
}
function count() {
  return page
    .findAll('.overview-card--count')
    .find((card) => card.find('.overview-card__label').text() === '告警设备数')
}
beforeEach(() => {
  vi.resetAllMocks()
  state.user = reactive({ info: { currentProjectId: 'p', userId: 'u', tenantId: 't' } })
  vi.mocked(fetchProjectOverview).mockResolvedValue(snapshot())
})
afterEach(() => page?.unmount())
it.each([5, 0])('renders the standalone alarm device card with %i devices', async (n) => {
  vi.mocked(fetchProjectOverview).mockResolvedValue(snapshot(n))
  render()
  await flushPromises()
  expect(page.findAll('.overview-card--count')).toHaveLength(4)
  expect(count()?.find('.overview-card__value').text()).toBe(String(n))
  expect(page.text()).toContain(`${n} 台告警设备，按最高活动告警级别归类`)
})
it.each([
  { ...snapshot(), alarmSeverityDeviceCounts: undefined },
  { ...snapshot(), alarmRate: { available: false, value: 0 } },
  { ...snapshot(), devices: { total: 7 } },
  {
    ...snapshot(),
    alarmSeverityDeviceCounts: { ...snapshot().alarmSeverityDeviceCounts, CRITICAL: -1 }
  }
])('does not report unavailable or inconsistent aggregates as zero', async (value) => {
  vi.mocked(fetchProjectOverview).mockResolvedValue(value as OverviewResponse)
  render()
  await flushPromises()
  expect(count()?.find('.overview-card__value').text()).toBe('—')
  expect(page.text()).toContain('告警分布暂不可用')
})
it('shows skeletons while loading and unavailable on first request failure', async () => {
  let reject!: (reason: unknown) => void
  vi.mocked(fetchProjectOverview).mockReturnValue(
    new Promise((_, fail) => {
      reject = fail
    })
  )
  const error = vi.spyOn(console, 'error').mockImplementation(() => {})
  render()
  await nextTick()
  expect(page.findAll('.overview-card--count .skeleton')).toHaveLength(12)
  reject(new Error('offline'))
  await flushPromises()
  expect(count()?.find('.overview-card__value').text()).toBe('—')
  error.mockRestore()
})
it.each(['currentProjectId', 'userId', 'tenantId'])(
  'clears previous counts on %s change and ignores old responses',
  async (field) => {
    let resolve!: (value: OverviewResponse) => void
    vi.mocked(fetchProjectOverview).mockReturnValueOnce(
      new Promise((done) => {
        resolve = done
      })
    )
    render()
    await nextTick()
    vi.mocked(fetchProjectOverview).mockResolvedValue(snapshot(1))
    state.user.info[field] = 'other'
    await flushPromises()
    expect(count()?.find('.overview-card__value').text()).toBe('1')
    resolve(snapshot(5))
    await flushPromises()
    expect(count()?.find('.overview-card__value').text()).toBe('1')
  }
)
it('reloads on identity epoch change even for the same project/account', async () => {
  render()
  await flushPromises()
  vi.mocked(fetchProjectOverview).mockResolvedValue(snapshot(0))
  invalidateIdentity()
  await flushPromises()
  expect(count()?.find('.overview-card__value').text()).toBe('0')
})
it('clears counts when leaving the project', async () => {
  render()
  await flushPromises()
  state.user.info.currentProjectId = ''
  await flushPromises()
  expect(page.text()).toContain('请先进入一个项目')
  expect(page.findAll('.overview-card--count')).toHaveLength(0)
})
