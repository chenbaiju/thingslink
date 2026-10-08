import { defineComponent, h, reactive } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter, isNavigationFailure, RouterView } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { emptyDashboard } from '@/features/dashboard/designer-model'
const mocks = vi.hoisted(() => ({
  list: vi.fn(),
  draft: vi.fn(),
  create: vi.fn(),
  save: vi.fn(),
  confirm: vi.fn(),
  device: vi.fn()
}))
let info: { currentProjectId: string; userId: string; tenantId: string; buttons: string[] }
vi.mock('@/store/modules/user', () => ({ useUserStore: () => ({ info, isLogin: true }) }))
vi.mock('@/utils/http/identity-scope', () => ({ currentIdentityEpoch: () => 1 }))
vi.mock('@/utils/http', () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() }
}))
vi.mock('@/api/device', () => ({ fetchDeviceDetail: mocks.device }))
vi.mock('@/api/dashboard', () => ({
  fetchDashboards: mocks.list,
  fetchDashboardDraft: mocks.draft,
  createDashboard: mocks.create,
  saveDashboardDraft: mocks.save
}))
vi.mock('element-plus', () => ({ ElMessageBox: { confirm: mocks.confirm } }))
import Designer from '@/views/dashboard/designer/index.vue'
const project = '00000000-0000-4000-8000-000000000100'
const dashboard = '00000000-0000-4000-8000-000000000101'
const publication = defineComponent({
  name: 'DesignerPublication',
  props: [
    'projectId',
    'dashboardId',
    'draftRevision',
    'dirty',
    'saving',
    'conflict',
    'available',
    'canRead',
    'canManage'
  ],
  emits: ['deleteLock', 'deleted'],
  template: '<section data-testid="publication-host" />'
})
const wrappers: ReturnType<typeof mount>[] = []
function silent(name: string) {
  return defineComponent({
    name,
    inheritAttrs: false,
    props: [
      'available',
      'schema',
      'model',
      'properties',
      'selected',
      'bindingModel',
      'disabled',
      'projectId',
      'dashboardId',
      'canManage'
    ],
    template: '<div />'
  })
}
async function fixture(extraQuery: Record<string, string> = {}) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/designer', component: Designer },
      { path: '/other', component: { template: '<div>其他页面</div>' } }
    ]
  })
  await router.push({
    path: '/designer',
    query: { dashboardId: dashboard, retained: 'yes', ...extraQuery }
  })
  const wrapper = mount(defineComponent({ setup: () => () => h(RouterView) }), {
    global: {
      plugins: [router],
      directives: { loading: () => undefined },
      stubs: {
        DesignerPublication: publication,
        DesignerCanvas: silent('DesignerCanvas'),
        DesignerSharing: silent('DesignerSharing'),
        DesignerGrants: silent('DesignerGrants'),
        DesignerDeviceBinding: silent('DesignerDeviceBinding'),
        DesignerText: silent('DesignerText'),
        DesignerAlarm: silent('DesignerAlarm'),
        DesignerHistory: silent('DesignerHistory'),
        DesignerVariables: silent('DesignerVariables'),
        DesignerDevicePreview: silent('DesignerDevicePreview'),
        ConsoleTableAction: true,
        ElButton: {
          props: ['disabled'],
          template: '<button :disabled="disabled"><slot /></button>'
        },
        ElAlert: { props: ['title'], template: '<p><slot />{{ title }}</p>' },
        ElTag: { template: '<span><slot /></span>' },
        ElDialog: true,
        ElCard: { template: '<div><slot /></div>' },
        ElTable: true,
        ElTableColumn: true,
        ElEmpty: true
      }
    }
  })
  wrappers.push(wrapper)
  await flushPromises()
  return { wrapper, router, child: wrapper.findComponent(publication) }
}
beforeEach(() => {
  vi.clearAllMocks()
  Object.defineProperty(navigator, 'onLine', { configurable: true, value: true })
  info = reactive({
    currentProjectId: project,
    userId: 'user',
    tenantId: 'tenant',
    buttons: ['dashboard_definition:read', 'dashboard_definition:manage']
  })
  mocks.draft.mockResolvedValue({
    dashboardId: dashboard,
    revision: '7',
    content: emptyDashboard()
  })
  mocks.list.mockResolvedValue({ items: [], hasMore: false, nextCursor: null })
})
afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  vi.useRealTimers()
})
describe('软删除设计器宿主冻结与终态', () => {
  it('路由guard resolve abort必须显示清理失败，不能冒称dashboardId已移除', async () => {
    const f = await fixture()
    const removeGuard = f.router.beforeEach((to) => !!to.query.dashboardId)
    f.child.vm.$emit('deleteLock', true)
    f.child.vm.$emit('deleted', {
      projectId: project,
      dashboardId: dashboard,
      identity: 1,
      receipt: 'COMPLETION_MARKER'
    })
    await flushPromises()
    expect(f.router.currentRoute.value.query.dashboardId).toBe(dashboard)
    expect(f.wrapper.get('[data-testid="designer-delete-cleanup-error"]').text()).toContain(
      '地址清理失败'
    )
    expect(f.wrapper.get('[data-testid="designer-delete-result"]').text()).toContain('不重放原204')
    expect(f.wrapper.findComponent(publication).exists()).toBe(false)
    expect(mocks.list).toHaveBeenCalledTimes(1)
    removeGuard()
    await f.wrapper.get('[data-testid="designer-delete-cleanup-retry"]').trigger('click')
    await flushPromises()
    expect(f.router.currentRoute.value.query.dashboardId).toBeUndefined()
    expect(mocks.draft).toHaveBeenCalledTimes(1)
  })
  it('终态地址清理拒绝也不复活或抛未处理Promise，仍刷目录并提供明确重试', async () => {
    const f = await fixture()
    vi.spyOn(f.router, 'replace').mockRejectedValueOnce(new Error('navigation failed'))
    f.child.vm.$emit('deleteLock', true)
    f.child.vm.$emit('deleted', {
      projectId: project,
      dashboardId: dashboard,
      identity: 1,
      receipt: 'NO_CONTENT'
    })
    await flushPromises()
    expect(f.wrapper.findComponent(publication).exists()).toBe(false)
    expect(f.wrapper.get('[data-testid="designer-delete-cleanup-error"]').text()).toContain(
      '地址清理失败'
    )
    expect(f.wrapper.get('[data-testid="designer-delete-result"]').text()).toContain('已软删除')
    expect(mocks.list).toHaveBeenCalledTimes(1)
    await f.wrapper.get('[data-testid="designer-delete-cleanup-retry"]').trigger('click')
    await flushPromises()
    expect(f.router.currentRoute.value.query.dashboardId).toBeUndefined()
    expect(f.wrapper.find('[data-testid="designer-delete-cleanup-error"]').exists()).toBe(false)
    expect(mocks.draft).toHaveBeenCalledTimes(1)
  })
  it('冻结时登录账号变化仍销毁编辑与旧终态，不能因close守卫保留旧资源', async () => {
    const f = await fixture()
    f.child.vm.$emit('deleteLock', true)
    await flushPromises()
    info.userId = 'another-user'
    await flushPromises()
    expect(f.wrapper.findComponent(publication).exists()).toBe(false)
    expect(f.wrapper.find('[data-testid="designer-delete-lock"]').exists()).toBe(false)
    expect(f.wrapper.find('[data-testid="designer-delete-result"]').exists()).toBe(false)
    expect(mocks.save).not.toHaveBeenCalled()
  })
  it('未知删除保留Publication/schema并暂停编辑/返回/换资源/离开/旁路运行', async () => {
    vi.useFakeTimers()
    const f = await fixture()
    f.child.vm.$emit('deleteLock', true)
    await flushPromises()
    expect(f.wrapper.get('[data-testid="designer-delete-lock"]').text()).toContain('删除结果待确认')
    expect(f.wrapper.findComponent(publication).exists()).toBe(true)
    expect(f.child.props('canManage')).toBe(true)
    expect(f.child.props('available')).toBe(true)
    expect(f.wrapper.findComponent({ name: 'DesignerSharing' }).props('available')).toBe(false)
    expect(f.wrapper.findComponent({ name: 'DesignerGrants' }).props('available')).toBe(false)
    expect(f.wrapper.findComponent({ name: 'DesignerDevicePreview' }).props('available')).toBe(
      false
    )
    expect(f.wrapper.get('[data-testid="designer-add-text"]').attributes('disabled')).toBeDefined()
    expect(f.wrapper.get('[data-testid="designer-back"]').attributes('disabled')).toBeDefined()
    await f.wrapper.get('[data-testid="designer-add-text"]').trigger('click')
    await f.wrapper.get('[data-testid="designer-back"]').trigger('click')
    await vi.advanceTimersByTimeAsync(12000)
    expect(mocks.save).not.toHaveBeenCalled()
    expect(isNavigationFailure(await f.router.push('/other'))).toBe(true)
    expect(
      isNavigationFailure(
        await f.router.push({ query: { dashboardId: '00000000-0000-4000-8000-000000000102' } })
      )
    ).toBe(true)
    expect(f.router.currentRoute.value.query.dashboardId).toBe(dashboard)
    const event = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(event)
    expect(event.defaultPrevented).toBe(true)
    expect(mocks.confirm).not.toHaveBeenCalled()
    expect(mocks.draft).toHaveBeenCalledTimes(1)
  })
  it.each(['NO_CONTENT', 'COMPLETION_MARKER'] as const)(
    '删除终态%s清编辑/绑定和路由，只读刷新目录',
    async (receipt) => {
      const f = await fixture()
      f.child.vm.$emit('deleteLock', true)
      f.child.vm.$emit('deleted', {
        projectId: project,
        dashboardId: dashboard,
        identity: 1,
        receipt
      })
      await flushPromises()
      expect(f.wrapper.findComponent(publication).exists()).toBe(false)
      expect(f.wrapper.find('[data-testid="designer-add-text"]').exists()).toBe(false)
      expect(f.router.currentRoute.value.query).toEqual({ retained: 'yes' })
      expect(mocks.list).toHaveBeenCalledTimes(1)
      expect(f.wrapper.get('[data-testid="designer-delete-result"]').text()).toContain(
        receipt === 'NO_CONTENT' ? '收到204' : '不重放原204'
      )
      expect(mocks.save).not.toHaveBeenCalled()
      expect(isNavigationFailure(await f.router.push('/other'))).toBe(false)
    }
  )
  it('目录刷新失败清空旧资源并明示重试，不把失败列表当删除失败', async () => {
    const f = await fixture()
    mocks.list.mockRejectedValueOnce(new Error('offline'))
    f.child.vm.$emit('deleteLock', true)
    f.child.vm.$emit('deleted', {
      projectId: project,
      dashboardId: dashboard,
      identity: 1,
      receipt: 'NO_CONTENT'
    })
    await flushPromises()
    expect(f.wrapper.get('[data-testid="designer-error"]').text()).toContain(
      '目录读取失败，请明确重试'
    )
    expect(f.wrapper.get('[data-testid="designer-delete-result"]').text()).toContain('已软删除')
    expect(f.router.currentRoute.value.query.dashboardId).toBeUndefined()
    expect(mocks.draft).toHaveBeenCalledTimes(1)
  })
  it('旧资源/身份终态不能关闭当前草稿或刷新目录', async () => {
    const f = await fixture()
    f.child.vm.$emit('deleted', {
      projectId: project,
      dashboardId: dashboard,
      identity: 0,
      receipt: 'NO_CONTENT'
    })
    f.child.vm.$emit('deleted', {
      projectId: 'other',
      dashboardId: dashboard,
      identity: 1,
      receipt: 'NO_CONTENT'
    })
    await flushPromises()
    expect(f.wrapper.findComponent(publication).exists()).toBe(true)
    expect(f.router.currentRoute.value.query.dashboardId).toBe(dashboard)
    expect(mocks.list).not.toHaveBeenCalled()
  })
})

it('设备来源只读核验与提示，不绑定设备、不修改已有草稿', async () => {
  const deviceId = '00000000-0000-4000-8000-000000000999'
  info.buttons.push('device:read')
  mocks.device.mockResolvedValue({ id: deviceId, name: '样板设备' })
  const f = await fixture({ deviceId, contextProjectId: project })
  expect(mocks.device).toHaveBeenCalledWith(project, deviceId)
  expect(f.wrapper.get('[aria-label="来源设备"]').text()).toContain('样板设备')
  expect(f.wrapper.get('[aria-label="来源设备"]').text()).toContain('不会自动绑定')
  expect(mocks.save).not.toHaveBeenCalled()
  expect(mocks.create).not.toHaveBeenCalled()
  expect((f.wrapper.findComponent(Designer).vm as any).$.setupState.state.schema).toEqual(
    emptyDashboard()
  )
})
