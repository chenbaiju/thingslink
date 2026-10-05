import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { reactive, nextTick } from 'vue'
import { createHash } from 'node:crypto'
import Panel from '@/views/device/components/DevicePersonalRecords.vue'
import { generatePersonalFactReport, listPersonalRecords } from '@/api/assistant-records'
import { invalidateIdentity } from '@/utils/http/identity-scope'

const state = vi.hoisted(() => ({ user: {} as any }))
vi.mock('@/store/modules/user', () => ({ useUserStore: () => state.user }))
vi.mock('@/api/assistant-records', () => ({
  savePersonalRecord: vi.fn(),
  listPersonalRecords: vi.fn(),
  readPersonalRecord: vi.fn(),
  deletePersonalRecord: vi.fn(),
  generatePersonalFactReport: vi.fn()
}))
const row = {
  id: '11111111-2222-4333-8444-555555555555',
  deviceId: 'd',
  modelVersionId: 'old-model',
  createdAt: '2026-10-04T00:00:00Z',
  expiresAt: '2026-11-03T00:00:00Z',
  contentSha256: 'a'.repeat(64)
}
const markdown = '# 历史事实\n\n<img src=x onerror=alert(1)>\n值 0 / false；未做模型诊断。\n'
const digest = (text: string) => createHash('sha256').update(text, 'utf8').digest('hex')
const result = () => ({
  schemaVersion: 1,
  mode: 'FACTS_ONLY',
  sourceRecord: { ...row },
  contentSha256: digest(markdown),
  markdown
})
const originalURL = globalThis.URL
const urlCreate = vi.fn(() => 'blob:report-test'),
  urlRevoke = vi.fn(),
  hash = vi.fn()
let panel: VueWrapper, clicked: ReturnType<typeof vi.spyOn>
const vm = () => (panel.vm as any).$?.setupState as any
async function page() {
  panel = mount(Panel, {
    props: { projectId: 'p', deviceId: 'd', modelVersionId: 'new-model', propertyKeys: ['n'] },
    global: {
      stubs: {
        ElButton: { template: '<button><slot /></button>' },
        ElAlert: { props: ['title'], template: '<p>{{ title }}</p>' }
      }
    }
  })
  await vm().refresh()
}
beforeEach(() => {
  vi.resetAllMocks()
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(new Date('2026-10-04T12:00:00Z'))
  state.user = reactive({
    isLogin: true,
    info: { userId: 'u', tenantId: 't', currentProjectId: 'p', roles: ['VIEWER'] }
  })
  vi.mocked(listPersonalRecords).mockResolvedValue([row])
  vi.mocked(generatePersonalFactReport).mockResolvedValue(result() as any)
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
  '%s generates only manually and preserves historical model',
  async (role) => {
    state.user.info.roles = [role]
    await page()
    expect(generatePersonalFactReport).not.toHaveBeenCalled()
    await vm().generate(row.id)
    expect(generatePersonalFactReport).toHaveBeenCalledWith('p', row.id, expect.any(AbortSignal))
    expect(panel.get('[data-testid="personal-fact-report"] pre').text()).toBe(markdown.trim())
    expect(panel.findAll('img')).toHaveLength(0)
    expect(vm().report.sourceRecord.modelVersionId).toBe('old-model')
    expect(urlCreate).not.toHaveBeenCalled()
  }
)
it('download performs another server authorization and releases its temporary URL', async () => {
  await page()
  await vm().generate(row.id)
  await vm().generate(row.id, true)
  expect(generatePersonalFactReport).toHaveBeenCalledTimes(2)
  expect(urlCreate).toHaveBeenCalledOnce()
  expect(clicked).toHaveBeenCalledOnce()
  const link = clicked.mock.instances[0] as HTMLAnchorElement
  expect(link.download).toBe(`personal-fact-report-${row.id}.md`)
  expect(link.href).toBe('blob:report-test')
  await new Promise((resolve) => setTimeout(resolve, 5))
  expect(urlRevoke).toHaveBeenCalledWith('blob:report-test')
  expect(panel.text()).toContain('已发起浏览器下载')
})
it('download cannot reuse a previously displayed report after server rejection', async () => {
  await page()
  await vm().generate(row.id)
  vi.mocked(generatePersonalFactReport).mockRejectedValue(new Error('revoked'))
  await vm().generate(row.id, true)
  expect(generatePersonalFactReport).toHaveBeenCalledTimes(2)
  expect(vm().report).toBeUndefined()
  expect(urlCreate).not.toHaveBeenCalled()
  expect(panel.text()).toContain('未显示或下载')
})
it.each(['mode', 'hash', 'source', 'size', 'expired'])(
  'rejects invalid %s receipts without a download or retry',
  async (kind) => {
    await page()
    const broken = result() as any
    if (kind === 'mode') broken.mode = 'DIAGNOSIS'
    if (kind === 'hash') broken.markdown += 'tampered'
    if (kind === 'source') broken.sourceRecord.contentSha256 = 'b'.repeat(64)
    if (kind === 'size') broken.markdown = '中'.repeat(45000)
    if (kind === 'expired') {
      broken.sourceRecord.expiresAt = row.createdAt
      vm().rows[0] = { ...row, expiresAt: row.createdAt }
    }
    vi.mocked(generatePersonalFactReport).mockResolvedValue(broken)
    await vm().generate(row.id, true)
    expect(vm().report).toBeUndefined()
    expect(generatePersonalFactReport).toHaveBeenCalledOnce()
    expect(urlCreate).not.toHaveBeenCalled()
    expect(clicked).not.toHaveBeenCalled()
  }
)
it('actual identity epoch invalidation clears already displayed plaintext', async () => {
  await page()
  await vm().generate(row.id)
  expect(vm().report).toBeDefined()
  expect(panel.text()).toContain('onerror=alert(1)')
  invalidateIdentity()
  await nextTick()
  expect(vm().report).toBeUndefined()
  expect(vm().rows).toEqual([])
  expect(panel.text()).not.toContain('onerror=alert(1)')
})
it('drops a late network receipt after a project switch', async () => {
  await page()
  let resolve: (value: any) => void = () => {}
  vi.mocked(generatePersonalFactReport).mockImplementation(
    () =>
      new Promise((r) => {
        resolve = r
      })
  )
  const pending = vm().generate(row.id, true)
  state.user.info.currentProjectId = 'other'
  await nextTick()
  expect(vi.mocked(generatePersonalFactReport).mock.calls[0][2].aborted).toBe(true)
  resolve(result())
  await pending
  expect(vm().report).toBeUndefined()
  expect(urlCreate).not.toHaveBeenCalled()
})
it('drops a download even when account changes during asynchronous digest calculation', async () => {
  await page()
  let resolve: (value: ArrayBuffer) => void = () => {}
  hash.mockImplementation(
    () =>
      new Promise((r) => {
        resolve = r
      })
  )
  const pending = vm().generate(row.id, true)
  await flushPromises()
  state.user.info.userId = 'other'
  await nextTick()
  resolve(Uint8Array.from(createHash('sha256').update(markdown).digest()).buffer)
  await pending
  expect(vm().report).toBeUndefined()
  expect(urlCreate).not.toHaveBeenCalled()
  expect(clicked).not.toHaveBeenCalled()
})
it('expiry during digest calculation invalidates the otherwise valid report', async () => {
  await page()
  let resolve: (value: ArrayBuffer) => void = () => {}
  hash.mockImplementation(
    () =>
      new Promise((r) => {
        resolve = r
      })
  )
  const pending = vm().generate(row.id, true)
  await flushPromises()
  vi.setSystemTime(new Date('2026-11-04T00:00:00Z'))
  resolve(Uint8Array.from(createHash('sha256').update(markdown).digest()).buffer)
  await pending
  expect(vm().report).toBeUndefined()
  expect(urlCreate).not.toHaveBeenCalled()
})
