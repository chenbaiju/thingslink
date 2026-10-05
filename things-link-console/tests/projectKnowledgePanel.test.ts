import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
import { reactive, nextTick } from 'vue'
import Panel from '@/components/agent/ProjectKnowledgePanel.vue'
import * as api from '@/api/assistant-knowledge'
import { invalidateIdentity } from '@/utils/http/identity-scope'
import { fetchProjects } from '@/api/project'
const state = vi.hoisted(() => ({ user: {} as any, epoch: 1 }))
vi.mock('@/store/modules/user', () => ({ useUserStore: () => state.user }))
vi.mock('@/api/project', () => ({ fetchProjects: vi.fn() }))
vi.mock('@/api/assistant-knowledge', () => ({
  listKnowledge: vi.fn(),
  readKnowledge: vi.fn(),
  publishKnowledge: vi.fn(),
  deleteKnowledge: vi.fn(),
  searchKnowledge: vi.fn()
}))
const row = {
  id: '019a0000-0000-7000-8000-000000000001',
  sourceKey: 'guide',
  versionNumber: 1,
  createdAt: '2026-10-04T00:00:00Z',
  contentSha256: 'a'.repeat(64)
}
const source = { source: row, content: '灌溉说明' }
const matched = {
  projectId: 'p',
  collectedAt: row.createdAt,
  mode: 'LOCAL_LITERAL',
  state: 'MATCHED',
  externalAllowed: false,
  hits: [{ source: row, startCodePoint: 0, endCodePoint: 4, truncated: false, text: '灌溉说明' }]
}
let panel: VueWrapper
const vm = () => (panel.vm as any).$?.setupState as any
function page() {
  panel = mount(Panel, {
    props: { projectId: 'p' },
    global: {
      stubs: {
        ElCard: { template: '<section><slot name="header"/><slot/></section>' },
        ElButton: { template: '<button><slot/></button>' },
        ElAlert: { props: ['title'], template: '<p>{{title}}</p>' }
      }
    }
  })
}
beforeEach(() => {
  vi.resetAllMocks()
  state.epoch = 1
  state.user = reactive({
    isLogin: true,
    info: { userId: 'u', tenantId: 't', currentProjectId: 'p', roles: ['OWNER'] }
  })
  vi.mocked(api.listKnowledge).mockResolvedValue([row])
  vi.mocked(api.readKnowledge).mockResolvedValue(source)
  vi.mocked(fetchProjects).mockResolvedValue([{ id: 'p', status: 'ACTIVE' }] as any)
  vi.mocked(api.searchKnowledge).mockResolvedValue(matched as any)
  vi.mocked(api.publishKnowledge).mockResolvedValue({
    ...row,
    id: '019a0000-0000-7000-8000-000000000002',
    versionNumber: 2
  })
})
afterEach(() => panel?.unmount())
it.each(['OWNER', 'ADMIN', 'OPERATOR', 'VIEWER'])(
  '%s acts manually and only reads approved sources',
  async (role) => {
    state.user.info.roles = [role]
    page()
    expect(api.listKnowledge).not.toHaveBeenCalled()
    expect(api.searchKnowledge).not.toHaveBeenCalled()
    await vm().refresh()
    await vm().open(row)
    vm().keywords = '灌溉'
    await vm().search()
    expect(api.searchKnowledge).toHaveBeenCalledExactlyOnceWith(
      'p',
      ['灌溉'],
      expect.any(AbortSignal)
    )
    expect(panel.text()).toContain(row.contentSha256)
    expect(panel.text()).toContain('不是诊断结论')
    vm().approved = true
    await vm().publish()
    await vm().remove()
    if (role === 'OPERATOR' || role === 'VIEWER') {
      expect(api.publishKnowledge).not.toHaveBeenCalled()
      expect(api.deleteKnowledge).not.toHaveBeenCalled()
    }
  }
)
it('requires open current version and explicit per-publication approval', async () => {
  page()
  await vm().refresh()
  vm().sourceKey = 'guide'
  vm().content = '灌溉新说明'
  vm().approved = true
  await vm().publish()
  expect(api.publishKnowledge).not.toHaveBeenCalled()
  await vm().open(row)
  vm().content = 'cafe\u0301\r\n灌溉说明'
  await vm().publish()
  expect(api.publishKnowledge).not.toHaveBeenCalled()
  vm().approved = true
  await vm().publish()
  expect(api.publishKnowledge).toHaveBeenCalledExactlyOnceWith(
    'p',
    'guide',
    row.id,
    'café\n灌溉说明',
    expect.any(AbortSignal)
  )
  expect(vm().approved).toBe(false)
  expect(vm().content).toBe('')
  expect(vm().detail).toBeUndefined()
})
it('new source uses null expected version and deletion uses exact current UUID', async () => {
  page()
  await vm().refresh()
  vm().newSource()
  vm().sourceKey = 'new_guide'
  vm().content = '灌溉说明'
  vm().approved = true
  vi.mocked(api.publishKnowledge).mockResolvedValue({
    ...row,
    id: '019a0000-0000-7000-8000-000000000002',
    sourceKey: 'new_guide'
  })
  await vm().publish()
  expect(api.publishKnowledge).toHaveBeenCalledWith(
    'p',
    'new_guide',
    null,
    '灌溉说明',
    expect.any(AbortSignal)
  )
  await vm().open(row)
  await vm().remove()
  expect(api.deleteKnowledge).toHaveBeenCalledExactlyOnceWith(
    'p',
    'guide',
    row.id,
    expect.any(AbortSignal)
  )
})
it.each(['ARCHIVED', '', 'PURGING'])(
  '%s or unknown lifecycle blocks writes while permitting authorized reads',
  async (status) => {
    vi.mocked(fetchProjects).mockResolvedValue([{ id: 'p', status }] as any)
    page()
    await vm().refresh()
    await vm().open(row)
    vm().approved = true
    await vm().publish()
    await vm().remove()
    expect(api.publishKnowledge).not.toHaveBeenCalled()
    expect(api.deleteKnowledge).not.toHaveBeenCalled()
    vm().keywords = '灌溉'
    await vm().search()
    expect(api.searchKnowledge).toHaveBeenCalledOnce()
  }
)
it.each(['水'.repeat(5462), '恶意\u0000正文', '文字\ud800'])(
  'rejects byte/control/malformed Unicode before sending',
  async (content) => {
    page()
    await vm().refresh()
    await vm().open(row)
    vm().content = content
    vm().approved = true
    await vm().publish()
    expect(api.publishKnowledge).not.toHaveBeenCalled()
  }
)
it.each(['水', '灌溉\n 灌溉 ', '灌溉\n排水\n水位\n故障\n检查\n作物', '灌\t溉'])(
  'rejects invalid keyword sets',
  async (keys) => {
    page()
    vm().keywords = keys
    await vm().search()
    expect(api.searchKnowledge).not.toHaveBeenCalled()
  }
)
it('renders hostile HTML as literal data and distinguishes absent sources from no match', async () => {
  page()
  vm().keywords = '灌溉'
  const text = '<img src=x onerror=alert(1)>灌溉'
  vi.mocked(api.searchKnowledge).mockResolvedValue({
    ...matched,
    hits: [{ ...matched.hits[0], text, endCodePoint: Array.from(text).length }]
  } as any)
  await vm().search()
  expect(panel.find('img').exists()).toBe(false)
  expect(panel.text()).toContain(text)
  for (const state of ['NO_SOURCES', 'NO_MATCH']) {
    vi.mocked(api.searchKnowledge).mockResolvedValue({ ...matched, state, hits: [] } as any)
    await vm().search()
    expect(panel.text()).toContain(state === 'NO_SOURCES' ? '无批准资料' : '没有字面命中')
  }
})
it.each(['publish', 'remove'])(
  'unconfirmed %s is never retried and requires manual reconciliation',
  async (action) => {
    page()
    await vm().refresh()
    await vm().open(row)
    vm().approved = true
    vi.mocked(api.publishKnowledge).mockRejectedValue(new Error('network'))
    vi.mocked(api.deleteKnowledge).mockRejectedValue(new Error('network'))
    await vm()[action]()
    await vm()[action]()
    expect(vm().uncertain).toBe(true)
    expect(vm().content).toBe('')
    expect(vm().approved).toBe(false)
    expect(action === 'publish' ? api.publishKnowledge : api.deleteKnowledge).toHaveBeenCalledOnce()
    await vm().refresh()
    expect(vm().uncertain).toBe(false)
    expect(action === 'publish' ? api.publishKnowledge : api.deleteKnowledge).toHaveBeenCalledOnce()
  }
)
it.each(['project', 'user', 'tenant', 'role', 'epoch'])(
  'discards delayed search on changed %s',
  async (kind) => {
    page()
    vm().keywords = '灌溉'
    let resolve: (x: any) => void = () => {}
    vi.mocked(api.searchKnowledge).mockImplementation(() => new Promise((r) => (resolve = r)))
    const pending = vm().search()
    if (kind === 'project') state.user.info.currentProjectId = 'other'
    if (kind === 'user') state.user.info.userId = 'other'
    if (kind === 'tenant') state.user.info.tenantId = 'other'
    if (kind === 'role') state.user.info.roles = ['VIEWER']
    if (kind === 'epoch') invalidateIdentity()
    await nextTick()
    resolve(matched)
    await pending
    expect(vm().result).toBeUndefined()
  }
)
it.each([
  { ...matched, externalAllowed: true },
  { ...matched, projectId: 'other' },
  { ...matched, state: 'NO_MATCH' },
  { ...matched, hits: [{ ...matched.hits[0], endCodePoint: 5 }] }
])('rejects mismatched response rather than displaying no match', async (value) => {
  page()
  vm().keywords = '灌溉'
  vi.mocked(api.searchKnowledge).mockResolvedValue(value as any)
  await vm().search()
  expect(vm().result).toBeUndefined()
  expect(panel.text()).toContain('不能视为无命中')
})
it('changing approved draft clears approval and unknown sources cannot exceed capacity', async () => {
  page()
  await vm().refresh()
  await vm().open(row)
  vm().approved = true
  vm().content = '排水说明'
  await nextTick()
  expect(vm().approved).toBe(false)
  await vm().refresh()
  vm().newSource()
  vm().rows = Array.from({ length: 100 }, (_, i) => ({ ...row, sourceKey: 's' + i }))
  vm().sourceKey = 'new'
  vm().content = '灌溉说明'
  vm().approved = true
  await vm().publish()
  expect(api.publishKnowledge).not.toHaveBeenCalled()
})
it('discarding a draft on identity change clears plaintext and outstanding mutations', async () => {
  page()
  await vm().refresh()
  await vm().open(row)
  vm().approved = true
  let resolve: (x: any) => void = () => {}
  vi.mocked(api.publishKnowledge).mockImplementation(() => new Promise((r) => (resolve = r)))
  const pending = vm().publish()
  state.user.info.userId = 'other'
  await nextTick()
  resolve({ ...row, id: '019a0000-0000-7000-8000-000000000002', versionNumber: 2 })
  await pending
  expect(vm().rows).toEqual([])
  expect(vm().detail).toBeUndefined()
  expect(vm().content).toBe('')
  expect(vm().notice).toBe('')
})

it('pure identity epoch change immediately clears visible plaintext without requiring store field changes', async () => {
  page()
  await vm().refresh()
  await vm().open(row)
  expect(vm().content).toBe('灌溉说明')
  invalidateIdentity()
  await nextTick()
  expect(vm().detail).toBeUndefined()
  expect(vm().content).toBe('')
  expect(vm().rows).toEqual([])
  expect(vm().loaded).toBe(false)
})
