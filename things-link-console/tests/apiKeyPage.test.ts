import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, shallowMount } from '@vue/test-utils'
import { nextTick, reactive } from 'vue'
import Page from '@/views/project/api-keys/index.vue'
import * as api from '@/api/api-key'
const mocks = vi.hoisted(() => ({ user: {} as any }))
vi.mock('@/store/modules/user', () => ({ useUserStore: () => mocks.user }))
vi.mock('@/api/api-key', () => ({
  listKeys: vi.fn(),
  createKey: vi.fn(),
  recoverKey: vi.fn(),
  revokeKey: vi.fn()
}))
vi.mock('element-plus', () => ({ ElMessageBox: { confirm: vi.fn() } }))
const stubs = Object.fromEntries(
  [
    'ElCard',
    'ElAlert',
    'ElInput',
    'ElButton',
    'ElTable',
    'ElTableColumn',
    'ElDialog',
    'ElForm',
    'ElFormItem',
    'ElCheckbox',
    'ElCheckboxGroup'
  ].map((name) => [name, true])
)
const mount = () => shallowMount(Page, { global: { stubs, directives: { loading: () => {} } } })
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
    info: { currentProjectId: 'p', userId: 'u', buttons: ['integration:manage'] }
  })
  vi.mocked(api.listKeys).mockResolvedValue({ items: [] })
})
describe('Key页面秘密和未知操作边界', () => {
  it('切换项目后迟到签发秘密不能进入新身份', async () => {
    const response = deferred()
    vi.mocked(api.createKey).mockReturnValue(response.promise)
    const page = mount()
    await flushPromises()
    const state = (page.vm as any).$.setupState
    state.openForm()
    state.name = 'key'
    state.cidrs = '127.0.0.1/32'
    const saving = state.save()
    await nextTick()
    mocks.user.info.currentProjectId = 'other'
    await nextTick()
    response.resolve({ secret: 'tcak1.late-secret', key: { id: 'old' } })
    await saving
    await flushPromises()
    expect(state.secret).toBe('')
    expect(state.secretVisible).toBe(false)
    expect(state.pending).toBe('')
    page.unmount()
  })
  it.each(['logout', 'demotion'])('%s清除已展示秘密与元数据', async (change) => {
    const page = mount()
    await flushPromises()
    const state = (page.vm as any).$.setupState
    state.secret = 'tcak1.private'
    state.secretVisible = true
    state.items = [{ id: 'old' }]
    if (change === 'logout') mocks.user.isLogin = false
    else mocks.user.info.buttons = []
    await nextTick()
    expect(state.secret).toBe('')
    expect(state.items).toEqual([])
    expect(state.secretVisible).toBe(false)
    page.unmount()
  })
  it('未知结果不自动发钥且恢复不产生秘密', async () => {
    vi.mocked(api.createKey).mockRejectedValue(new Error('network'))
    vi.mocked(api.recoverKey).mockResolvedValue({ id: 'created', name: 'key', status: 'ACTIVE' })
    const page = mount()
    await flushPromises()
    const state = (page.vm as any).$.setupState
    state.openForm()
    state.name = 'key'
    state.cidrs = '127.0.0.1/32'
    await state.save()
    const operation = state.pending
    expect(operation).toMatch(/^[0-9a-f-]{36}$/)
    await state.save()
    expect(api.createKey).toHaveBeenCalledTimes(1)
    await state.recover()
    expect(api.recoverKey).toHaveBeenCalledWith('p', operation)
    expect(state.secret).toBe('')
    expect(state.pending).toBe('')
    expect(state.recovered.id).toBe('created')
    page.unmount()
  })
  it('关闭窗口立即清空秘密且不写入浏览器存储', async () => {
    const local = vi.spyOn(Storage.prototype, 'setItem')
    const page = mount()
    await flushPromises()
    const state = (page.vm as any).$.setupState
    state.secret = 'tcak1.private'
    state.secretVisible = true
    state.clearSecret()
    expect(state.secret).toBe('')
    expect(state.secretVisible).toBe(false)
    expect(local).not.toHaveBeenCalled()
    page.unmount()
    local.mockRestore()
  })
})
