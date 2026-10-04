import { mount, flushPromises } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
const mocks = vi.hoisted(() => ({ confirm: vi.fn(), create: vi.fn(), identity: 1 }))
vi.mock('element-plus', () => ({ ElMessageBox: { confirm: mocks.confirm } }))
vi.mock('../src/store/modules/user', () => ({ useUserStore: () => ({ info: { userId: 'user' } }) }))
vi.mock('../src/utils/http/identity-scope', () => ({ currentIdentityEpoch: () => mocks.identity }))
vi.mock('../src/features/dashboard/publication-model', () => ({
  createDashboardPublication: mocks.create
}))
vi.mock('../src/api/dashboard-publication', () => ({
  fetchDashboardPublicationCatalog: vi.fn(),
  fetchDashboardPublicationHistory: vi.fn(),
  fetchDashboardPublicationVersion: vi.fn(),
  writeDashboardPublicationIntent: vi.fn()
}))
import DesignerPublication from '../src/views/dashboard/designer/components/DesignerPublication.vue'
function snapshot() {
  return {
    catalog: { id: 'dashboard', publicationRevision: '2', currentVersionId: 'version-2' },
    history: [
      { id: 'version-2', versionNumber: '2', publishedAt: '2026-09-08T00:00:00Z' },
      { id: 'version-1', versionNumber: '1', publishedAt: '2026-09-07T00:00:00Z' }
    ],
    nextCursor: 'next',
    loading: false,
    writing: false,
    detailLoading: false,
    pending: null as null | { status: string },
    error: '',
    notice: '',
    selectedVersion: null as null | Record<string, unknown>
  }
}
function fixture(patch: Record<string, unknown> = {}) {
  const initial = snapshot()
  let changed!: (state: ReturnType<typeof snapshot>) => void
  const model = {
    getSnapshot: () => initial,
    open: vi.fn(),
    loadMore: vi.fn(),
    selectVersion: vi.fn(),
    publish: vi.fn(),
    rollback: vi.fn(),
    withdraw: vi.fn(),
    retry: vi.fn(),
    recover: vi.fn(),
    reset: vi.fn()
  }
  mocks.create.mockImplementation((ports) => {
    changed = ports.changed
    return model
  })
  const wrapper = mount(DesignerPublication, {
    props: {
      projectId: 'project',
      dashboardId: 'dashboard',
      draftRevision: '3',
      dirty: false,
      saving: false,
      conflict: false,
      available: true,
      canRead: true,
      canManage: true,
      ...patch
    },
    global: {
      stubs: {
        ElButton: {
          props: ['disabled'],
          template: '<button :disabled="disabled"><slot /></button>'
        },
        ElAlert: { props: ['title'], template: '<p>{{ title }}</p>' }
      }
    }
  })
  return {
    wrapper,
    model,
    initial,
    update: (patch: Partial<ReturnType<typeof snapshot>>) => changed({ ...initial, ...patch })
  }
}
beforeEach(() => {
  vi.clearAllMocks()
  mocks.identity = 1
  mocks.confirm.mockResolvedValue(undefined)
})
describe('看板发布产品会话', () => {
  it('读取当前版本、分页和详情不会改写草稿，管理按钮统一确认', async () => {
    const { wrapper, model, update } = fixture()
    expect(model.open).toHaveBeenCalledTimes(1)
    expect(wrapper.get('[data-testid="publication-status"]').attributes('data-version-id')).toBe(
      'version-2'
    )
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '下一页历史版本')!
      .trigger('click')
    expect(model.loadMore).toHaveBeenCalledTimes(1)
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '查看版本 1')!
      .trigger('click')
    expect(model.selectVersion).toHaveBeenCalledWith('version-1')
    update({
      selectedVersion: {
        id: 'version-1',
        versionNumber: '1',
        sourceDraftRevision: '1',
        publishedAt: '2026-09-07T00:00:00Z',
        schema: { title: '历史内容' }
      }
    })
    await flushPromises()
    expect(wrapper.text()).toContain('查看不会覆盖当前草稿')
    await wrapper.get('[data-testid="publication-publish"]').trigger('click')
    await flushPromises()
    expect(mocks.confirm).toHaveBeenCalledWith(
      expect.any(String),
      '确认发布',
      expect.objectContaining({ confirmButtonText: '发布' })
    )
    expect(model.publish).toHaveBeenCalledTimes(1)
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '回滚到版本 1')!
      .trigger('click')
    await flushPromises()
    expect(model.rollback).toHaveBeenCalledWith('version-1')
    await wrapper.get('[data-testid="publication-withdraw"]').trigger('click')
    await flushPromises()
    expect(model.withdraw).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })
  it.each([{ dirty: true }, { saving: true }, { conflict: true }])(
    '草稿未稳定%s禁止发布',
    async (patch) => {
      const { wrapper, model } = fixture(patch)
      expect(
        wrapper.get('[data-testid="publication-publish"]').attributes('disabled')
      ).toBeDefined()
      await wrapper.get('[data-testid="publication-publish"]').trigger('click')
      expect(model.publish).not.toHaveBeenCalled()
      expect(mocks.confirm).not.toHaveBeenCalled()
      wrapper.unmount()
    }
  )
  it('只读用户可看历史但不显示写操作', () => {
    const { wrapper } = fixture({ canManage: false })
    expect(wrapper.find('[data-testid="publication-publish"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="publication-withdraw"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('回滚到版本')
    expect(wrapper.text()).toContain('查看版本 1')
    wrapper.unmount()
  })
  it('取消产品确认不写，确认期间换看板或身份也不得误发新上下文', async () => {
    const { wrapper, model } = fixture()
    mocks.confirm.mockRejectedValueOnce('cancel')
    await wrapper.get('[data-testid="publication-publish"]').trigger('click')
    await flushPromises()
    expect(model.publish).not.toHaveBeenCalled()
    let resolve!: () => void
    mocks.confirm.mockImplementationOnce(
      () =>
        new Promise<void>((done) => {
          resolve = done
        })
    )
    await wrapper.get('[data-testid="publication-publish"]').trigger('click')
    await wrapper.setProps({ dashboardId: 'another' })
    mocks.identity++
    resolve()
    await flushPromises()
    expect(model.publish).not.toHaveBeenCalled()
    expect(model.reset).toHaveBeenCalledTimes(2)
    wrapper.unmount()
  })
  it('未知结果仅允许原意图重试和读取事实，完成墓碑不当作首次成功', async () => {
    const { wrapper, model, update } = fixture()
    update({ pending: { status: 'UNKNOWN' } })
    await flushPromises()
    expect(wrapper.get('[data-testid="publication-publish"]').attributes('disabled')).toBeDefined()
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '重试原操作')!
      .trigger('click')
    expect(model.retry).toHaveBeenCalledTimes(1)
    await wrapper.get('[data-testid="publication-refresh"]').trigger('click')
    expect(model.recover).toHaveBeenCalledTimes(1)
    update({ pending: { status: 'COMPLETED' }, notice: '已收到完成标记，请读取当前事实。' })
    await flushPromises()
    expect(wrapper.text()).not.toContain('重试原操作')
    expect(wrapper.text()).toContain('当前状态可能包含后续变更')
    expect(wrapper.text()).not.toContain('发布成功')
    wrapper.unmount()
  })
  it('失去读取资格立即重置并不重新加载，离线禁止读取和重试', async () => {
    const { wrapper, model } = fixture()
    await wrapper.setProps({ available: false })
    expect(model.reset).toHaveBeenCalledTimes(2)
    expect(model.open).toHaveBeenCalledTimes(1)
    expect(wrapper.get('[data-testid="publication-refresh"]').attributes('disabled')).toBeDefined()
    wrapper.unmount()
  })
})
