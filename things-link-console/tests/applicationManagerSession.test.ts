import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { reactive, defineComponent, ref, nextTick } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
const mocks = vi.hoisted(() => ({
  user: {} as {
    info: { currentProjectId: string; userId: string; tenantId: string; buttons: string[] }
  },
  confirm: vi.fn(),
  leave: undefined as undefined | (() => Promise<boolean>),
  list: vi.fn(),
  draft: vi.fn(),
  create: vi.fn(),
  dashboards: vi.fn(),
  versions: vi.fn()
}))
vi.mock('@/store/modules/user', () => ({ useUserStore: () => mocks.user }))
vi.mock('@/utils/http/identity-scope', () => ({ currentIdentityEpoch: () => 1 }))
vi.mock('vue-router', () => ({
  onBeforeRouteLeave: (guard: () => Promise<boolean>) => {
    mocks.leave = guard
  }
}))
vi.mock('element-plus', () => ({ ElMessageBox: { confirm: mocks.confirm } }))
vi.mock('@/api/application', () => ({
  fetchApplications: mocks.list,
  fetchApplicationDraft: mocks.draft,
  createApplication: mocks.create,
  saveApplicationDraft: vi.fn()
}))
vi.mock('@/api/dashboard', () => ({ fetchDashboards: mocks.dashboards }))
vi.mock('@/api/dashboard-publication', () => ({ fetchDashboardPublicationHistory: mocks.versions }))
vi.mock('@/views/dashboard/applications/components/ApplicationPublication.vue', () => ({
  default: { name: 'ApplicationPublication', template: '<div />' }
}))
import Manager from '@/views/dashboard/applications/index.vue'
const id = '11111111-1111-4111-8111-111111111111'
const other = '22222222-2222-4222-8222-222222222222'
const Publication = defineComponent({
  name: 'ApplicationPublication',
  props: ['applicationId'],
  emits: ['pending', 'working', 'access-denied'],
  setup() {
    const intent = ref('UNKNOWN-original-key')
    return { intent }
  },
  template: '<div data-testid="publication">{{ intent }}</div>'
})
let wrapper: VueWrapper | undefined
const click = async (label: string) => {
  const button = wrapper!.findAll('button').find((item) => item.text() === label)
  expect(button, label).toBeDefined()
  await button!.trigger('click')
  await flushPromises()
}
const editApplication = async (name: string) => {
  const item = wrapper!
    .findAll('[aria-label="应用目录"] li')
    .find((entry) => entry.find('span').text() === name)
  expect(item, name).toBeDefined()
  const button = item!.find('button')
  expect(button.text()).toBe('编辑')
  await button.trigger('click')
  await flushPromises()
}
async function loaded() {
  wrapper = mount(Manager, {
    global: {
      stubs: {
        ApplicationPublication: Publication,
        ElButton: {
          props: ['disabled'],
          template: '<button :disabled="disabled"><slot /></button>'
        },
        ElAlert: { props: ['title'], template: '<p>{{ title }}</p>' }
      }
    }
  })
  await click('读取应用目录')
  await editApplication('应用甲')
  expect(wrapper.find('[aria-label="应用草稿"]').exists()).toBe(true)
  return wrapper.findComponent(Publication)
}
beforeEach(() => {
  vi.clearAllMocks()
  mocks.user = reactive({
    info: {
      currentProjectId: 'project',
      userId: 'user',
      tenantId: 'tenant',
      buttons: ['application:read', 'application:manage', 'dashboard_definition:read']
    }
  })
  mocks.confirm.mockResolvedValue('confirm')
  mocks.list.mockResolvedValue({
    items: [
      { id, managementName: '应用甲' },
      { id: other, managementName: '应用乙' }
    ],
    hasMore: false,
    nextCursor: null
  })
  mocks.draft.mockImplementation(async (_project, applicationId) => ({
    applicationId,
    revision: '1',
    updatedAt: '2026-09-12T00:00:00Z',
    content: {
      formatVersion: 'tc.application/v1',
      displayName: '原稿',
      hostCompatibility: { minInclusive: '1.0.0', maxExclusive: '2.0.0' },
      dashboardRefs: [],
      entryDashboardId: null
    }
  }))
  mocks.dashboards.mockResolvedValue({
    items: [{ id: other, managementName: '引用候选' }],
    hasMore: false,
    nextCursor: null
  })
})
afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
})
describe('应用管理身份与发布恢复接线', () => {
  it('等值info对象替换保留本地草稿及同一发布实例', async () => {
    const child = await loaded(),
      instance = child.vm.$.uid
    await wrapper!.get('[aria-label="公开展示名"]').setValue('未保存修改')
    child.vm.$emit('pending', true)
    mocks.user.info = { ...mocks.user.info, buttons: [...mocks.user.info.buttons] }
    await nextTick()
    expect(wrapper!.findComponent(Publication).vm.$.uid).toBe(instance)
    expect((wrapper!.get('[aria-label="公开展示名"]').element as HTMLInputElement).value).toBe(
      '未保存修改'
    )
    expect(child.text()).toContain('UNKNOWN-original-key')
  })
  it('只改变看板读取权限清候选，保留发布UNKNOWN和草稿', async () => {
    const child = await loaded(),
      instance = child.vm.$.uid
    child.vm.$emit('pending', true)
    await click('读取看板目录')
    expect(wrapper!.get('[aria-label="看板"]').text()).toContain('引用候选')
    mocks.user.info.buttons = ['application:read', 'application:manage']
    await nextTick()
    expect(wrapper!.findComponent(Publication).vm.$.uid).toBe(instance)
    mocks.user.info.buttons.push('dashboard_definition:read')
    await nextTick()
    expect(wrapper!.get('[aria-label="看板"]').text()).not.toContain('引用候选')
    expect(child.text()).toContain('UNKNOWN-original-key')
    mocks.confirm.mockRejectedValueOnce('cancel')
    expect(await mocks.leave!()).toBe(false)
    expect(mocks.confirm).toHaveBeenCalledOnce()
  })
  it.each(['currentProjectId', 'userId', 'tenantId'] as const)(
    '真实%s改变清草稿与发布实例',
    async (field) => {
      await loaded()
      mocks.user.info[field] = 'different'
      await nextTick()
      expect(wrapper!.find('[aria-label="应用草稿"]').exists()).toBe(false)
      expect(wrapper!.findComponent(Publication).exists()).toBe(false)
    }
  )
  it('发布读取失权立即清除编辑内容', async () => {
    const child = await loaded()
    child.vm.$emit('access-denied')
    await nextTick()
    expect(wrapper!.find('[aria-label="应用草稿"]').exists()).toBe(false)
    expect(wrapper!.text()).toContain('应用或读取权限已失效')
  })
  it('UNKNOWN离开必须明确丢弃，取消保持原实例', async () => {
    const child = await loaded(),
      instance = child.vm.$.uid
    child.vm.$emit('pending', true)
    mocks.confirm.mockRejectedValueOnce('cancel')
    expect(await mocks.leave!()).toBe(false)
    expect(wrapper!.findComponent(Publication).vm.$.uid).toBe(instance)
    mocks.confirm.mockResolvedValueOnce('confirm')
    expect(await mocks.leave!()).toBe(true)
    expect(mocks.confirm).toHaveBeenLastCalledWith(
      expect.stringContaining('离开不会撤销已提交的请求'),
      '离开应用编辑'
    )
  })
  it('写入进行中禁止切换应用、创建和离开', async () => {
    const child = await loaded(),
      instance = child.vm.$.uid
    child.vm.$emit('working', true)
    await nextTick()
    await editApplication('应用乙')
    await click('创建应用')
    expect(mocks.draft).toHaveBeenCalledOnce()
    expect(mocks.create).not.toHaveBeenCalled()
    expect(mocks.confirm).not.toHaveBeenCalled()
    expect(await mocks.leave!()).toBe(false)
    expect(wrapper!.findComponent(Publication).vm.$.uid).toBe(instance)
  })
})
