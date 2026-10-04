import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, shallowMount } from '@vue/test-utils'
import { nextTick, reactive } from 'vue'
import Page from '@/views/project/webhooks/index.vue'
import * as api from '@/api/webhook'
const mocks = vi.hoisted(() => ({ user: {} as any }))
vi.mock('@/store/modules/user', () => ({ useUserStore: () => mocks.user }))
vi.mock('@/api/webhook', () =>
  Object.fromEntries(
    [
      'list',
      'save',
      'change',
      'operation',
      'deliveries',
      'detail',
      'events',
      'recover',
      'recoveryOperation'
    ].map((name) => [name, vi.fn()])
  )
)
vi.mock('element-plus', () => ({ ElMessageBox: { confirm: vi.fn().mockResolvedValue('confirm') } }))
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
    'ElCheckboxGroup',
    'ElSelect',
    'ElOption'
  ].map((name) => [name, true])
)
const mount = () => shallowMount(Page, { global: { stubs, directives: { loading: () => {} } } })
beforeEach(() => {
  vi.clearAllMocks()
  sessionStorage.clear()
  mocks.user = reactive({
    isLogin: true,
    info: { currentProjectId: 'p', userId: 'u', buttons: ['integration:manage'] }
  })
  vi.mocked(api.list).mockResolvedValue({ items: [] })
})
describe('Webhook秘密与持久操作恢复', () => {
  it('刷新保留非秘密操作身份，查询完成不重新提交', async () => {
    vi.mocked(api.save).mockRejectedValue(new Error('network'))
    vi.mocked(api.operation).mockResolvedValue({
      operationId: 'op',
      resultId: 'id',
      kind: 'CREATE',
      resultRevision: '1',
      current: null
    })
    let page = mount()
    await flushPromises()
    let state = (page.vm as any).$.setupState
    state.openForm()
    state.name = 'one'
    state.targetUrl = 'https://receiver.example.com'
    await state.save()
    const operation = state.pending
    expect(operation).toMatch(/^[0-9a-f-]{36}$/)
    expect(sessionStorage.getItem('tc-webhook-operation:u:p')).not.toContain('receiver.example')
    await state.save()
    expect(api.save).toHaveBeenCalledTimes(1)
    page.unmount()
    page = mount()
    await flushPromises()
    state = (page.vm as any).$.setupState
    expect(state.pending).toBe(operation)
    await state.recoverOperation()
    expect(api.operation).toHaveBeenCalledWith('p', operation)
    expect(state.pending).toBe('')
    expect(state.recovered).toContain('明细已清理')
    expect(state.secret).toBe('')
    page.unmount()
  })
  it('跨项目迟到秘密被丢弃，原项目操作身份仍可恢复', async () => {
    let resolve!: (value: any) => void
    vi.mocked(api.save).mockReturnValue(new Promise((r) => (resolve = r)))
    const page = mount()
    await flushPromises()
    const state = (page.vm as any).$.setupState
    state.openForm()
    state.name = 'one'
    state.targetUrl = 'https://receiver.example.com'
    const saving = state.save()
    await nextTick()
    mocks.user.info.currentProjectId = 'other'
    await nextTick()
    resolve({ signingSecret: 'private' })
    await saving
    await flushPromises()
    expect(state.secret).toBe('')
    expect(state.pending).toBe('')
    expect(sessionStorage.getItem('tc-webhook-operation:u:p')).toBeTruthy()
    page.unmount()
  })
  it.each(['logout', 'demotion', 'close'])('%s立即清除已展示秘密', async (change) => {
    const page = mount()
    await flushPromises()
    const state = (page.vm as any).$.setupState
    state.secret = 'private'
    state.secretVisible = true
    await nextTick()
    if (change === 'logout') mocks.user.isLogin = false
    else if (change === 'demotion') mocks.user.info.buttons = []
    else state.secretVisible = false
    await nextTick()
    expect(state.secret).toBe('')
    expect(JSON.stringify(Object.entries(sessionStorage))).not.toContain('private')
    page.unmount()
  })
  it('新筛选清除旧分页，恢复查询类别隔离', async () => {
    vi.mocked(api.deliveries).mockResolvedValue({ items: [], nextCursor: 'next' })
    vi.mocked(api.recoveryOperation).mockResolvedValue({
      operationId: 'op',
      deliveryId: 'id',
      resultRound: 2,
      completedAt: '2026-09-22T00:00:00Z',
      replayed: true,
      current: null
    })
    const page = mount()
    await flushPromises()
    const state = (page.vm as any).$.setupState
    await state.loadDeliveries()
    state.deliveryStatus = 'DEAD'
    await state.loadDeliveries()
    expect(api.deliveries).toHaveBeenLastCalledWith('p', 'DEAD', undefined)
    state.recoveryId = '11111111-1111-4111-8111-111111111111'
    state.recoveryKind = 'delivery'
    await state.recoverOperation()
    expect(api.recoveryOperation).toHaveBeenCalled()
    expect(api.operation).not.toHaveBeenCalled()
    page.unmount()
  })
})
