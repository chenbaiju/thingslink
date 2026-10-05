import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { reactive, nextTick } from 'vue'
import Panel from '@/views/device/components/DeviceEvidenceTools.vue'
import { readHistoryEvidence, readAlarmEvidence } from '@/api/assistant-tools'
import { invalidateIdentity } from '@/utils/http/identity-scope'

const state = vi.hoisted(() => ({ user: {} as any }))
vi.mock('@/store/modules/user', () => ({ useUserStore: () => state.user }))
vi.mock('@/api/assistant-tools', () => ({
  readHistoryEvidence: vi.fn(),
  readAlarmEvidence: vi.fn()
}))
const model = '11111111-2222-4333-8444-555555555555'
const oldModel = '11111111-2222-4333-8444-555555555556'
const start = '2026-10-04T00:00:00.000Z',
  end = '2026-10-04T01:00:00.000Z'
const history = (): any => ({
  projectId: 'p',
  deviceId: 'd',
  modelVersionId: model,
  propertyKey: 'n',
  requestedFrom: start,
  requestedTo: end,
  effectiveFrom: start,
  effectiveTo: end,
  retentionClipped: false,
  requestedGranularity: 'RAW',
  actualGranularity: 'RAW',
  aggregation: 'AVG',
  readAt: end,
  state: 'HAS_POINTS',
  points: [
    { at: start, value: 0, sampleCount: '1', sourceModelVersionId: model, source: 'CURRENT_MODEL' },
    {
      at: '2026-10-04T00:10:00Z',
      value: 9,
      sampleCount: '2',
      sourceModelVersionId: oldModel,
      source: 'HISTORICAL_MODEL'
    },
    {
      at: '2026-10-04T00:20:00Z',
      value: null,
      sampleCount: '1',
      sourceModelVersionId: null,
      source: 'SOURCE_UNKNOWN'
    }
  ]
})
const item = (n: number): any => ({
  id: `11111111-2222-4333-8444-${String(n).padStart(12, '0')}`,
  severity: 'WARNING',
  conditionState: 'PENDING',
  ackState: 'UNACKNOWLEDGED',
  firstConditionAt: start,
  activatedAt: null,
  clearedAt: null,
  acknowledgedAt: null,
  lastReceivedAt: end,
  version: 0
})
const alarms = (): any => ({
  projectId: 'p',
  deviceId: 'd',
  currentModelVersionId: model,
  collectedAt: end,
  sourceModelState: 'NOT_PROVIDED',
  items: [],
  nextCursor: null,
  hasMore: false,
  limit: 20
})
let panel: VueWrapper
const vm = () => (panel.vm as any).$?.setupState as any
const local = (iso: string) => {
  const date = new Date(iso)
  return new Date(date.getTime() - date.getTimezoneOffset() * 60000).toISOString().slice(0, 16)
}
async function page(role = 'VIEWER') {
  state.user.info.roles = [role]
  panel = mount(Panel, {
    props: { projectId: 'p', deviceId: 'd', modelVersionId: model, propertyKeys: ['n'] },
    global: {
      stubs: {
        ElButton: {
          props: ['disabled'],
          template: '<button :disabled="disabled"><slot /></button>'
        },
        ElAlert: { props: ['title'], template: '<p>{{title}}</p>' },
        ElTable: { props: ['data'], template: '<pre>{{JSON.stringify(data)}}</pre>' },
        ElTableColumn: true
      }
    }
  })
  vm().property = 'n'
  vm().from = local(start)
  vm().to = local(end)
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
  vi.mocked(readHistoryEvidence).mockResolvedValue(history())
  vi.mocked(readAlarmEvidence).mockResolvedValue(alarms())
})
afterEach(() => {
  panel?.unmount()
  vi.useRealTimers()
})

it.each(['OWNER', 'ADMIN', 'OPERATOR', 'VIEWER'])(
  '%s reads manually with UTC window and preserves zero, old source and unknown',
  async (role) => {
    await page(role)
    expect(readHistoryEvidence).not.toHaveBeenCalled()
    expect(readAlarmEvidence).not.toHaveBeenCalled()
    await panel.get('button').trigger('click')
    await flushPromises()
    expect(readHistoryEvidence).toHaveBeenCalledWith(
      'p',
      'd',
      model,
      'n',
      start,
      end,
      expect.any(AbortSignal)
    )
    expect(vm().history.points.map((p: any) => p.value)).toEqual([0, 9, null])
    expect(vm().history.points[1].sourceModelVersionId).toBe(oldModel)
    expect(panel.text()).toContain('单位及行业阈值未提供')
  }
)
it.each(['NO_POINTS', 'OUTSIDE_RETENTION'])(
  'empty %s explicitly remains missing evidence',
  async (state) => {
    await page()
    const value = history()
    value.state = state
    value.points = []
    if (state === 'OUTSIDE_RETENTION') {
      value.effectiveFrom = end
      value.effectiveTo = end
      value.retentionClipped = true
    }
    vi.mocked(readHistoryEvidence).mockResolvedValue(value)
    await vm().readHistory()
    expect(vm().history.state).toBe(state)
    expect(panel.text()).toContain('不代表正常')
  }
)
it('retention clipping and aggregate degradation remain explicit', async () => {
  await page()
  const value = history()
  value.effectiveFrom = '2026-10-04T00:10:00Z'
  value.retentionClipped = true
  value.actualGranularity = 'ONE_MINUTE'
  value.points = value.points.slice(1)
  vi.mocked(readHistoryEvidence).mockResolvedValue(value)
  await vm().readHistory()
  expect(panel.text()).toContain('已裁剪')
  expect(panel.text()).toContain('ONE_MINUTE')
})
it.each(['over-window', 'future', 'missing-key', 'reversed', 'bad-time'])(
  'rejects %s before HTTP',
  async (kind) => {
    await page()
    if (kind === 'over-window') vm().from = local('2026-10-02T23:00:00Z')
    if (kind === 'future') vm().to = local('2026-10-05T00:00:00Z')
    if (kind === 'missing-key') vm().property = 'other'
    if (kind === 'reversed') vm().from = local(end)
    if (kind === 'bad-time') vm().to = 'bad'
    await nextTick()
    await vm().readHistory()
    expect(readHistoryEvidence).not.toHaveBeenCalled()
    expect(vm().history).toBeUndefined()
  }
)
it.each([
  'wrong-project',
  'wrong-window',
  'too-many',
  'unknown-value',
  'sample-count',
  'source-mismatch',
  'empty-success',
  'point-outside'
])('rejects invalid history %s without retry', async (kind) => {
  await page()
  const value = history()
  if (kind === 'wrong-project') value.projectId = 'other'
  if (kind === 'wrong-window') value.requestedTo = start
  if (kind === 'too-many') value.points = Array(2001).fill(value.points[0])
  if (kind === 'unknown-value') value.points[2].value = 0
  if (kind === 'sample-count') value.points[0].sampleCount = '9223372036854775808'
  if (kind === 'source-mismatch') value.points[1].sourceModelVersionId = model
  if (kind === 'empty-success') value.points = []
  if (kind === 'point-outside') value.points[0].at = end
  vi.mocked(readHistoryEvidence).mockResolvedValue(value)
  await vm().readHistory()
  expect(vm().history).toBeUndefined()
  expect(readHistoryEvidence).toHaveBeenCalledOnce()
  expect(panel.text()).toContain('未取得有效回执')
})
it('manual paging replaces twenty items and carries signed cursor exactly', async () => {
  await page()
  const first = alarms()
  first.items = Array.from({ length: 20 }, (_, n) => item(n))
  first.hasMore = true
  first.nextCursor = 'signed+/=cursor'
  const second = alarms()
  second.items = [item(21)]
  vi.mocked(readAlarmEvidence).mockResolvedValueOnce(first).mockResolvedValueOnce(second)
  await vm().readAlarms(false)
  expect(readAlarmEvidence).toHaveBeenCalledOnce()
  await vm().readAlarms(true)
  expect(readAlarmEvidence).toHaveBeenLastCalledWith(
    'p',
    'd',
    model,
    expect.any(AbortSignal),
    'signed+/=cursor'
  )
  expect(vm().alarms.items.map((p: any) => p.id)).toEqual([item(21).id])
  expect(panel.text()).toContain('事故来源模型未提供')
})
it.each(['enum', 'cursor', 'duplicates', 'model', 'source', 'oversize', 'time'])(
  'rejects invalid alarm %s and cannot continue its cursor',
  async (kind) => {
    await page()
    const value = alarms()
    value.items = [item(1)]
    if (kind === 'enum') value.items[0].severity = '<img>'
    if (kind === 'cursor') {
      value.hasMore = true
      value.nextCursor = 'x'.repeat(2049)
    }
    if (kind === 'duplicates') value.items.push(item(1))
    if (kind === 'model') value.currentModelVersionId = oldModel
    if (kind === 'source') value.sourceModelState = 'CURRENT_MODEL'
    if (kind === 'oversize') value.items = Array.from({ length: 21 }, (_, n) => item(n))
    if (kind === 'time') value.items[0].firstConditionAt = 'bad'
    vi.mocked(readAlarmEvidence).mockResolvedValue(value)
    await vm().readAlarms(false)
    await vm().readAlarms(true)
    expect(vm().alarms).toBeUndefined()
    expect(readAlarmEvidence).toHaveBeenCalledOnce()
    expect(panel.find('img').exists()).toBe(false)
  }
)
it('failed next page removes old data and cursor; another click cannot retry automatically', async () => {
  await page()
  const first = alarms()
  first.items = Array.from({ length: 20 }, (_, n) => item(n))
  first.hasMore = true
  first.nextCursor = 'cursor'
  vi.mocked(readAlarmEvidence)
    .mockResolvedValueOnce(first)
    .mockRejectedValueOnce(new Error('source unavailable'))
  await vm().readAlarms(false)
  await vm().readAlarms(true)
  await vm().readAlarms(true)
  expect(readAlarmEvidence).toHaveBeenCalledTimes(2)
  expect(vm().alarms).toBeUndefined()
  expect(panel.text()).toContain('未将失败记作空页')
})
it('identity epoch clears displayed facts without a user-field change', async () => {
  await page()
  await vm().readHistory()
  await vm().readAlarms(false)
  invalidateIdentity()
  await nextTick()
  expect(vm().history).toBeUndefined()
  expect(vm().alarms).toBeUndefined()
  expect(vm().property).toBe('')
})
it.each(['project', 'user', 'role', 'model', 'epoch'])(
  'late %s history response is aborted and discarded',
  async (kind) => {
    await page()
    let resolve!: (v: any) => void
    vi.mocked(readHistoryEvidence).mockImplementation(
      () =>
        new Promise((r) => {
          resolve = r
        })
    )
    const pending = vm().readHistory()
    const signal = vi.mocked(readHistoryEvidence).mock.calls[0][6]
    if (kind === 'project') state.user.info.currentProjectId = 'other'
    if (kind === 'user') state.user.info.userId = 'other'
    if (kind === 'role') state.user.info.roles = []
    if (kind === 'model') await panel.setProps({ modelVersionId: oldModel })
    if (kind === 'epoch') invalidateIdentity()
    await nextTick()
    expect(signal.aborted).toBe(true)
    resolve(history())
    await pending
    expect(vm().history).toBeUndefined()
    expect(readHistoryEvidence).toHaveBeenCalledOnce()
  }
)
it('input change clears prior history while keeping an independently read alarm page', async () => {
  await page()
  await vm().readHistory()
  await vm().readAlarms(false)
  vm().to = local('2026-10-04T02:00:00Z')
  await nextTick()
  expect(vm().history).toBeUndefined()
  expect(vm().alarms).toBeDefined()
})

it('summarizes each exact source version separately without treating unknown as zero', async () => {
  await page()
  const value = history()
  const anotherModel = '11111111-2222-4333-8444-555555555557'
  value.points.push(
    { ...value.points[0], at: '2026-10-04T00:30:00Z', value: -3, sampleCount: '2' },
    {
      ...value.points[1],
      at: '2026-10-04T00:40:00Z',
      value: 100,
      sourceModelVersionId: anotherModel
    }
  )
  vi.mocked(readHistoryEvidence).mockResolvedValue(value)
  await vm().readHistory()
  const groups = panel.findAll('[data-testid="history-source-group"]').map((row) => row.text())
  expect(groups).toHaveLength(4)
  expect(groups[0]).toContain('当前模型来源')
  expect(groups[0]).toContain('返回 2 个点；样本计数合计 3')
  expect(groups[0]).toContain('原始点值范围： -3 至 0')
  expect(groups[1]).toContain(oldModel)
  expect(groups[1]).toContain('9 至 9')
  expect(groups[2]).toContain('来源未知；模型 未提供')
  expect(groups[2]).toContain('数值未提供，不计算范围')
  expect(groups[2]).not.toContain('至 0')
  expect(groups[3]).toContain(anotherModel)
  expect(groups[3]).toContain('100 至 100')
  expect(readHistoryEvidence).toHaveBeenCalledOnce()
  expect(readAlarmEvidence).not.toHaveBeenCalled()
})

it('sums exact sample-count strings beyond both safe integer and signed long ranges', async () => {
  await page()
  const value = history()
  value.points = [
    { ...value.points[0], sampleCount: '9223372036854775807' },
    { ...value.points[0], at: '2026-10-04T00:30:00Z', sampleCount: '9223372036854775807' }
  ]
  vi.mocked(readHistoryEvidence).mockResolvedValue(value)
  await vm().readHistory()
  expect(panel.get('[data-testid="history-source-group"]').text()).toContain(
    '样本计数合计 18446744073709551614'
  )
})

it('labels downsampled value ranges as returned bucket means, not raw extremes or a window mean', async () => {
  await page()
  const value = history()
  value.actualGranularity = 'ONE_MINUTE'
  value.points = [
    { ...value.points[0], sampleCount: '1000' },
    { ...value.points[0], at: '2026-10-04T00:30:00Z', value: 10, sampleCount: '1' }
  ]
  vi.mocked(readHistoryEvidence).mockResolvedValue(value)
  await vm().readHistory()
  const overview = panel.get('[data-testid="history-evidence-overview"]').text()
  expect(overview).toContain('样本计数合计 1001')
  expect(overview).toContain('桶均值范围： 0 至 10')
  expect(overview).toContain('不代表原始采样的最小值或最大值')
  expect(overview).toContain('不计算完整率、异常或趋势')
  expect(overview).not.toContain('原始点值范围')
})

it.each(['identity', 'input', 'failure'])(
  'clears the derived summary on %s without retaining or automatically refreshing facts',
  async (kind) => {
    await page()
    await vm().readHistory()
    expect(panel.find('[data-testid="history-evidence-overview"]').exists()).toBe(true)
    if (kind === 'identity') invalidateIdentity()
    if (kind === 'input') vm().property = ''
    if (kind === 'failure') {
      vi.mocked(readHistoryEvidence).mockRejectedValueOnce(new Error('PRIVATE_SOURCE_FAILURE'))
      await vm().readHistory()
    }
    await nextTick()
    expect(panel.find('[data-testid="history-evidence-overview"]').exists()).toBe(false)
    expect(readHistoryEvidence).toHaveBeenCalledTimes(kind === 'failure' ? 2 : 1)
    expect(panel.text()).not.toContain('PRIVATE_SOURCE_FAILURE')
  }
)
