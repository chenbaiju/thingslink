import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, shallowMount } from '@vue/test-utils'
import { nextTick, reactive } from 'vue'
import Panel from '@/views/project/settings/AgentModelPanel.vue'
import * as api from '@/api/assistant-model'
import { fetchProjects } from '@/api/project'
const mocks = vi.hoisted(() => ({ user: {} as any, confirm: vi.fn() }))
vi.mock('@/store/modules/user', () => ({ useUserStore: () => mocks.user }))
vi.mock('@/api/assistant-model', () => ({
  readModelConfiguration: vi.fn(),
  replaceModelCredential: vi.fn(),
  setModelEnabled: vi.fn(),
  removeModelCredential: vi.fn()
}))
vi.mock('@/api/project', () => ({ fetchProjects: vi.fn() }))
vi.mock('element-plus', () => ({ ElMessageBox: { confirm: mocks.confirm } }))
const empty = { configured: false, enabled: false, revision: '0', updatedAt: null }
const saved = { configured: true, enabled: false, revision: '1', updatedAt: '2026-10-03T00:00:00Z' }
const mount = () =>
  shallowMount(Panel, {
    global: {
      stubs: Object.fromEntries(
        ['ElCard', 'ElAlert', 'ElInput', 'ElButton', 'ElForm', 'ElFormItem'].map((name) => [
          name,
          true
        ])
      )
    }
  })
const state = (panel: ReturnType<typeof mount>) => (panel.vm as any).$.setupState
function deferred() {
  let resolve!: (value: any) => void
  let reject!: (value: any) => void
  const promise = new Promise<any>((yes, no) => {
    resolve = yes
    reject = no
  })
  return { promise, resolve, reject }
}
beforeEach(() => {
  vi.resetAllMocks()
  mocks.user = reactive({
    isLogin: true,
    info: { currentProjectId: 'p', userId: 'u', tenantId: 't', roles: ['OWNER'] }
  })
  vi.mocked(api.readModelConfiguration).mockResolvedValue(empty)
  vi.mocked(fetchProjects).mockResolvedValue([
    { id: 'p', status: 'ACTIVE' },
    { id: 'other', status: 'ACTIVE' }
  ] as any)
})
describe('项目模型凭据界面', () => {
  it.each(['OWNER', 'ADMIN'])('%s 读取元数据，空配置不能启用', async (role) => {
    mocks.user.info.roles = [role]
    const panel = mount()
    await flushPromises()
    expect(api.readModelConfiguration).toHaveBeenCalledOnce()
    expect(state(panel).configuration).toEqual(empty)
    expect(state(panel).writable).toBe(true)
    panel.unmount()
  })
  it.each(['OPERATOR', 'VIEWER'])('%s 不请求管理元数据', async (role) => {
    mocks.user.info.roles = [role]
    const panel = mount()
    await flushPromises()
    expect(api.readModelConfiguration).not.toHaveBeenCalled()
    expect(panel.find('[data-testid="agent-model-panel"]').exists()).toBe(false)
    panel.unmount()
  })
  it('保存前立即清空秘密，失败不自动重试，刷新只读取状态', async () => {
    const pending = deferred()
    vi.mocked(api.replaceModelCredential).mockReturnValue(pending.promise)
    const panel = mount()
    await flushPromises()
    const vm = state(panel)
    vm.secret = 'synthetic-private-key'
    const saving = vm.save()
    expect(vm.secret).toBe('')
    expect(api.replaceModelCredential).toHaveBeenCalledWith(
      'p',
      { expectedRevision: '0', apiKey: 'synthetic-private-key' },
      expect.any(AbortSignal)
    )
    pending.reject(new Error('network'))
    await saving
    expect(vm.uncertain).toBe(true)
    expect(vm.configuration).toBeUndefined()
    expect(vm.writable).toBe(false)
    vm.secret = 'another'
    await vm.save()
    expect(api.replaceModelCredential).toHaveBeenCalledOnce()
    vi.mocked(api.readModelConfiguration).mockResolvedValue(saved)
    await vm.load()
    expect(api.replaceModelCredential).toHaveBeenCalledOnce()
    expect(vm.configuration).toEqual(saved)
    expect(vm.secret).toBe('')
    panel.unmount()
  })
  it('切项目中止旧请求并丢弃迟到状态', async () => {
    const pending = deferred()
    vi.mocked(api.readModelConfiguration).mockReturnValueOnce(pending.promise)
    const panel = mount()
    const signal = vi.mocked(api.readModelConfiguration).mock.calls[0][1]!
    state(panel).secret = 'old-project-secret'
    mocks.user.info.currentProjectId = 'other'
    await nextTick()
    await flushPromises()
    expect(signal.aborted).toBe(true)
    pending.resolve(saved)
    await flushPromises()
    expect(state(panel).configuration).toEqual(empty)
    expect(state(panel).secret).toBe('')
    panel.unmount()
  })
  it('主密钥配置不可用时明确说明，不回显异常或重发Key', async () => {
    vi.mocked(api.replaceModelCredential).mockRejectedValue({
      code: 50061,
      message: 'synthetic-sensitive-error'
    })
    const panel = mount()
    await flushPromises()
    const vm = state(panel)
    vm.secret = 'synthetic-key'
    await vm.save()
    expect(vm.error).toContain('服务器模型凭据保护不可用')
    expect(vm.error).toContain('不要重复提交 Key')
    expect(vm.error).not.toContain('synthetic-sensitive-error')
    expect(vm.secret).toBe('')
    expect(vm.uncertain).toBe(true)
    await vm.save()
    expect(api.replaceModelCredential).toHaveBeenCalledOnce()
    panel.unmount()
  })
  it.each(['demote', 'logout', 'account'])('%s 清除秘密和元数据', async (change) => {
    const panel = mount()
    await flushPromises()
    state(panel).secret = 'private-key'
    if (change === 'demote') mocks.user.info.roles = ['VIEWER']
    else if (change === 'logout') mocks.user.isLogin = false
    else mocks.user.info.userId = 'new-user'
    await nextTick()
    expect(state(panel).secret).toBe('')
    if (change !== 'account') expect(state(panel).configuration).toBeUndefined()
    panel.unmount()
  })
  it('切项目后旧移除确认不能作用于新项目', async () => {
    vi.mocked(api.readModelConfiguration).mockResolvedValue(saved)
    const confirm = deferred()
    mocks.confirm.mockReturnValue(confirm.promise)
    const panel = mount()
    await flushPromises()
    const removing = state(panel).remove()
    mocks.user.info.currentProjectId = 'other'
    await nextTick()
    confirm.resolve('confirm')
    await removing
    expect(api.removeModelCredential).not.toHaveBeenCalled()
    panel.unmount()
  })
  it('归档项目可读状态但禁用写操作', async () => {
    vi.mocked(fetchProjects).mockResolvedValue([{ id: 'p', status: 'ARCHIVED' }] as any)
    const panel = mount()
    await flushPromises()
    expect(state(panel).configuration).toEqual(empty)
    expect(state(panel).writable).toBe(false)
    panel.unmount()
  })
  it('单次保存即启用，停用和移除只发送版本', async () => {
    vi.mocked(api.replaceModelCredential).mockResolvedValue({
      ...saved,
      enabled: true,
      revision: '2'
    })
    vi.mocked(api.setModelEnabled).mockResolvedValue({ ...saved, revision: '3' })
    vi.mocked(api.removeModelCredential).mockResolvedValue({ ...empty, revision: '4' })
    mocks.confirm.mockResolvedValue('confirm')
    const panel = mount()
    await flushPromises()
    const vm = state(panel)
    vm.secret = 'synthetic-key'
    await vm.save()
    expect(vm.configuration.enabled).toBe(true)
    expect(api.replaceModelCredential).toHaveBeenCalledOnce()
    expect(api.setModelEnabled).not.toHaveBeenCalled()
    await vm.toggle()
    expect(api.setModelEnabled).toHaveBeenCalledWith(
      'p',
      { expectedRevision: '2', enabled: false },
      expect.any(AbortSignal)
    )
    await vm.remove()
    expect(api.removeModelCredential).toHaveBeenCalledWith('p', '3', expect.any(AbortSignal))
    expect(vm.configuration.configured).toBe(false)
    panel.unmount()
  })
})
