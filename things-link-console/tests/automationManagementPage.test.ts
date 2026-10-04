import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, shallowMount } from '@vue/test-utils'
import { reactive, nextTick } from 'vue'
import Page from '@/views/rule/automations/index.vue'
import * as api from '@/api/automation-management'
import { fetchProjects } from '@/api/project'
const mocks = vi.hoisted(() => ({ user: {} as any }))
vi.mock('@/store/modules/user', () => ({ useUserStore: () => mocks.user }))
vi.mock('@/api/automation-management', () => ({
  ruleCatalog: vi.fn(),
  listAutomations: vi.fn(),
  getAutomation: vi.fn(),
  automationHistory: vi.fn(),
  saveAutomation: vi.fn()
}))
vi.mock('@/api/project', () => ({ fetchProjects: vi.fn() }))
vi.mock('@/api/device', () => ({ fetchSearchDevices: vi.fn() }))
vi.mock('element-plus', () => ({ ElMessageBox: { confirm: vi.fn() } }))
function deferred() {
  let resolve!: (value: any) => void
  const promise = new Promise<any>((r) => {
    resolve = r
  })
  return { promise, resolve }
}
beforeEach(() => {
  vi.resetAllMocks()
  mocks.user = reactive({
    isLogin: true,
    info: { currentProjectId: 'a', userId: 'u', buttons: ['rule:manage'] }
  })
  vi.mocked(api.ruleCatalog).mockResolvedValue({ actions: [] })
  vi.mocked(fetchProjects).mockResolvedValue([
    { id: 'a', timezone: 'Asia/Shanghai' },
    { id: 'b', timezone: 'UTC' }
  ])
})
describe('自动化页面身份与配置清理边界', () => {
  it('切换项目后旧请求迟到不得覆盖新列表', async () => {
    const old = deferred()
    vi.mocked(api.listAutomations).mockImplementation((id) =>
      id === 'a' ? old.promise : Promise.resolve({ items: [{ id: 'new', name: '新项目' }] })
    )
    const page = shallowMount(Page, {
      global: {
        stubs: Object.fromEntries(
          [
            'ElCard',
            'ElAlert',
            'ElInput',
            'ElSelect',
            'ElOption',
            'ElButton',
            'ElTable',
            'ElTableColumn',
            'ElDialog',
            'ElForm',
            'ElFormItem'
          ].map((name) => [name, true])
        )
      }
    })
    mocks.user.info.currentProjectId = 'b'
    await nextTick()
    await flushPromises()
    old.resolve({ items: [{ id: 'secret', name: '旧项目私有规则' }] })
    await flushPromises()
    expect((page.vm as any).$.setupState.items).toEqual([{ id: 'new', name: '新项目' }])
    page.unmount()
  })
  it.each(['logout', 'demotion'])('%s清空数据且不重新请求', async (change) => {
    const pending = deferred()
    vi.mocked(api.listAutomations).mockReturnValue(pending.promise)
    const page = shallowMount(Page, {
      global: {
        stubs: Object.fromEntries(
          [
            'ElCard',
            'ElAlert',
            'ElInput',
            'ElSelect',
            'ElOption',
            'ElButton',
            'ElTable',
            'ElTableColumn',
            'ElDialog',
            'ElForm',
            'ElFormItem'
          ].map((name) => [name, true])
        )
      }
    })
    if (change === 'logout') mocks.user.isLogin = false
    else mocks.user.info.buttons = ['rule:read']
    await nextTick()
    pending.resolve({ items: [{ id: 'secret' }] })
    await flushPromises()
    expect((page.vm as any).$.setupState.items).toEqual([])
    expect(api.listAutomations).toHaveBeenCalledTimes(1)
    page.unmount()
  })
})

it('管理接口403清除已加载配置且迟到响应不能恢复', async () => {
  vi.mocked(api.listAutomations).mockResolvedValue({ items: [] })
  const page = shallowMount(Page, {
    global: {
      stubs: Object.fromEntries(
        [
          'ElCard',
          'ElAlert',
          'ElInput',
          'ElSelect',
          'ElOption',
          'ElButton',
          'ElTable',
          'ElTableColumn',
          'ElDialog',
          'ElForm',
          'ElFormItem',
          'RouterLink'
        ].map((name) => [name, true])
      )
    }
  })
  await flushPromises()
  const state = (page.vm as any).$.setupState
  state.actions = [{ nodeType: 'webhook-action', config: { url: 'private-url' } }]
  state.versions = [{ id: 'secret' }]
  state.payload = '{"token":"private-time-input"}'
  state.timezone = 'America/New_York'
  state.runAt = '2099-01-01T00:00:00'
  const { HttpError } = await import('@/utils/http/error')
  vi.mocked(api.getAutomation).mockRejectedValue(new HttpError('权限已撤销', 40050))
  vi.mocked(api.automationHistory).mockResolvedValue({ items: [] })
  await state.open({ id: 'automation' })
  expect(state.actions).toEqual([])
  expect(state.versions).toEqual([])
  expect(state.visible).toBe(false)
  expect(state.payload).toBe('{}')
  expect(state.timezone).toBe('')
  expect(state.runAt).toBe('')
  page.unmount()
})
