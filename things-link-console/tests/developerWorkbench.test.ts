import { memoryStorage } from './commercialStorageFixture'
import { beforeEach, afterEach, it, expect, vi } from 'vitest'
import { reactive } from 'vue'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import Workbench from '@/views/dashboard/workbench/index.vue'
import { fetchProjectOverview } from '@/api/overview'
import { fetchProjectQuota, type ProjectQuotaOverviewResponse } from '@/api/quota'
import WorkbenchPlanOverview from '@/views/dashboard/workbench/WorkbenchPlanOverview.vue'
import WorkbenchResources from '@/views/dashboard/workbench/WorkbenchResources.vue'
import { formatTime } from '@/utils/time'
const state = vi.hoisted(() => ({ user: {} as any, menu: {} as any, push: vi.fn() }))
vi.mock('@/store/modules/user', () => ({ useUserStore: () => state.user }))
vi.mock('@/store/modules/menu', () => ({ useMenuStore: () => state.menu }))
vi.mock('vue-router', () => ({ useRouter: () => ({ push: state.push }) }))
vi.mock('@/utils/http', () => ({ default: { get: vi.fn() } }))
vi.mock('@/api/overview', () => ({ fetchProjectOverview: vi.fn() }))
vi.mock('@/api/quota', () => ({ fetchProjectQuota: vi.fn() }))
vi.mock('@/api/project', () => ({
  fetchProjects: vi.fn(async () => [{ id: 'p', name: '开发项目' }])
}))
let wrapper: VueWrapper
const snapshot = {
  devices: { total: 6, online: 2, active24h: 4 },
  alarmRate: { available: true, value: 0.5 },
  alarmSeverityDeviceCounts: { NORMAL: 3, CRITICAL: 3, MAJOR: 0, MINOR: 0, WARNING: 0, INFO: 0 }
}
function render() {
  wrapper = mount(Workbench, {
    global: {
      stubs: {
        ElButton: { template: '<button><slot/></button>' },
        ElSkeleton: { template: '<div><slot/></div>' },
        ElAlert: { props: ['title'], template: '<div>{{title}}<slot/></div>' },
        ElDrawer: true,
        ElEmpty: true,
        ArtSvgIcon: true,
        WorkbenchResources: true
      }
    }
  })
}
beforeEach(() => {
  vi.clearAllMocks()
  vi.stubGlobal('localStorage', memoryStorage())
  localStorage.clear()
  state.user = reactive({
    info: { currentProjectId: 'p', userId: 'u', tenantId: 't', roles: ['OWNER'] }
  })
  state.menu = reactive({
    menuList: ['/dashboard/overview', '/device/types', '/project/list'].map((path) => ({ path }))
  })
  vi.mocked(fetchProjectOverview).mockResolvedValue(snapshot)
})
afterEach(() => wrapper?.unmount())
it('shows authoritative alarm count and disables inaccessible steps', async () => {
  render()
  await flushPromises()
  expect(wrapper.findAll('.workbench-metrics strong').map((node) => node.text())).toEqual([
    '6',
    '2',
    '4',
    '3'
  ])
  expect(wrapper.findAll('.development-step:disabled')).toHaveLength(4)
  expect(wrapper.text()).toContain('开发项目')
  expect(wrapper.find('.workbench-help').exists()).toBe(false)
  expect(wrapper.findComponent(WorkbenchResources).exists()).toBe(true)
  expect(wrapper.text()).not.toContain('统计生成于')
})
it('ignores delayed results after project changes', async () => {
  let release!: (value: typeof snapshot) => void
  vi.mocked(fetchProjectOverview).mockReturnValueOnce(
    new Promise((resolve) => {
      release = resolve
    })
  )
  render()
  state.user.info.currentProjectId = 'other'
  vi.mocked(fetchProjectOverview).mockRejectedValue(new Error('offline'))
  await flushPromises()
  release(snapshot)
  await flushPromises()
  expect(wrapper.text()).toContain('运行摘要暂不可用')
  expect(wrapper.findAll('.workbench-metrics strong').every((node) => node.text() === '—')).toBe(
    true
  )
  expect(wrapper.text()).not.toContain('开发项目')
  expect(wrapper.find('.workbench-help').exists()).toBe(false)
  expect(wrapper.findAll('.workbench-traffic__value').map((node) => node.text())).toEqual([
    '—',
    '—'
  ])
  expect(wrapper.get('.workbench-window strong').text()).toBe('—')
})
it('shows backend traffic and window below the runtime summary using the same overview request', async () => {
  const from = '2026-10-06T12:00:00Z'
  const to = '2026-10-07T12:00:00Z'
  vi.mocked(fetchProjectOverview).mockResolvedValue({
    ...snapshot,
    messages24h: { count: 1234, bytes: 1536 },
    window: { from, to },
    generatedAt: to
  })
  render()
  await flushPromises()
  expect(wrapper.findAll('.workbench-traffic__value').map((node) => node.text())).toEqual([
    '1,234',
    '1.5 KiB'
  ])
  expect(wrapper.findAll('.workbench-window strong').map((node) => node.text())).toEqual([
    `${formatTime(from)} 至 ${formatTime(to)}`,
    formatTime(to)
  ])
  expect(
    wrapper
      .findAll('.workbench-main > section')
      .map((node) => node.attributes('aria-label') ?? node.attributes('aria-labelledby'))
  ).toEqual(['recent-heading', 'snapshot-heading', '消息流量概览'])
  expect(wrapper.findAll('.workbench-traffic > section')).toHaveLength(3)
  expect(fetchProjectOverview).toHaveBeenCalledTimes(1)
  vi.mocked(fetchProjectOverview).mockResolvedValue({
    ...snapshot,
    messages24h: { count: 0, bytes: 0 }
  })
  state.user.info.currentProjectId = 'other'
  await flushPromises()
  expect(wrapper.findAll('.workbench-traffic__value').map((node) => node.text())).toEqual([
    '0',
    '0 B'
  ])
  expect(wrapper.get('.workbench-window strong').text()).toBe('—')
})
it('shows the first-device guide only after an explicit zero count and hides it while switching projects', async () => {
  let release!: (value: typeof snapshot) => void
  vi.mocked(fetchProjectOverview).mockReturnValueOnce(
    new Promise((resolve) => {
      release = resolve
    })
  )
  render()
  await flushPromises()
  expect(wrapper.find('.workbench-help').exists()).toBe(false)
  release({ ...snapshot, devices: { total: 0, online: 0, active24h: 0 } })
  await flushPromises()
  expect(wrapper.find('.workbench-help').exists()).toBe(true)
  expect(wrapper.findComponent(WorkbenchResources).exists()).toBe(false)
  vi.mocked(fetchProjectOverview).mockResolvedValue({})
  state.user.info.currentProjectId = 'other'
  await flushPromises()
  expect(wrapper.find('.workbench-help').exists()).toBe(false)
})
it('uses current-project quota device count rather than another project in the shared pool', async () => {
  state.menu.menuList = [{ path: '/project/settings' }]
  vi.mocked(fetchProjectQuota).mockResolvedValue({
    project: { deviceCount: { used: 0 } },
    tenantSharedPool: { deviceCount: { used: 8 } }
  })
  render()
  await flushPromises()
  expect(wrapper.find('.workbench-help').exists()).toBe(true)
  vi.mocked(fetchProjectQuota).mockResolvedValue({ project: { deviceCount: { used: 1 } } })
  state.user.info.currentProjectId = 'other'
  await flushPromises()
  expect(wrapper.find('.workbench-help').exists()).toBe(false)
})
it('does not fetch without overview access or a project', async () => {
  state.menu.menuList = [{ path: '/project/list' }]
  render()
  await flushPromises()
  expect(fetchProjectOverview).not.toHaveBeenCalled()
  expect(fetchProjectQuota).not.toHaveBeenCalled()
  expect(wrapper.find('.workbench-traffic').exists()).toBe(false)
  expect(wrapper.find('.workbench-window').exists()).toBe(false)
  state.user.info.currentProjectId = ''
  await flushPromises()
  expect(wrapper.find('.development-steps').exists()).toBe(false)
})
it('loads quota with resource access even without overview access and ignores an old project response', async () => {
  state.menu.menuList = [{ path: '/project/settings' }, { path: '/plan-catalog' }]
  let release!: (value: ProjectQuotaOverviewResponse) => void
  vi.mocked(fetchProjectQuota)
    .mockReturnValueOnce(new Promise((resolve) => (release = resolve)))
    .mockResolvedValueOnce({ projectId: 'other', memberCount: 2 })
  render()
  await flushPromises()
  expect(fetchProjectOverview).not.toHaveBeenCalled()
  expect(fetchProjectQuota).toHaveBeenCalledWith('p')
  state.user.info.currentProjectId = 'other'
  await flushPromises()
  release({ projectId: 'p', memberCount: 99 })
  await flushPromises()
  expect(wrapper.findComponent(WorkbenchPlanOverview).props('overview')).toEqual({
    projectId: 'other',
    memberCount: 2
  })
  expect(wrapper.findComponent(WorkbenchPlanOverview).props('loading')).toBe(false)
  await wrapper.findComponent(WorkbenchPlanOverview).get('button[aria-label]').trigger('click')
  expect(state.push).toHaveBeenCalledWith('/project/settings')
})
it('clears quota after resource access is lost and does not show the panel without a project', async () => {
  state.menu.menuList.push({ path: '/project/settings' })
  vi.mocked(fetchProjectQuota).mockResolvedValue({ memberCount: 3 })
  render()
  await flushPromises()
  expect(wrapper.findComponent(WorkbenchPlanOverview).exists()).toBe(true)
  state.menu.menuList = [{ path: '/project/list' }]
  await flushPromises()
  expect(wrapper.findComponent(WorkbenchPlanOverview).exists()).toBe(false)
  state.user.info.currentProjectId = ''
  await flushPromises()
  expect(fetchProjectQuota).toHaveBeenCalledTimes(1)
})
