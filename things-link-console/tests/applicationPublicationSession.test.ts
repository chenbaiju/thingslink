import { mount, flushPromises } from '@vue/test-utils'
import { reactive } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
const mocks = vi.hoisted(() => ({
  confirm: vi.fn(),
  create: vi.fn(),
  identity: 1,
  user: null as unknown as { info: { userId: string; tenantId: string; roles?: string[] } }
}))
vi.mock('element-plus', () => ({ ElMessageBox: { confirm: mocks.confirm } }))
vi.mock('../src/store/modules/user', () => ({ useUserStore: () => mocks.user }))
vi.mock('../src/utils/http/identity-scope', () => ({ currentIdentityEpoch: () => mocks.identity }))
vi.mock('../src/features/application/publication-model', () => ({
  createApplicationPublication: mocks.create
}))
vi.mock('../src/api/application-publication', () => ({
  fetchApplicationPublicationCatalog: vi.fn(),
  fetchApplicationPublicationHistory: vi.fn(),
  fetchApplicationPublicationVersion: vi.fn(),
  writeApplicationPublicationIntent: vi.fn()
}))
import ApplicationPublication from '../src/views/dashboard/applications/components/ApplicationPublication.vue'
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
    pending: null as null | { status: string; kind?: string },
    deleted: null as null | {
      projectId: string
      applicationId: string
      identity: number
      receipt: 'NO_CONTENT' | 'COMPLETION_MARKER'
    },
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
    softDelete: vi.fn(),
    retry: vi.fn(),
    recover: vi.fn(),
    reset: vi.fn()
  }
  mocks.create.mockImplementation((ports) => {
    changed = ports.changed
    return model
  })
  const wrapper = mount(ApplicationPublication, {
    props: {
      projectId: 'project',
      applicationId: 'dashboard',
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
        ElDivider: { template: '<div role="separator"><slot /></div>' },
        ElButton: {
          props: ['disabled'],
          template: '<button :disabled="disabled"><slot /></button>'
        },
        ElAlert: { props: ['title'], template: '<div>{{ title }}<slot /></div>' }
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
  mocks.user = reactive({ info: { userId: 'user', tenantId: 'tenant' } })
  mocks.confirm.mockResolvedValue(undefined)
})
describe('应用发布产品会话', () => {
  it('软删除无需已发布，后果确认明确不删除其他看板/分享/授权', async () => {
    const f = fixture()
    f.update({ catalog: { ...f.initial.catalog, currentVersionId: null as unknown as string } })
    await flushPromises()
    await f.wrapper.get('[data-testid="application-publication-soft-delete"]').trigger('click')
    await flushPromises()
    expect(mocks.confirm).toHaveBeenCalledWith(
      expect.stringContaining('只停止此应用'),
      '确认软删除应用',
      expect.objectContaining({ confirmButtonText: '软删除' })
    )
    expect(mocks.confirm.mock.calls[0]![0]).toContain('看板、分享和用户历史授权不会被删除')
    expect(f.model.softDelete).toHaveBeenCalledOnce()
    f.wrapper.unmount()
  })
  it.each([{ dirty: true }, { saving: true }, { conflict: true }, { canManage: false }])(
    '删除%s不能丢本地或绕过权限',
    async (patch) => {
      const f = fixture(patch)
      const button = f.wrapper.find('[data-testid="application-publication-soft-delete"]')
      if (button.exists()) {
        expect(button.attributes('disabled')).toBeDefined()
        await button.trigger('click')
      }
      expect(mocks.confirm).not.toHaveBeenCalled()
      expect(f.model.softDelete).not.toHaveBeenCalled()
      f.wrapper.unmount()
    }
  )
  it.each(['revision', 'identity', 'permission', 'dirty', 'saving', 'application'] as const)(
    '确认期间%s改变不提交旧删除',
    async (change) => {
      const f = fixture()
      let resolve!: () => void
      mocks.confirm.mockImplementationOnce(
        () =>
          new Promise<void>((done) => {
            resolve = done
          })
      )
      await f.wrapper.get('[data-testid="application-publication-soft-delete"]').trigger('click')
      if (change === 'revision')
        f.update({ catalog: { ...f.initial.catalog, publicationRevision: '4' } })
      if (change === 'identity') mocks.identity++
      if (change === 'permission') await f.wrapper.setProps({ canManage: false })
      if (change === 'dirty') await f.wrapper.setProps({ dirty: true })
      if (change === 'saving') await f.wrapper.setProps({ saving: true })
      if (change === 'application') await f.wrapper.setProps({ applicationId: 'other' })
      resolve()
      await flushPromises()
      expect(f.model.softDelete).not.toHaveBeenCalled()
      f.wrapper.unmount()
    }
  )
  it('删除未知跨离线保原实例，真实role变化清理，终态只通知一次', async () => {
    const f = fixture()
    f.update({ pending: { kind: 'SOFT_DELETE', status: 'UNKNOWN' } })
    await f.wrapper.setProps({ available: false })
    await f.wrapper.setProps({ available: true })
    expect(f.model.reset).toHaveBeenCalledTimes(1)
    expect(f.wrapper.emitted('deleteLock')?.at(-1)).toEqual([true])
    mocks.user.info.roles = ['ADMIN']
    await flushPromises()
    expect(f.model.reset).toHaveBeenCalledTimes(2)
    const deleted = {
      projectId: 'project',
      applicationId: 'dashboard',
      identity: 1,
      receipt: 'COMPLETION_MARKER' as const
    }
    f.update({ pending: null, deleted })
    f.update({ pending: null, deleted })
    expect(f.wrapper.emitted('deleted')).toEqual([[deleted]])
    f.wrapper.unmount()
  })
  it('读取当前版本、分页和详情不会改写草稿，管理按钮统一确认', async () => {
    const { wrapper, model, update } = fixture()
    expect(model.open).toHaveBeenCalledTimes(1)
    expect(
      wrapper.get('[data-testid="application-publication-status"]').attributes('data-version-id')
    ).toBe('version-2')
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
        snapshot: { displayName: '历史内容', dashboardRefs: [], entryDashboardId: '' }
      }
    })
    await flushPromises()
    expect(wrapper.text()).toContain('查看不会覆盖当前草稿')
    await wrapper.get('[data-testid="application-publication-publish"]').trigger('click')
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
    await wrapper.get('[data-testid="application-publication-withdraw"]').trigger('click')
    await flushPromises()
    expect(model.withdraw).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })
  it.each([{ dirty: true }, { saving: true }, { conflict: true }])(
    '草稿未稳定%s禁止发布',
    async (patch) => {
      const { wrapper, model } = fixture(patch)
      expect(
        wrapper.get('[data-testid="application-publication-publish"]').attributes('disabled')
      ).toBeDefined()
      await wrapper.get('[data-testid="application-publication-publish"]').trigger('click')
      expect(model.publish).not.toHaveBeenCalled()
      expect(mocks.confirm).not.toHaveBeenCalled()
      wrapper.unmount()
    }
  )
  it('只读用户可看历史但不显示写操作', () => {
    const { wrapper } = fixture({ canManage: false })
    expect(wrapper.find('[data-testid="application-publication-publish"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="application-publication-withdraw"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('回滚到版本')
    expect(wrapper.text()).toContain('查看版本 1')
    wrapper.unmount()
  })
  it('取消产品确认不写，确认期间换看板或身份也不得误发新上下文', async () => {
    const { wrapper, model } = fixture()
    mocks.confirm.mockRejectedValueOnce('cancel')
    await wrapper.get('[data-testid="application-publication-publish"]').trigger('click')
    await flushPromises()
    expect(model.publish).not.toHaveBeenCalled()
    let resolve!: () => void
    mocks.confirm.mockImplementationOnce(
      () =>
        new Promise<void>((done) => {
          resolve = done
        })
    )
    await wrapper.get('[data-testid="application-publication-publish"]').trigger('click')
    await wrapper.setProps({ applicationId: 'another' })
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
    expect(
      wrapper.get('[data-testid="application-publication-publish"]').attributes('disabled')
    ).toBeDefined()
    await wrapper
      .findAll('button')
      .find((button) => button.text() === '重试原操作')!
      .trigger('click')
    expect(model.retry).toHaveBeenCalledTimes(1)
    await wrapper.get('[data-testid="application-publication-refresh"]').trigger('click')
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
    expect(
      wrapper.get('[data-testid="application-publication-refresh"]').attributes('disabled')
    ).toBeDefined()
    wrapper.unmount()
  })
})

it('等值用户信息刷新不重置发布恢复状态，实际身份变化才清理', async () => {
  const { wrapper, model, update } = fixture()
  update({ pending: { status: 'UNKNOWN' } })
  await flushPromises()
  const count = model.reset.mock.calls.length
  mocks.user.info = { ...mocks.user.info }
  await flushPromises()
  expect(model.reset).toHaveBeenCalledTimes(count)
  await wrapper
    .findAll('button')
    .find((button) => button.text() === '重试原操作')!
    .trigger('click')
  expect(model.retry).toHaveBeenCalledTimes(1)
  mocks.user.info = { userId: 'other', tenantId: 'tenant' }
  await flushPromises()
  expect(model.reset).toHaveBeenCalledTimes(count + 1)
  wrapper.unmount()
})
