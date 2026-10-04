import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, shallowMount } from '@vue/test-utils'
import { reactive, nextTick } from 'vue'
import Page from '@/views/rule/messages/index.vue'
import * as api from '@/api/rule-management'
const mocks = vi.hoisted(() => ({ user: {} as any }))
vi.mock('@/store/modules/user', () => ({ useUserStore: () => mocks.user }))
vi.mock('@/api/rule-management', () => ({ ruleCatalog: vi.fn(), listRules: vi.fn() }))
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
})
describe('消息规则页面身份变更边界', () => {
  it('切换项目后旧请求迟到不得覆盖新列表', async () => {
    const old = deferred()
    vi.mocked(api.listRules).mockImplementation((id) =>
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
    vi.mocked(api.listRules).mockReturnValue(pending.promise)
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
    expect(api.listRules).toHaveBeenCalledTimes(1)
    page.unmount()
  })
})
