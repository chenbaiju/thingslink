import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { reactive } from 'vue'
import Panel from '@/views/ota/jobs/OtaRollbackPreflightPanel.vue'
import { fetchOtaRollbackPreflight } from '@/api/ota'
import { fetchProjects } from '@/api/project'
import { HttpError } from '@/utils/http/error'
import { invalidateIdentity } from '@/utils/http/identity-scope'
const state = vi.hoisted(() => ({ user: {} as any }))
vi.mock('@/store/modules/user', () => ({ useUserStore: () => state.user }))
vi.mock('@/api/ota', () => ({ fetchOtaRollbackPreflight: vi.fn() }))
vi.mock('@/api/project', () => ({ fetchProjects: vi.fn() }))
const projectId = '11111111-1111-4111-8111-111111111111',
  campaignId = '22222222-2222-4222-8222-222222222222',
  jobId = '33333333-3333-4333-8333-333333333333',
  otherId = '44444444-4444-4444-8444-444444444444'
const snapshot = {
  queryId: projectId,
  reportId: campaignId,
  observedDisposition: 'PREPARABLE',
  observedReason: 'ATOMIC_FENCE_STILL_REQUIRED',
  currentDisposition: 'INELIGIBLE',
  currentReason: 'QUERY_NOT_CURRENT_OR_FRESH',
  observedAt: '2026-10-06T20:00:00Z',
  queryExpiresAt: '2026-10-06T20:02:00Z',
  checkedAt: '2026-10-06T20:01:00Z',
  operationRevision: '1',
  sourceSlot: 'A',
  targetSlot: 'B',
  executionAuthorized: false,
  requiresAtomicCommitFence: true
}
let wrapper: VueWrapper | undefined
const find = (id: string) => wrapper!.find(`[data-testid="${id}"]`)
function deferred() {
  let resolve!: (v: any) => void
  const promise = new Promise<any>((yes) => {
    resolve = yes
  })
  return { promise, resolve }
}
function render(
  props: Partial<{ active: boolean; projectId: string; campaignId: string; jobId: string }> = {}
) {
  wrapper = mount(Panel, {
    props: { active: true, projectId, campaignId, jobId, ...props },
    global: {
      stubs: {
        ElDivider: { template: '<h3><slot/></h3>' },
        ElButton: {
          props: ['disabled'],
          template: '<button :disabled="disabled"><slot/></button>'
        },
        ElDescriptions: { template: '<div><slot/></div>' },
        ElDescriptionsItem: { props: ['label'], template: '<p>{{label}}: <slot/></p>' },
        ElAlert: { props: ['title'], template: '<p>{{title}}</p>' }
      }
    }
  })
}
async function refresh() {
  await find('ota-rollback-preflight-refresh').trigger('click')
  await flushPromises()
}
beforeEach(() => {
  vi.resetAllMocks()
  vi.useFakeTimers({ toFake: ['Date', 'setTimeout', 'clearTimeout'] })
  vi.setSystemTime(new Date('2026-10-06T20:01:00Z'))
  state.user = reactive({
    info: {
      currentProjectId: projectId,
      userId: 'owner',
      tenantId: 'tenant',
      roles: ['OWNER'],
      buttons: ['ota:read']
    }
  })
  vi.mocked(fetchProjects).mockResolvedValue([{ id: projectId, status: 'ACTIVE', myRole: 'OWNER' }])
  vi.mocked(fetchOtaRollbackPreflight).mockResolvedValue({ ...snapshot } as any)
})
afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
  vi.restoreAllMocks()
  vi.useRealTimers()
})
it('分开显示历史与当前分类/稳定原因/完整UTC字段和安全恒量，只有刷新读取按钮', async () => {
  render()
  await flushPromises()
  expect(fetchOtaRollbackPreflight).toHaveBeenCalledExactlyOnceWith(projectId, campaignId, jobId)
  expect(find('ota-rollback-observed-disposition').text()).toBe('可准备')
  expect(find('ota-rollback-current-disposition').text()).toBe('不可准备')
  expect(find('ota-rollback-observed-reason').text()).toContain('ATOMIC_FENCE_STILL_REQUIRED')
  expect(find('ota-rollback-current-reason').text()).toContain('QUERY_NOT_CURRENT_OR_FRESH')
  for (const id of ['observed-at', 'checked-at', 'query-expiry'])
    expect(find(`ota-rollback-${id}`).text()).toMatch(/Z$/)
  expect(find('ota-rollback-execution-authorized').text()).toContain('false')
  expect(find('ota-rollback-atomic-fence').text()).toBe('true')
  expect(find('ota-rollback-preflight-stale').exists()).toBe(true)
  expect(wrapper!.findAll('button').map((item) => item.text())).toEqual(['刷新回退准备观察'])
})
it('协议UNKNOWN属于已认证结果，不是无报告空态', async () => {
  vi.mocked(fetchOtaRollbackPreflight).mockResolvedValue({
    ...snapshot,
    observedDisposition: 'UNKNOWN',
    currentDisposition: 'UNKNOWN',
    observedReason: 'WRITER_STATE_UNKNOWN',
    currentReason: 'WRITER_STATE_UNKNOWN'
  } as any)
  render()
  await flushPromises()
  expect(find('ota-rollback-preflight-result').exists()).toBe(true)
  expect(find('ota-rollback-current-disposition').text()).toBe('未知')
  expect(find('ota-rollback-preflight-no-report').exists()).toBe(false)
})
it('404/70044仅为无可读报告；读取不自动轮询，刷新仅再GET一次', async () => {
  vi.mocked(fetchOtaRollbackPreflight).mockRejectedValue(new HttpError('PRIVATE_MESSAGE', 70044))
  render()
  await flushPromises()
  expect(find('ota-rollback-preflight-no-report').text()).toContain('不等于设备报告')
  expect(find('ota-rollback-preflight-result').exists()).toBe(false)
  await vi.advanceTimersByTimeAsync(180_000)
  expect(fetchOtaRollbackPreflight).toHaveBeenCalledOnce()
  await refresh()
  expect(fetchOtaRollbackPreflight).toHaveBeenCalledTimes(2)
})
it.each([70042, 70043, 401, 403, 50017, 500])('%s失败固定展示并清除旧观察', async (code) => {
  render()
  await flushPromises()
  vi.mocked(fetchOtaRollbackPreflight).mockRejectedValue(
    new HttpError('PRIVATE_MESSAGE', code, { details: ['RAW_EVIDENCE'] })
  )
  await refresh()
  expect(find('ota-rollback-preflight-result').exists()).toBe(false)
  expect(find('ota-rollback-preflight-error').exists()).toBe(true)
  expect(wrapper!.text()).not.toContain('PRIVATE_MESSAGE')
  expect(wrapper!.text()).not.toContain('RAW_EVIDENCE')
  expect(find('ota-rollback-preflight-no-report').exists()).toBe(false)
})
it.each([
  { executionAuthorized: true },
  { requiresAtomicCommitFence: false },
  { credential: 'SECRET' },
  { checkedAt: null },
  { targetSlot: 'A' }
])('闭集坏响应不冒充安全资格%j', async (change) => {
  vi.mocked(fetchOtaRollbackPreflight).mockResolvedValue({ ...snapshot, ...change } as any)
  render()
  await flushPromises()
  expect(find('ota-rollback-preflight-result').exists()).toBe(false)
  expect(find('ota-rollback-preflight-error').text()).toContain('公开只读合同')
  expect(wrapper!.text()).not.toContain('SECRET')
})
it.each(['ARCHIVED', 'DELETING', 'DELETED', 'VIEWER'])(
  '当前项目%s不读取观察',
  async (condition) => {
    vi.mocked(fetchProjects).mockResolvedValue([
      {
        id: projectId,
        status: condition === 'VIEWER' ? 'ACTIVE' : condition,
        myRole: condition === 'VIEWER' ? condition : 'OWNER'
      }
    ])
    render()
    await flushPromises()
    expect(fetchOtaRollbackPreflight).not.toHaveBeenCalled()
    expect(find('ota-rollback-preflight-error').exists()).toBe(true)
  }
)
it.each(['inactive', 'role', 'buttons', 'user', 'tenant', 'project', 'job'])(
  '首次%s条件不符不读取',
  async (condition) => {
    const props: any = {}
    if (condition === 'inactive') props.active = false
    if (condition === 'role') state.user.info.roles = ['VIEWER']
    if (condition === 'buttons') state.user.info.buttons = []
    if (condition === 'user') state.user.info.userId = ''
    if (condition === 'tenant') state.user.info.tenantId = ''
    if (condition === 'project') state.user.info.currentProjectId = otherId
    if (condition === 'job') props.jobId = 'bad'
    render(props)
    await flushPromises()
    expect(fetchProjects).not.toHaveBeenCalled()
    expect(fetchOtaRollbackPreflight).not.toHaveBeenCalled()
  }
)
it.each(['close', 'project', 'tenant', 'user', 'epoch', 'roles', 'buttons', 'job', 'campaign'])(
  '%s改变丢弃在途旧观察',
  async (change) => {
    const old = deferred()
    vi.mocked(fetchOtaRollbackPreflight).mockReturnValueOnce(old.promise)
    render()
    await flushPromises()
    vi.mocked(fetchOtaRollbackPreflight).mockRejectedValue(new HttpError('missing', 70044))
    if (change === 'close') await wrapper!.setProps({ active: false })
    else if (change === 'project') state.user.info.currentProjectId = otherId
    else if (change === 'tenant') state.user.info.tenantId = 'tenant-b'
    else if (change === 'user') state.user.info.userId = 'user-b'
    else if (change === 'epoch') invalidateIdentity()
    else if (change === 'roles') state.user.info.roles = ['VIEWER']
    else if (change === 'buttons') state.user.info.buttons = []
    else if (change === 'job') await wrapper!.setProps({ jobId: otherId })
    else await wrapper!.setProps({ campaignId: otherId })
    await flushPromises()
    old.resolve({ ...snapshot })
    await flushPromises()
    expect(find('ota-rollback-preflight-result').exists()).toBe(false)
  }
)
it('关闭后重开清理旧报告，旧资格读取也不能驱动新代次GET', async () => {
  const old = deferred()
  vi.mocked(fetchProjects).mockReturnValueOnce(old.promise)
  render()
  await flushPromises()
  await wrapper!.setProps({ active: false })
  vi.mocked(fetchOtaRollbackPreflight).mockRejectedValue(new HttpError('missing', 70044))
  await wrapper!.setProps({ active: true })
  await flushPromises()
  old.resolve([{ id: projectId, status: 'ACTIVE', myRole: 'OWNER' }])
  await flushPromises()
  expect(fetchOtaRollbackPreflight).toHaveBeenCalledOnce()
  expect(find('ota-rollback-preflight-no-report').exists()).toBe(true)
})
it('在途刷新不并发读取；截止只更新时效提示，不重分类也不自动GET', async () => {
  vi.mocked(fetchOtaRollbackPreflight).mockResolvedValue({
    ...snapshot,
    currentDisposition: 'PREPARABLE',
    currentReason: 'ATOMIC_FENCE_STILL_REQUIRED'
  } as any)
  const old = deferred()
  vi.mocked(fetchProjects).mockReturnValueOnce(old.promise)
  render()
  await flushPromises()
  await refresh()
  expect(fetchProjects).toHaveBeenCalledOnce()
  old.resolve([{ id: projectId, status: 'ACTIVE', myRole: 'OWNER' }])
  await flushPromises()
  expect(find('ota-rollback-preflight-stale').exists()).toBe(false)
  await vi.advanceTimersByTimeAsync(60_000)
  expect(find('ota-rollback-preflight-stale').exists()).toBe(true)
  expect(find('ota-rollback-current-disposition').text()).toBe('可准备')
  expect(fetchOtaRollbackPreflight).toHaveBeenCalledOnce()
  await wrapper!.setProps({ active: false })
  expect(vi.getTimerCount()).toBe(0)
})
it('浏览器快钟时明确提示本地截止来源，保持服务端PREPARABLE快照及重验时间', async () => {
  vi.setSystemTime(new Date('2026-10-06T20:03:00Z'))
  vi.mocked(fetchOtaRollbackPreflight).mockResolvedValue({
    ...snapshot,
    currentDisposition: 'PREPARABLE',
    currentReason: 'ATOMIC_FENCE_STILL_REQUIRED'
  } as any)
  render()
  await flushPromises()
  expect(find('ota-rollback-preflight-stale').text()).toContain('按当前浏览器时间已到原截止')
  expect(find('ota-rollback-current-disposition').text()).toBe('可准备')
  expect(find('ota-rollback-checked-at').text()).toBe('2026-10-06T20:01:00Z')
  expect(wrapper!.text()).toContain('受控原子提交互斥要求')
  expect(wrapper!.text()).not.toContain('未来原子')
})
