import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, shallowMount } from '@vue/test-utils'
import { reactive, nextTick } from 'vue'
import Page from '@/views/rule/scenes/index.vue'
import * as api from '@/api/scene-management'
const mocks = vi.hoisted(() => ({ user: {} as any }))
vi.mock('@/store/modules/user', () => ({ useUserStore: () => mocks.user }))
vi.mock('@/api/scene-management', () => ({
  ruleCatalog: vi.fn(),
  listScenes: vi.fn(),
  executeScene: vi.fn()
}))
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
})
describe('场景页面身份与执行重试边界', () => {
  it('切换项目后旧请求迟到不得覆盖新列表', async () => {
    const old = deferred()
    vi.mocked(api.listScenes).mockImplementation((id) =>
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
    vi.mocked(api.listScenes).mockReturnValue(pending.promise)
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
    expect(api.listScenes).toHaveBeenCalledTimes(1)
    page.unmount()
  })
})

it('未知结果保留相同键和原始输入，再次执行不能重复受理', async () => {
  vi.mocked(api.listScenes).mockResolvedValue({ items: [] })
  vi.mocked(api.executeScene)
    .mockRejectedValueOnce(new Error('连接中断'))
    .mockResolvedValueOnce({ id: 'first', status: 'DISPATCHED' })
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
  state.selected = { id: 'scene', status: 'ACTIVE' }
  state.deviceId = 'device'
  state.inputJson = '{"temperature":42}'
  await state.execute(false)
  const first = vi.mocked(api.executeScene).mock.calls[0]
  expect(first[2]).toBeTruthy()
  state.inputJson = '{"temperature":999}'
  await state.execute(false)
  expect(api.executeScene).toHaveBeenCalledTimes(1)
  await state.execute(true)
  expect(vi.mocked(api.executeScene).mock.calls[1]).toEqual(first)
  expect(state.pending).toBeUndefined()
  expect(state.executionResult).toContain('DISPATCHED')
  page.unmount()
})
