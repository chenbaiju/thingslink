import { defineComponent, h, reactive, ref } from 'vue'
import { mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useDashboardDesigner } from '../src/composables/useDashboardDesigner'
import { emptyDashboard } from '../src/features/dashboard/designer-model'
const api = vi.hoisted(() => ({
  fetchDashboards: vi.fn(),
  fetchDashboardDraft: vi.fn(),
  createDashboard: vi.fn(),
  saveDashboardDraft: vi.fn()
}))
vi.mock('@/api/dashboard', () => api)
vi.mock('@/utils/http/identity-scope', () => ({ currentIdentityEpoch: () => 1 }))
const id = '00000000-0000-0000-0000-000000000001'
function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((yes, no) => {
    resolve = yes
    reject = no
  })
  return { promise, resolve, reject }
}
const wrappers: ReturnType<typeof mount>[] = []
function fixture() {
  const project = ref('project-a')
  const permissions = reactive({ read: true, create: true, update: true })
  let designer!: ReturnType<typeof useDashboardDesigner>
  wrappers.push(
    mount(
      defineComponent({
        setup() {
          designer = useDashboardDesigner(project, () => permissions)
          return () => h('div')
        }
      })
    )
  )
  return { designer, project, permissions }
}
beforeEach(() => {
  vi.resetAllMocks()
  Object.defineProperty(navigator, 'onLine', { configurable: true, value: true })
  api.fetchDashboardDraft.mockResolvedValue({
    dashboardId: id,
    revision: '0',
    content: emptyDashboard()
  })
})
afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
  vi.useRealTimers()
})
describe('设计器会话边界', () => {
  it.each([
    [60059, '套餐上限'],
    [50048, '套餐配置']
  ])('确定额度拒绝%s显示恢复建议且允许用户重新创建', async (code, message) => {
    const { designer } = fixture()
    api.createDashboard.mockRejectedValue({ code, outcomeUnknown: false })
    await designer.create('看板')
    expect(designer.state.error).toContain(message)
    expect(designer.state.schema).toBeNull()
    expect(designer.state.creating).toBe(false)
    expect(api.createDashboard).toHaveBeenCalledTimes(1)
    await designer.create('另一看板')
    expect(api.createDashboard).toHaveBeenCalledTimes(2)
    expect(api.createDashboard.mock.calls[1]![3]).not.toBe(api.createDashboard.mock.calls[0]![3])
  })
  it('旧项目open晚到不恢复Schema，权限丢失立即清除草稿', async () => {
    const { designer, project, permissions } = fixture()
    const pending = deferred<unknown>()
    api.fetchDashboardDraft.mockReturnValueOnce(pending.promise)
    const opening = designer.open(id)
    project.value = 'project-b'
    pending.resolve({ dashboardId: id, revision: '0', content: emptyDashboard() })
    await opening
    expect(designer.state.schema).toBeNull()
    await designer.open(id)
    designer.add('TEXT')
    permissions.update = false
    expect(designer.state.schema).toBeNull()
    await designer.open(id)
    expect(designer.state.readonly).toBe(true)
  })
  it('未知创建固定同键同正文，改名称拒绝且不会自动重发', async () => {
    const { designer } = fixture()
    api.createDashboard.mockRejectedValue({ outcomeUnknown: true })
    await designer.create('看板')
    const first = api.createDashboard.mock.calls[0]!
    await designer.create('另一名称')
    expect(api.createDashboard).toHaveBeenCalledTimes(1)
    await designer.create('看板')
    expect(api.createDashboard.mock.calls[1]).toEqual(first)
  })
  it('旧创建finally不得清除新项目的creating状态', async () => {
    const { designer, project } = fixture()
    const old = deferred<unknown>()
    const current = deferred<unknown>()
    api.createDashboard.mockReturnValueOnce(old.promise).mockReturnValueOnce(current.promise)
    const first = designer.create('A')
    project.value = 'project-b'
    const second = designer.create('B')
    old.resolve({ id })
    await first
    expect(designer.state.creating).toBe(true)
    expect(api.fetchDashboardDraft).not.toHaveBeenCalled()
    current.reject({ outcomeUnknown: true })
    await second
    expect(designer.state.creating).toBe(false)
  })
  it('offline丢弃内存并围栏在途保存；online不能自行恢复或重发', async () => {
    vi.useFakeTimers()
    const { designer } = fixture()
    const pending = deferred<unknown>()
    api.saveDashboardDraft.mockReturnValueOnce(pending.promise)
    await designer.open(id)
    designer.add('TEXT')
    await vi.advanceTimersByTimeAsync(1500)
    expect(api.saveDashboardDraft).toHaveBeenCalledTimes(1)
    Object.defineProperty(navigator, 'onLine', { configurable: true, value: false })
    window.dispatchEvent(new Event('offline'))
    expect(designer.state.schema).toBeNull()
    expect(designer.state.canUndo).toBe(false)
    expect(designer.state.error).toContain('已丢弃')
    pending.resolve({
      dashboardId: id,
      revision: '1',
      content: api.saveDashboardDraft.mock.calls[0]![3]
    })
    await vi.advanceTimersByTimeAsync(0)
    Object.defineProperty(navigator, 'onLine', { configurable: true, value: true })
    window.dispatchEvent(new Event('online'))
    await vi.advanceTimersByTimeAsync(60000)
    expect(designer.state.schema).toBeNull()
    expect(api.saveDashboardDraft).toHaveBeenCalledTimes(1)
    expect(api.fetchDashboardDraft).toHaveBeenCalledTimes(1)
    await designer.open(id)
    expect(designer.state.schema).not.toBeNull()
  })
  it('目录分页替换而不积累，close后仍可重新使用', async () => {
    const { designer } = fixture()
    api.fetchDashboards
      .mockResolvedValueOnce({
        items: [{ id, managementName: '第一页' }],
        hasMore: true,
        nextCursor: 'next'
      })
      .mockResolvedValueOnce({
        items: [{ id: 'second', managementName: '第二页' }],
        hasMore: false
      })
    await designer.list()
    await designer.list('next')
    expect(designer.state.items.map((item) => item.managementName)).toEqual(['第二页'])
    designer.close()
    await designer.open(id)
    expect(designer.state.dashboardId).toBe(id)
  })
})
