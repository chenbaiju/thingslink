import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { reactive, nextTick } from 'vue'
import { createHash } from 'node:crypto'
import Panel from '@/components/agent/PersonalFactCollectionPanel.vue'
import { generatePersonalFactCollection, listPersonalRecords } from '@/api/assistant-records'
import { invalidateIdentity } from '@/utils/http/identity-scope'
const state = vi.hoisted(() => ({ user: {} as any }))
vi.mock('@/store/modules/user', () => ({ useUserStore: () => state.user }))
vi.mock('@/api/assistant-records', () => ({
  generatePersonalFactCollection: vi.fn(),
  listPersonalRecords: vi.fn()
}))
const id = (n: number) => `${n.toString(16).padStart(8, '0')}-0000-4000-8000-000000000000`
const row = (n: number, device = n) => ({
  id: id(n),
  deviceId: id(device + 20),
  modelVersionId: id(50),
  createdAt: '2026-10-04T00:00:00Z',
  expiresAt: '2026-11-03T00:00:00Z',
  contentSha256: 'a'.repeat(64)
})
const first = row(1),
  second = { ...row(2), expiresAt: '2026-11-02T00:00:00Z' }
const markdown = '# 历史集合\n\n<img src=x onerror=alert(1)>\n值 0 / false；缺项不代表正常。\n'
const digest = (text: string) => createHash('sha256').update(text, 'utf8').digest('hex')
const result = () => ({
  schemaVersion: 1,
  mode: 'FACTS_ONLY',
  scope: 'SELECTED_PERSONAL_RECORDS',
  sourceRecords: [{ ...first }, { ...second }],
  coverage: {
    devices: 2,
    selectedProperties: 4,
    availableValues: 2,
    unavailableValues: 2,
    omittedValues: 1
  },
  earliestCollectionAt: '2026-10-04T00:00:00Z',
  latestCollectionAt: '2026-10-04T00:02:00Z',
  expiresAt: second.expiresAt,
  contentSha256: digest(markdown),
  markdown
})
const originalURL = globalThis.URL,
  urlCreate = vi.fn(() => 'blob:collection-test'),
  urlRevoke = vi.fn(),
  hash = vi.fn()
let panel: VueWrapper, clicked: ReturnType<typeof vi.spyOn>
const vm = () => (panel.vm as any).$?.setupState as any
async function page() {
  panel = mount(Panel, {
    props: { projectId: 'p' },
    global: {
      stubs: {
        ElButton: { template: '<button><slot /></button>' },
        ElAlert: { props: ['title'], template: '<div>{{ title }}<slot /></div>' },
        ElCard: { template: '<section><slot name="header" /><slot /></section>' }
      }
    }
  })
  await vm().refresh()
  vm().toggle(second.id)
  vm().toggle(first.id)
  await nextTick()
}
beforeEach(() => {
  vi.resetAllMocks()
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(new Date('2026-10-04T12:00:00Z'))
  state.user = reactive({
    isLogin: true,
    info: { userId: 'u', tenantId: 't', currentProjectId: 'p', roles: ['VIEWER'] }
  })
  vi.mocked(listPersonalRecords).mockResolvedValue([{ ...first }, { ...second }])
  vi.mocked(generatePersonalFactCollection).mockResolvedValue(result() as any)
  hash.mockImplementation(
    async (_algorithm, bytes) => Uint8Array.from(createHash('sha256').update(bytes).digest()).buffer
  )
  vi.stubGlobal('crypto', { subtle: { digest: hash } })
  vi.stubGlobal(
    'URL',
    class extends originalURL {
      static createObjectURL = urlCreate
      static revokeObjectURL = urlRevoke
    }
  )
  clicked = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
})
afterEach(() => {
  panel?.unmount()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
  vi.useRealTimers()
})
it.each(['OWNER', 'ADMIN', 'OPERATOR', 'VIEWER'])(
  '%s uses only explicit refresh and manual canonical generation',
  async (role) => {
    state.user.info.roles = [role]
    await page()
    expect(listPersonalRecords).toHaveBeenCalledOnce()
    expect(generatePersonalFactCollection).not.toHaveBeenCalled()
    await vm().generate()
    expect(generatePersonalFactCollection).toHaveBeenCalledWith(
      'p',
      [first.id, second.id],
      expect.any(AbortSignal)
    )
    expect(panel.get('[data-testid="personal-collection-report"] pre').text()).toBe(markdown.trim())
    expect(panel.findAll('img')).toHaveLength(0)
    expect(urlCreate).not.toHaveBeenCalled()
    expect(vm().report.sourceRecords[0].modelVersionId).toBe(first.modelVersionId)
  }
)
it('mount does not issue a catalog or report request', async () => {
  panel = mount(Panel, {
    props: { projectId: 'p' },
    global: { stubs: { ElCard: { template: '<section><slot /></section>' } } }
  })
  await nextTick()
  expect(listPersonalRecords).not.toHaveBeenCalled()
  expect(generatePersonalFactCollection).not.toHaveBeenCalled()
})
it('one source per device and at most five devices remain enforced at the request boundary', async () => {
  vi.mocked(listPersonalRecords).mockResolvedValue([
    first,
    row(7, 1),
    ...Array.from({ length: 5 }, (_, n) => row(n + 2))
  ])
  await page()
  vm().toggle(id(7))
  expect(vm().selected).not.toContain(id(7))
  vm().toggle(id(3))
  vm().toggle(id(4))
  vm().toggle(id(5))
  vm().toggle(id(6))
  expect(vm().selected).toHaveLength(5)
  vm().selected = [first.id, id(7)]
  await vm().generate()
  expect(generatePersonalFactCollection).not.toHaveBeenCalled()
  vm().selected = Array.from({ length: 6 }, (_, n) => id(n + 1))
  await vm().generate()
  expect(generatePersonalFactCollection).not.toHaveBeenCalled()
  vm().selected = [first.id, first.id]
  await vm().generate()
  expect(generatePersonalFactCollection).not.toHaveBeenCalled()
})
it('empty catalog is not a normal diagnosis and cannot generate', async () => {
  vi.mocked(listPersonalRecords).mockResolvedValue([])
  await page()
  await vm().generate()
  expect(panel.text()).toContain('空记录不代表设备正常')
  expect(generatePersonalFactCollection).not.toHaveBeenCalled()
})
it.each(['tooMany', 'duplicate', 'expired', 'invalidTime', 'invalidId', 'hash'])(
  'rejects an invalid %s personal catalog',
  async (kind) => {
    let entries: any[] = [first, second]
    if (kind === 'tooMany') entries = Array.from({ length: 101 }, (_, n) => row(n + 1))
    if (kind === 'duplicate') entries = [first, first]
    if (kind === 'expired') entries = [{ ...first, expiresAt: first.createdAt }]
    if (kind === 'invalidTime') entries = [{ ...first, createdAt: 'bad' }]
    if (kind === 'invalidId') entries = [{ ...first, deviceId: 'raw/name' }]
    if (kind === 'hash') entries = [{ ...first, contentSha256: 'bad' }]
    vi.mocked(listPersonalRecords).mockResolvedValue(entries)
    await page()
    await vm().generate()
    expect(vm().rows).toEqual([])
    expect(panel.text()).toContain('本人记录不可读')
    expect(generatePersonalFactCollection).not.toHaveBeenCalled()
  }
)
it('download always freshly calls the server and releases its bounded filename URL', async () => {
  await page()
  await vm().generate()
  await vm().generate(true)
  expect(generatePersonalFactCollection).toHaveBeenCalledTimes(2)
  expect(clicked).toHaveBeenCalledOnce()
  expect(urlCreate).toHaveBeenCalledOnce()
  const link = clicked.mock.instances[0] as HTMLAnchorElement
  expect(link.download).toBe(`personal-fact-collection-${first.id}_${second.id}.md`)
  await new Promise((resolve) => setTimeout(resolve, 5))
  expect(urlRevoke).toHaveBeenCalledWith('blob:collection-test')
  vi.mocked(generatePersonalFactCollection).mockRejectedValue(new Error('deleted'))
  await vm().generate(true)
  expect(vm().report).toBeUndefined()
  expect(clicked).toHaveBeenCalledOnce()
  expect(panel.text()).toContain('未显示或下载')
})
it.each([
  'version',
  'mode',
  'scope',
  'order',
  'sourceHash',
  'sourceDevice',
  'sourceModel',
  'sourceTime',
  'devices',
  'sum',
  'omitted',
  'negative',
  'fraction',
  'tooFewProperties',
  'tooManyProperties',
  'timeEnvelope',
  'expiry',
  'bodyHash',
  'oversize'
])('rejects %s without rendering, downloading or retrying', async (kind) => {
  await page()
  const broken: any = result()
  if (kind === 'version') broken.schemaVersion = '1'
  if (kind === 'mode') broken.mode = 'DIAGNOSIS'
  if (kind === 'scope') broken.scope = 'ALL_PROJECT'
  if (kind === 'order') broken.sourceRecords.reverse()
  if (kind === 'sourceHash') broken.sourceRecords[0].contentSha256 = 'b'.repeat(64)
  if (kind === 'sourceDevice') broken.sourceRecords[0].deviceId = id(99)
  if (kind === 'sourceModel') broken.sourceRecords[0].modelVersionId = id(99)
  if (kind === 'sourceTime') broken.sourceRecords[0].createdAt = '2026-10-04T00:01:00Z'
  if (kind === 'devices') broken.coverage.devices = 3
  if (kind === 'sum') broken.coverage.availableValues = 3
  if (kind === 'omitted') broken.coverage.omittedValues = 3
  if (kind === 'negative') broken.coverage.availableValues = -1
  if (kind === 'fraction') broken.coverage.selectedProperties = 4.5
  if (kind === 'tooFewProperties') {
    broken.coverage.selectedProperties = 1
    broken.coverage.availableValues = 0
    broken.coverage.unavailableValues = 1
  }
  if (kind === 'tooManyProperties') {
    broken.coverage.selectedProperties = 21
    broken.coverage.unavailableValues = 19
  }
  if (kind === 'timeEnvelope') broken.latestCollectionAt = '2026-10-03T00:00:00Z'
  if (kind === 'expiry') broken.expiresAt = first.expiresAt
  if (kind === 'bodyHash') broken.markdown += 'tampered'
  if (kind === 'oversize') broken.markdown = '中'.repeat(350000)
  vi.mocked(generatePersonalFactCollection).mockResolvedValue(broken)
  await vm().generate(true)
  expect(generatePersonalFactCollection).toHaveBeenCalledOnce()
  expect(vm().report).toBeUndefined()
  expect(urlCreate).not.toHaveBeenCalled()
  expect(clicked).not.toHaveBeenCalled()
  expect(panel.text()).toContain('未显示或下载')
})
it('selection, actual epoch and refresh clear a previously displayed report', async () => {
  await page()
  await vm().generate()
  vm().toggle(first.id)
  expect(vm().report).toBeUndefined()
  vm().toggle(first.id)
  await vm().generate()
  invalidateIdentity()
  await nextTick()
  expect(vm().report).toBeUndefined()
  expect(vm().rows).toEqual([])
  await vm().refresh()
  vm().toggle(first.id)
  vm().toggle(second.id)
  await vm().generate()
  await vm().refresh()
  expect(vm().report).toBeUndefined()
  expect(vm().selected).toEqual([])
})
it.each(['project', 'account', 'tenant', 'role', 'logout', 'selection'])(
  'discards a late report on %s transition',
  async (kind) => {
    await page()
    let resolve: (v: any) => void = () => {}
    vi.mocked(generatePersonalFactCollection).mockImplementation(
      () =>
        new Promise((r) => {
          resolve = r
        })
    )
    const pending = vm().generate(true)
    if (kind === 'project') state.user.info.currentProjectId = 'other'
    if (kind === 'account') state.user.info.userId = 'other'
    if (kind === 'tenant') state.user.info.tenantId = 'other'
    if (kind === 'role') state.user.info.roles = []
    if (kind === 'logout') state.user.isLogin = false
    if (kind === 'selection') vm().selected = []
    await nextTick()
    expect(vi.mocked(generatePersonalFactCollection).mock.calls[0][2].aborted).toBe(true)
    resolve(result())
    await pending
    expect(vm().report).toBeUndefined()
    expect(clicked).not.toHaveBeenCalled()
  }
)
it('a late catalog cannot repopulate another project', async () => {
  await page()
  let resolve: (v: any) => void = () => {}
  vi.mocked(listPersonalRecords).mockImplementation(
    () =>
      new Promise((r) => {
        resolve = r
      })
  )
  const pending = vm().refresh()
  state.user.info.currentProjectId = 'other'
  resolve([first, second])
  await pending
  expect(vm().rows).toEqual([])
  expect(vm().selected).toEqual([])
})
it.each(['identity', 'expiry'])(
  'discards a download after %s changes during digest',
  async (kind) => {
    await page()
    let resolve: (v: ArrayBuffer) => void = () => {}
    hash.mockImplementation(
      () =>
        new Promise((r) => {
          resolve = r
        })
    )
    const pending = vm().generate(true)
    await flushPromises()
    if (kind === 'identity') invalidateIdentity()
    else vi.setSystemTime(new Date(second.expiresAt))
    resolve(Uint8Array.from(createHash('sha256').update(markdown).digest()).buffer)
    await pending
    expect(vm().report).toBeUndefined()
    expect(clicked).not.toHaveBeenCalled()
    expect(urlCreate).not.toHaveBeenCalled()
  }
)
it('time advancing after selection is checked again before a server call', async () => {
  await page()
  expect(vm().canGenerate).toBe(true)
  vi.setSystemTime(new Date(second.expiresAt))
  await vm().generate(true)
  expect(generatePersonalFactCollection).not.toHaveBeenCalled()
  expect(vm().report).toBeUndefined()
  expect(clicked).not.toHaveBeenCalled()
})

it('five distinct devices can generate a valid bounded selected collection', async () => {
  const sources = Array.from({ length: 5 }, (_, n) => row(n + 1))
  vi.mocked(listPersonalRecords).mockResolvedValue(sources)
  await page()
  for (const source of sources.slice(2)) vm().toggle(source.id)
  const value = {
    ...result(),
    sourceRecords: sources,
    expiresAt: first.expiresAt,
    coverage: {
      devices: 5,
      selectedProperties: 5,
      availableValues: 0,
      unavailableValues: 5,
      omittedValues: 0
    }
  }
  vi.mocked(generatePersonalFactCollection).mockResolvedValue(value as any)
  await vm().generate()
  expect(vm().report.coverage.devices).toBe(5)
  expect(generatePersonalFactCollection).toHaveBeenCalledWith(
    'p',
    sources.map((source) => source.id),
    expect.any(AbortSignal)
  )
})
