import { defineComponent, h, reactive } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter, isNavigationFailure, RouterView } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { currentIdentityEpoch, invalidateIdentity } from '@/utils/http/identity-scope'
const mocks = vi.hoisted(() => ({
  user: {} as {
    info: {
      currentProjectId: string
      userId: string
      tenantId: string
      roles: string[]
      buttons: string[]
    }
  },
  confirm: vi.fn(),
  list: vi.fn(),
  draft: vi.fn(),
  create: vi.fn(),
  save: vi.fn(),
  dashboards: vi.fn(),
  versions: vi.fn(),
  catalog: vi.fn(),
  history: vi.fn(),
  version: vi.fn(),
  write: vi.fn()
}))
vi.mock('@/store/modules/user', () => ({ useUserStore: () => mocks.user }))
vi.mock('element-plus', () => ({ ElMessageBox: { confirm: mocks.confirm } }))
vi.mock('@/api/application', () => ({
  fetchApplications: mocks.list,
  fetchApplicationDraft: mocks.draft,
  createApplication: mocks.create,
  saveApplicationDraft: mocks.save
}))
vi.mock('@/api/dashboard', () => ({ fetchDashboards: mocks.dashboards }))
vi.mock('@/api/dashboard-publication', () => ({ fetchDashboardPublicationHistory: mocks.versions }))
vi.mock('@/api/application-publication', () => ({
  fetchApplicationPublicationCatalog: mocks.catalog,
  fetchApplicationPublicationHistory: mocks.history,
  fetchApplicationPublicationVersion: mocks.version,
  writeApplicationPublicationIntent: mocks.write
}))
import Manager from '@/views/dashboard/applications/index.vue'
import Publication from '@/views/dashboard/applications/components/ApplicationPublication.vue'
const id = (n: number) => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`
const project = id(100),
  application = id(101),
  other = id(102),
  dashboard = id(201),
  version = id(202)
const content = () => ({
  formatVersion: 'tc.application/v1',
  displayName: '原稿',
  hostCompatibility: { minInclusive: '1.0.0', maxExclusive: '2.0.0' },
  dashboardRefs: [{ dashboardId: dashboard, dashboardVersionId: version, title: '原引用' }],
  entryDashboardId: dashboard
})
const directory = () => ({
  items: [
    { id: application, managementName: '应用甲' },
    { id: other, managementName: '应用乙' }
  ],
  hasMore: false,
  nextCursor: null
})
const catalog = (id: string) => ({
  id,
  managementName: '应用',
  appKey: 'app_' + 'a'.repeat(32),
  publicationRevision: '3',
  currentVersionId: version,
  createdAt: '2026-09-08T00:00:00Z',
  updatedAt: '2026-09-08T00:00:00Z'
})
function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((yes) => {
    resolve = yes
  })
  return { promise, resolve }
}
const wrappers: ReturnType<typeof mount>[] = []
async function fixture() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/apps', component: Manager },
      { path: '/other', component: { template: '<p>其他页面</p>' } }
    ]
  })
  await router.push('/apps')
  const wrapper = mount(defineComponent({ setup: () => () => h(RouterView) }), {
    global: {
      plugins: [router],
      stubs: {
        ElButton: {
          props: ['disabled'],
          template: '<button :disabled="disabled"><slot /></button>'
        },
        ElAlert: { props: ['title'], template: '<p>{{ title }}</p>' },
        ElEmpty: true
      }
    }
  })
  wrappers.push(wrapper)
  await flushPromises()
  await wrapper.get('[data-testid="application-directory-refresh"]').trigger('click')
  await flushPromises()
  await wrapper.get(`[data-testid="application-open-${application}"]`).trigger('click')
  await flushPromises()
  return { wrapper, router, child: wrapper.findComponent(Publication) }
}
async function deleteUnknown(f: Awaited<ReturnType<typeof fixture>>) {
  mocks.write.mockRejectedValueOnce(
    Object.assign(new Error('删除响应未知'), { outcomeUnknown: true })
  )
  await f.wrapper.get('[data-testid="application-publication-soft-delete"]').trigger('click')
  await flushPromises()
  return structuredClone(mocks.write.mock.calls.at(-1)![0])
}
async function retryCompleted(f: Awaited<ReturnType<typeof fixture>>) {
  mocks.list.mockResolvedValueOnce({
    items: [{ id: other, managementName: '应用乙' }],
    hasMore: false,
    nextCursor: null
  })
  mocks.write.mockRejectedValueOnce({ code: 10014, status: 409, outcomeUnknown: false })
  await f.wrapper.get('[data-testid="application-publication-retry"]').trigger('click')
  await flushPromises()
}
beforeEach(() => {
  vi.clearAllMocks()
  invalidateIdentity()
  Object.defineProperty(navigator, 'onLine', { configurable: true, value: true })
  mocks.user = reactive({
    info: {
      currentProjectId: project,
      userId: id(301),
      tenantId: id(302),
      roles: ['ADMIN'],
      buttons: ['application:read', 'application:manage', 'dashboard_definition:read']
    }
  })
  mocks.confirm.mockResolvedValue(undefined)
  mocks.list.mockResolvedValue(directory())
  mocks.draft.mockImplementation(async (_project: string, applicationId: string) => ({
    applicationId,
    revision: '7',
    content: content()
  }))
  mocks.catalog.mockImplementation(async (_project: string, applicationId: string) =>
    catalog(applicationId)
  )
  mocks.history.mockResolvedValue({
    items: [
      {
        id: version,
        versionNumber: '2',
        sourceDraftRevision: '7',
        snapshotDigestAlgorithm: 'PG_JSONB_TEXT_V1_SHA256',
        snapshotDigest: 'a'.repeat(64),
        publishedAt: '2026-09-08T00:00:00Z'
      }
    ],
    hasMore: false,
    nextCursor: null
  })
  mocks.dashboards.mockResolvedValue({
    items: [{ id: dashboard, managementName: '引用候选' }],
    hasMore: false,
    nextCursor: null
  })
  mocks.versions.mockResolvedValue({
    items: [{ id: version, versionNumber: '2' }],
    hasMore: false,
    nextCursor: null
  })
})
afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
})
describe('真实应用模型与宿主的删除会话', () => {
  it('UNKNOWN→整体404清内容保同一Publication及原键→10014终态，不伪204或换键', async () => {
    const f = await fixture(),
      instance = f.child.vm.$.uid
    const first = await deleteUnknown(f)
    expect(f.wrapper.get('[data-testid="application-delete-lock"]').text()).toContain(
      '删除结果待确认'
    )
    expect(f.wrapper.get('[aria-label="公开展示名"]').attributes('disabled')).toBeDefined()
    expect(f.wrapper.get('[data-testid="application-save"]').attributes('disabled')).toBeDefined()
    expect(f.wrapper.get('[data-testid="application-create"]').attributes('disabled')).toBeDefined()
    expect(
      f.wrapper.get('[data-testid="application-directory-refresh"]').attributes('disabled')
    ).toBeDefined()
    mocks.catalog.mockRejectedValueOnce({ code: 60030, status: 404 })
    await f.wrapper.get('[data-testid="application-publication-refresh"]').trigger('click')
    await flushPromises()
    expect(f.wrapper.findComponent(Publication).vm.$.uid).toBe(instance)
    expect(f.wrapper.find('[aria-label="应用草稿"]').exists()).toBe(false)
    expect(f.wrapper.find('[aria-label="公开展示名"]').exists()).toBe(false)
    expect(f.wrapper.find('[data-testid="application-publication-status"]').exists()).toBe(false)
    expect(
      f.wrapper.get('[data-testid="application-publication-retry"]').attributes('disabled')
    ).toBeUndefined()
    await retryCompleted(f)
    expect(mocks.write.mock.calls[1]![0]).toEqual(first)
    expect(mocks.write).toHaveBeenCalledTimes(2)
    expect(f.wrapper.get('[data-testid="application-delete-result"]').text()).toContain(
      '不重放原204'
    )
    expect(f.wrapper.findComponent(Publication).exists()).toBe(false)
    expect(f.wrapper.find('[aria-label="应用草稿"]').exists()).toBe(false)
    expect(f.wrapper.find(`[data-testid="application-open-${application}"]`).exists()).toBe(false)
    expect(f.wrapper.find(`[data-testid="application-open-${other}"]`).exists()).toBe(true)
    expect(mocks.draft).toHaveBeenCalledOnce()
    expect(mocks.save).not.toHaveBeenCalled()
  })
  it('删除UNKNOWN不能通过discard、离开、同路由更新、create/save/reload/compare/引用旁路', async () => {
    const f = await fixture()
    await deleteUnknown(f)
    expect(isNavigationFailure(await f.router.push('/other'))).toBe(true)
    expect(isNavigationFailure(await f.router.push({ query: { changed: 'yes' } }))).toBe(true)
    const event = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(event)
    expect(event.defaultPrevented).toBe(true)
    for (const text of [
      '读取远端比较',
      '丢弃本地并重载远端',
      '读取看板目录',
      '保存应用草稿',
      '创建应用'
    ]) {
      const button = f.wrapper.findAll('button').find((button) => button.text() === text)!
      expect(button.attributes('disabled')).toBeDefined()
      await button.trigger('click')
    }
    expect(mocks.confirm).toHaveBeenCalledOnce()
    expect(mocks.draft).toHaveBeenCalledOnce()
    expect(mocks.save).not.toHaveBeenCalled()
    expect(mocks.create).not.toHaveBeenCalled()
    expect(mocks.dashboards).not.toHaveBeenCalled()
    expect(mocks.versions).not.toHaveBeenCalled()
  })
  it('offline在途204未采纳→online恢复60030→仍原键→10014，保宿主并清公开内容', async () => {
    const f = await fixture(),
      instance = f.child.vm.$.uid
    const write = deferred<unknown>()
    mocks.write.mockReturnValueOnce(write.promise)
    await f.wrapper.get('[data-testid="application-publication-soft-delete"]').trigger('click')
    await flushPromises()
    const first = structuredClone(mocks.write.mock.calls[0]![0])
    Object.defineProperty(navigator, 'onLine', { configurable: true, value: false })
    window.dispatchEvent(new Event('offline'))
    write.resolve(undefined)
    await flushPromises()
    expect(f.wrapper.findComponent(Publication).vm.$.uid).toBe(instance)
    expect(f.wrapper.find('[aria-label="公开展示名"]').exists()).toBe(false)
    expect(
      f.wrapper.get('[data-testid="application-publication-retry"]').attributes('disabled')
    ).toBeDefined()
    Object.defineProperty(navigator, 'onLine', { configurable: true, value: true })
    window.dispatchEvent(new Event('online'))
    await flushPromises()
    mocks.write.mockRejectedValueOnce({ code: 60030, status: 404, outcomeUnknown: false })
    await f.wrapper.get('[data-testid="application-publication-retry"]').trigger('click')
    await flushPromises()
    expect(f.wrapper.findComponent(Publication).vm.$.uid).toBe(instance)
    expect(mocks.write.mock.calls[1]![0]).toEqual(first)
    await retryCompleted(f)
    expect(mocks.write.mock.calls[2]![0]).toEqual(first)
    expect(f.wrapper.get('[data-testid="application-delete-result"]').text()).toContain(
      '不重放原204'
    )
  })
  it.each([
    { code: 20001, status: 401 },
    { code: 403, status: 403 },
    { code: 50001, status: 404 }
  ])('删除UNKNOWN后的真实访问拒绝%s清宿主/原键，不能吞为resource404', async (error) => {
    const f = await fixture()
    await deleteUnknown(f)
    mocks.catalog.mockRejectedValueOnce(error)
    await f.wrapper.get('[data-testid="application-publication-refresh"]').trigger('click')
    await flushPromises()
    expect(f.wrapper.findComponent(Publication).exists()).toBe(false)
    expect(f.wrapper.find('[data-testid="application-delete-lock"]').exists()).toBe(false)
    expect(f.wrapper.find('[data-testid="application-delete-result"]').exists()).toBe(false)
    expect(mocks.write).toHaveBeenCalledOnce()
  })
  it.each(['currentProjectId', 'userId', 'tenantId', 'roles', 'buttons', 'epoch'])(
    '实际scope撤销%s销毁旧删除意图',
    async (field) => {
      const f = await fixture()
      await deleteUnknown(f)
      if (field === 'epoch') invalidateIdentity()
      else if (field === 'buttons') mocks.user.info.buttons = ['application:read']
      else if (field === 'roles') mocks.user.info.roles = ['VIEWER']
      else mocks.user.info[field as 'currentProjectId' | 'userId' | 'tenantId'] = id(500)
      await flushPromises()
      expect(f.wrapper.findComponent(Publication).exists()).toBe(false)
      expect(f.wrapper.find('[data-testid="application-delete-lock"]').exists()).toBe(false)
      expect(mocks.write).toHaveBeenCalledOnce()
    }
  )
  it('等值info刷新/仅看板选择资格变动保删除恢复，单个60048不清整个App', async () => {
    const f = await fixture(),
      instance = f.child.vm.$.uid
    await deleteUnknown(f)
    mocks.user.info = {
      ...mocks.user.info,
      roles: [...mocks.user.info.roles],
      buttons: ['application:read', 'application:manage']
    }
    await flushPromises()
    expect(f.wrapper.findComponent(Publication).vm.$.uid).toBe(instance)
    mocks.version.mockRejectedValueOnce({ code: 60048, status: 404 })
    await f.wrapper
      .findAll('button')
      .find((button) => button.text() === '查看版本 2')!
      .trigger('click')
    await flushPromises()
    expect(f.wrapper.findComponent(Publication).vm.$.uid).toBe(instance)
    expect(f.wrapper.find('[aria-label="应用草稿"]').exists()).toBe(true)
  })
  it('late directory/compare/candidate响应在删除冻结后均不能回填；204终态只新目录', async () => {
    const f = await fixture()
    const oldDirectory = deferred<ReturnType<typeof directory>>()
    const oldCompare = deferred<unknown>(),
      oldCandidates = deferred<unknown>()
    mocks.list.mockReturnValueOnce(oldDirectory.promise)
    mocks.draft.mockReturnValueOnce(oldCompare.promise)
    mocks.dashboards.mockReturnValueOnce(oldCandidates.promise)
    await f.wrapper.get('[data-testid="application-directory-refresh"]').trigger('click')
    await f.wrapper
      .findAll('button')
      .find((button) => button.text() === '读取远端比较')!
      .trigger('click')
    await f.wrapper
      .findAll('button')
      .find((button) => button.text() === '读取看板目录')!
      .trigger('click')
    await deleteUnknown(f)
    oldDirectory.resolve(directory())
    oldCompare.resolve({ applicationId: application, revision: '8', content: content() })
    oldCandidates.resolve({
      items: [{ id: dashboard, managementName: '旧候选' }],
      hasMore: false,
      nextCursor: null
    })
    await flushPromises()
    expect(f.wrapper.findAll('[aria-label="应用目录"] li')).toHaveLength(0)
    expect(f.wrapper.text()).not.toContain('远端草稿（只读比较）')
    expect(f.wrapper.get('[aria-label="看板"]').text()).not.toContain('旧候选')
    mocks.list.mockResolvedValueOnce({
      items: [{ id: other, managementName: '应用乙' }],
      hasMore: false,
      nextCursor: null
    })
    mocks.write.mockResolvedValueOnce(undefined)
    await f.wrapper.get('[data-testid="application-publication-retry"]').trigger('click')
    await flushPromises()
    expect(f.wrapper.get('[data-testid="application-delete-result"]').text()).toContain('收到204')
    expect(f.wrapper.findComponent(Publication).exists()).toBe(false)
    expect(f.wrapper.find(`[data-testid="application-open-${other}"]`).exists()).toBe(true)
  })
  it('终态目录失败明确且可重试，不让旧读取/旧id复活', async () => {
    const f = await fixture()
    mocks.list.mockRejectedValueOnce(new Error('directory failed'))
    mocks.write.mockResolvedValueOnce(undefined)
    await f.wrapper.get('[data-testid="application-publication-soft-delete"]').trigger('click')
    await flushPromises()
    expect(f.wrapper.get('[data-testid="application-delete-result"]').text()).toContain('已软删除')
    expect(f.wrapper.get('[data-testid="application-error"]').text()).toContain('应用目录读取失败')
    expect(f.wrapper.findComponent(Publication).exists()).toBe(false)
    mocks.list.mockResolvedValueOnce({
      items: [{ id: other, managementName: '应用乙' }],
      hasMore: false,
      nextCursor: null
    })
    await f.wrapper.get('[data-testid="application-directory-refresh"]').trigger('click')
    await flushPromises()
    expect(f.wrapper.find(`[data-testid="application-open-${other}"]`).exists()).toBe(true)
    expect(f.wrapper.find('[data-testid="application-error"]').exists()).toBe(false)
    expect(currentIdentityEpoch()).toBeGreaterThan(0)
  })
})
