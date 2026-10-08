import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { reactive } from 'vue'
import Page from '@/views/device/messages/index.vue'

const mocks = vi.hoisted(() => ({
  user: {} as any,
  route: {} as any,
  list: vi.fn(),
  detail: vi.fn(),
  diagnostics: vi.fn(),
  device: vi.fn()
}))
vi.mock('@/utils/http/error', () => ({ HttpError: class extends Error {} }))
vi.mock('@/store/modules/user', () => ({ useUserStore: () => mocks.user }))
vi.mock('vue-router', () => ({ useRoute: () => mocks.route, useRouter: () => ({ push: vi.fn() }) }))
vi.mock('@/api/device', () => ({
  fetchProjectMessages: mocks.list,
  fetchMessageLogDetail: mocks.detail,
  fetchDeviceAccessDiagnostics: mocks.diagnostics,
  fetchDeviceDetail: mocks.device
}))
vi.mock('@/views/device/messages/MessageLogFilterForm.vue', () => ({
  default: { render: () => null }
}))
vi.mock('@/composables/usePagedDeviceCatalog', async () => {
  const { ref } = await import('vue')
  return {
    usePagedDeviceCatalog: () => ({
      devices: ref([]),
      devicesLoading: ref(false),
      loadDevices: vi.fn(),
      searchDevices: vi.fn(),
      ensureDevices: vi.fn(),
      onDevicePopupScroll: vi.fn()
    })
  }
})
const wrappers: ReturnType<typeof mount>[] = []
function setup() {
  const wrapper = mount({ ...Page, render: () => null })
  wrappers.push(wrapper)
  return (wrapper.vm as any).$.setupState
}
function deferred() {
  let resolve!: (value: any) => void
  return {
    promise: new Promise<any>((done) => {
      resolve = done
    }),
    resolve: (value: any) => resolve(value)
  }
}
const deviceId = '11111111-1111-4111-8111-111111111111'
beforeEach(() => {
  vi.resetAllMocks()
  mocks.user = reactive({
    isLogin: true,
    info: {
      userId: 'user',
      tenantId: 'tenant',
      currentProjectId: 'project',
      buttons: ['device:read']
    }
  })
  mocks.route = reactive({ query: {} })
  mocks.list.mockResolvedValue({ items: [], hasMore: false })
  mocks.device.mockResolvedValue({ id: deviceId, name: '来源设备' })
})
afterEach(() => wrappers.splice(0).forEach((wrapper) => wrapper.unmount()))
describe('消息调试工作区', () => {
  it('来源设备经当前项目单读后才查询该设备消息', async () => {
    mocks.route.query = { deviceId, contextProjectId: 'project' }
    const page = setup()
    await flushPromises()
    expect(mocks.device).toHaveBeenCalledExactlyOnceWith('project', deviceId)
    expect(mocks.list).toHaveBeenCalledOnce()
    expect(mocks.list.mock.calls[0][1].deviceId).toBe(deviceId)
    expect(page.filter.deviceId).toBe(deviceId)
  })
  it('跨项目来源不查询设备或消息', async () => {
    mocks.route.query = { deviceId, contextProjectId: 'other' }
    const page = setup()
    await flushPromises()
    expect(mocks.device).not.toHaveBeenCalled()
    expect(mocks.list).not.toHaveBeenCalled()
    expect(page.sourceError).toContain('其他项目')
  })
  it('移除无效来源参数后恢复当前项目消息查询', async () => {
    mocks.route.query = { deviceId, contextProjectId: 'other' }
    const page = setup()
    await flushPromises()
    expect(mocks.list).not.toHaveBeenCalled()
    mocks.route.query = {}
    await flushPromises()
    expect(mocks.list).toHaveBeenCalledOnce()
    expect(page.sourceError).toBe('')
    expect(page.filter.deviceId).toBe('')
  })
  it('切换项目后旧消息列表迟响应不能覆盖新项目', async () => {
    const old = deferred()
    mocks.list.mockImplementation((project: string) =>
      project === 'project' ? old.promise : Promise.resolve({ items: [{ id: 'new' }] })
    )
    const page = setup()
    mocks.user.info.currentProjectId = 'new-project'
    await flushPromises()
    old.resolve({ items: [{ id: 'old-secret' }], nextCursor: 'old-cursor', hasMore: true })
    await flushPromises()
    expect(page.items).toEqual([{ id: 'new' }])
    expect(page.nextCursor).toBeUndefined()
    expect(page.hasMore).toBe(false)
  })
  it('换身份立即清空详情与诊断，迟响应不得重新出现', async () => {
    const detail = deferred(),
      diagnostics = deferred()
    mocks.detail.mockReturnValue(detail.promise)
    mocks.diagnostics.mockReturnValue(diagnostics.promise)
    const page = setup()
    page.openDetail({ id: 'log', deviceId })
    void page.openDiagnostics(deviceId)
    mocks.user.info.userId = 'other-user'
    detail.resolve({ payloadSummary: '旧消息' })
    diagnostics.resolve({ state: 'CONNECTED' })
    await flushPromises()
    expect(page.detailVisible).toBe(false)
    expect(page.diagnosticsVisible).toBe(false)
    expect(page.detail).toBeUndefined()
    expect(page.diagnostics).toBeUndefined()
  })
  it('关闭后重新打开详情，只接受新的详情响应', async () => {
    const old = deferred()
    mocks.detail
      .mockReturnValueOnce(old.promise)
      .mockResolvedValueOnce({ payloadSummary: '新消息' })
    const page = setup()
    page.openDetail({ id: 'old', deviceId })
    page.detailVisible = false
    await flushPromises()
    page.openDetail({ id: 'new', deviceId })
    await flushPromises()
    old.resolve({ payloadSummary: '旧消息' })
    await flushPromises()
    expect(page.detail.payloadSummary).toBe('新消息')
  })
  it('撤销读取权限丢弃在途请求且不再次查询', async () => {
    const old = deferred()
    mocks.list.mockReturnValue(old.promise)
    const page = setup()
    mocks.user.info.buttons = []
    old.resolve({ items: [{ id: 'old-secret' }] })
    await flushPromises()
    expect(page.items).toEqual([])
    expect(mocks.list).toHaveBeenCalledOnce()
  })
})
