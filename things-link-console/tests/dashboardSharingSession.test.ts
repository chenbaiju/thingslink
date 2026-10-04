import { mount, flushPromises } from '@vue/test-utils'
import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest'
const mocks = vi.hoisted(() => ({
  configuration: vi.fn(),
  list: vi.fn(),
  create: vi.fn(),
  revoke: vi.fn(),
  catalog: vi.fn(),
  history: vi.fn(),
  version: vi.fn(),
  confirm: vi.fn(),
  copy: vi.fn(),
  metadata: vi.fn(),
  scope: vi.fn(),
  search: vi.fn()
}))
vi.mock('element-plus', () => ({ ElMessageBox: { confirm: mocks.confirm } }))
vi.mock('../src/store/modules/user', () => ({ useUserStore: () => ({ info: { userId: 'user' } }) }))
vi.mock('../src/utils/http/identity-scope', () => ({ currentIdentityEpoch: () => 1 }))
vi.mock('../src/api/dashboard-sharing', () => ({
  fetchShareConfiguration: mocks.configuration,
  fetchDashboardShares: mocks.list,
  createDashboardShareIntent: mocks.create,
  revokeDashboardShare: mocks.revoke
}))
vi.mock('../src/api/dashboard-publication', () => ({
  fetchDashboardPublicationCatalog: mocks.catalog,
  fetchDashboardPublicationHistory: mocks.history,
  fetchDashboardPublicationVersion: mocks.version,
  writeDashboardPublicationIntent: vi.fn()
}))
vi.mock('../src/api/device', () => ({ fetchSearchDevices: mocks.search }))
vi.mock('../src/api/dashboard-binding', () => ({
  fetchBindingMetadata: mocks.metadata,
  createDesignerReadScope: mocks.scope
}))
import DesignerSharing from '../src/views/dashboard/designer/components/DesignerSharing.vue'
import { emptyDashboard } from '../src/features/dashboard/designer-model'
const projectId = '11111111-1111-4111-8111-111111111111',
  dashboardId = '22222222-2222-4222-8222-222222222222',
  versionId = '33333333-3333-4333-8333-333333333333',
  shareId = '44444444-4444-4444-8444-444444444444',
  deviceId = '55555555-5555-4555-8555-555555555555',
  modelId = '66666666-6666-4666-8666-666666666666'
const token = 'sh_' + 'A'.repeat(43),
  time = '2026-09-08T00:00:00Z'
const version = {
  id: versionId,
  versionNumber: '1',
  sourceDraftRevision: '0',
  schemaVersion: 'tc.dashboard/v1',
  schemaDigestAlgorithm: 'PG_JSONB_TEXT_V1_SHA256',
  schemaDigest: 'a'.repeat(64),
  publishedAt: time
}
let hidden = false
beforeEach(() => {
  vi.clearAllMocks()
  hidden = false
  vi.spyOn(document, 'hidden', 'get').mockImplementation(() => hidden)
  Object.defineProperty(navigator, 'clipboard', {
    configurable: true,
    value: { writeText: mocks.copy }
  })
  mocks.copy.mockResolvedValue(undefined)
  mocks.confirm.mockResolvedValue(undefined)
  mocks.configuration.mockResolvedValue({
    available: true,
    hostOrigin: 'https://app.test',
    hostVersion: '1.0.0',
    hostCompatibility: { minInclusive: '1.0.0', maxExclusive: '1.0.1' }
  })
  mocks.list.mockResolvedValue({ items: [], hasMore: false, nextCursor: null })
  mocks.create.mockResolvedValue({ shareId, secret: token, expiresAt: time })
  mocks.revoke.mockResolvedValue(undefined)
  mocks.catalog.mockResolvedValue({
    id: dashboardId,
    managementName: '看板',
    publicationRevision: '1',
    currentVersionId: versionId,
    createdAt: time,
    updatedAt: time
  })
  mocks.history.mockResolvedValue({ items: [version], hasMore: false, nextCursor: null })
  mocks.version.mockResolvedValue({
    ...version,
    schema: emptyDashboard(),
    requiredComponents: [],
    requiredResources: []
  })
  mocks.scope.mockImplementation(() => ({ close: vi.fn() }))
})
afterEach(() => {
  vi.restoreAllMocks()
  vi.useRealTimers()
})
async function fixture(canManage = true) {
  const wrapper = mount(DesignerSharing, {
    props: { projectId, dashboardId, available: true, canManage },
    global: {
      stubs: {
        ElButton: {
          props: ['disabled'],
          template: '<button :disabled="disabled"><slot /></button>'
        },
        ElAlert: { props: ['title'], template: '<p>{{title}}</p>' },
        ElInputNumber: { props: ['modelValue'], template: '<input :value="modelValue" />' },
        ElFormItem: { template: '<div><slot /></div>' },
        ElSelect: {
          props: ['modelValue'],
          emits: ['update:modelValue'],
          template:
            '<select :value="modelValue" @change="$emit(\'update:modelValue\',$event.target.value)"><slot /></select>'
        },
        ElOption: {
          props: ['value', 'label'],
          template: '<option :value="value">{{label}}</option>'
        }
      }
    }
  })
  await flushPromises()
  return wrapper
}
async function choose(wrapper: Awaited<ReturnType<typeof fixture>>) {
  await wrapper
    .findAll('button')
    .find((button) => button.text() === '选择分享版本 1')!
    .trigger('click')
  await flushPromises()
}
describe('匿名分享管理会话', () => {
  it('用户确认后创建，页面与DOM无secret，仅显式copy送剪贴板；隐藏立即清除', async () => {
    const wrapper = await fixture()
    await choose(wrapper)
    expect(wrapper.text()).toContain('所有静态页面内容')
    await wrapper.get('[data-testid="sharing-create"]').trigger('click')
    await flushPromises()
    expect(mocks.confirm).toHaveBeenCalledWith(
      expect.any(String),
      '创建只读分享',
      expect.objectContaining({ confirmButtonText: '创建分享' })
    )
    expect(wrapper.get('[data-testid="sharing-created"]').attributes('data-share-id')).toBe(shareId)
    expect(wrapper.html()).not.toContain(token)
    expect(mocks.copy).not.toHaveBeenCalled()
    await wrapper.get('[data-testid="sharing-copy"]').trigger('click')
    await flushPromises()
    expect(mocks.copy).toHaveBeenCalledWith(`https://app.test/app/share/${shareId}#token=${token}`)
    hidden = true
    document.dispatchEvent(new Event('visibilitychange'))
    await flushPromises()
    expect(wrapper.find('[data-testid="sharing-copy"]').exists()).toBe(false)
    expect(wrapper.html()).not.toContain(token)
    wrapper.unmount()
  })
  it('未知创建保持原意图，60052只显示ID和撤销入口，不出现复制按钮', async () => {
    mocks.create
      .mockRejectedValueOnce({ outcomeUnknown: true })
      .mockRejectedValueOnce({ code: 60052, status: 409, details: [shareId] })
    const wrapper = await fixture()
    await choose(wrapper)
    await wrapper.get('[data-testid="sharing-create"]').trigger('click')
    await flushPromises()
    await wrapper.get('[data-testid="sharing-retry"]').trigger('click')
    await flushPromises()
    expect(mocks.create.mock.calls[1]).toEqual(mocks.create.mock.calls[0])
    expect(wrapper.get('[data-testid="sharing-unrecoverable"]').attributes('data-share-id')).toBe(
      shareId
    )
    expect(wrapper.find('[data-testid="sharing-copy"]').exists()).toBe(false)
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '撤销无法恢复的分享')!
      .trigger('click')
    await flushPromises()
    expect(mocks.revoke).toHaveBeenCalledWith(projectId, dashboardId, shareId)
    wrapper.unmount()
  })
  it('确认期间切换看板不能签发新上下文，取消确认不创建', async () => {
    const wrapper = await fixture()
    await choose(wrapper)
    mocks.confirm.mockRejectedValueOnce('cancel')
    await wrapper.get('[data-testid="sharing-create"]').trigger('click')
    await flushPromises()
    expect(mocks.create).not.toHaveBeenCalled()
    let resolve!: () => void
    mocks.confirm.mockImplementationOnce(
      () =>
        new Promise<void>((done) => {
          resolve = done
        })
    )
    await wrapper.get('[data-testid="sharing-create"]').trigger('click')
    await wrapper.setProps({ dashboardId: shareId })
    resolve()
    await flushPromises()
    expect(mocks.create).not.toHaveBeenCalled()
    wrapper.unmount()
  })
  it('非管理角色没有面板或请求；读取失败不能显示成空列表', async () => {
    const readonly = await fixture(false)
    expect(readonly.text()).not.toContain('匿名只读分享')
    expect(mocks.list).not.toHaveBeenCalled()
    readonly.unmount()
    mocks.list.mockRejectedValueOnce({ status: 403, code: 60035 })
    const wrapper = await fixture()
    expect(wrapper.text()).toContain('分享状态读取失败')
    expect(wrapper.text()).not.toContain('当前页没有分享记录')
    expect(wrapper.text()).not.toContain('暂无可选择')
    expect(mocks.catalog).not.toHaveBeenCalled()
    wrapper.unmount()
  })
  it('候选校验总deadline物理关闭活动scope，不发创建', async () => {
    vi.useFakeTimers()
    const close = vi.fn()
    let reject!: (error: Error) => void
    close.mockImplementation(() => reject?.(new Error('cancelled')))
    mocks.scope.mockReturnValue({ close })
    mocks.metadata.mockImplementation(
      () =>
        new Promise((_resolve, no) => {
          reject = no
        })
    )
    mocks.version.mockResolvedValue({
      ...version,
      requiredComponents: [],
      requiredResources: [],
      schema: {
        ...emptyDashboard(),
        models: [
          {
            key: 'model',
            versionId: modelId,
            digestAlgorithm: 'PG_JSONB_TEXT_V1_SHA256',
            digest: 'a'.repeat(64),
            profile: 'TC_PROPERTY_COMPOSITE_V1'
          }
        ],
        variables: [
          {
            key: 'device',
            type: 'DEVICE_SINGLE',
            modelKey: 'model',
            title: '设备',
            defaultDeviceId: deviceId
          }
        ]
      }
    })
    const wrapper = await fixture()
    await choose(wrapper)
    await wrapper.get('[data-testid="sharing-create"]').trigger('click')
    await flushPromises()
    await vi.advanceTimersByTimeAsync(30_000)
    await flushPromises()
    expect(close).toHaveBeenCalled()
    expect(mocks.create).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('未发出创建请求')
    wrapper.unmount()
  })
})
