import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, h, nextTick, reactive } from 'vue'
import Panel from '@/views/device/components/DeviceAgentAnalysis.vue'
import { readAnalysisAvailability, postAnalysisRun } from '@/api/assistant-analysis'
import { memoryStorage } from './commercialStorageFixture'
import { analysisIntentStorage } from '@/features/agent/analysis-intent'
import { invalidateIdentity } from '@/utils/http/identity-scope'
const mocks = vi.hoisted(() => ({ user: {} as any }))
vi.mock('@/store/modules/user', () => ({ useUserStore: () => mocks.user }))
vi.mock('@/api/assistant-analysis', () => ({
  readAnalysisAvailability: vi.fn(),
  postAnalysisRun: vi.fn()
}))
const button = defineComponent({
  props: { disabled: Boolean, loading: Boolean },
  emits: ['click'],
  setup:
    (props, { slots, emit }) =>
    () =>
      h(
        'button',
        { disabled: props.disabled || props.loading, onClick: () => emit('click') },
        slots.default?.()
      )
})
let panel: ReturnType<typeof mount>
const state = () => (panel.vm as any).$.setupState
function page(overrides: Record<string, unknown> = {}) {
  panel = mount(Panel, {
    props: {
      projectId: 'p',
      deviceId: 'd',
      modelVersionId: 'm',
      propertyKeys: ['zero'],
      parentBusy: false,
      ...overrides
    },
    global: {
      stubs: {
        ElButton: button,
        ElSelect: { template: '<div><slot/></div>' },
        ElOption: true,
        ElCheckbox: { props: ['modelValue', 'disabled'], template: '<label><slot/></label>' },
        ElAlert: { props: ['title'], template: '<p>{{title}}</p>' }
      }
    }
  })
}
function deferred() {
  let resolve!: (value: any) => void
  const promise = new Promise<any>((yes) => {
    resolve = yes
  })
  return { promise, resolve }
}
beforeEach(() => {
  vi.resetAllMocks()
  vi.stubGlobal('localStorage', memoryStorage())
  mocks.user = reactive({
    isLogin: true,
    accessToken: 'synthetic',
    info: { userId: 'u', tenantId: 't', currentProjectId: 'p', roles: ['OWNER'] }
  })
  vi.mocked(readAnalysisAvailability).mockResolvedValue({
    businessAvailable: false,
    reason: 'MODEL_ADMISSION_PENDING'
  })
})
const previousLocks = Object.getOwnPropertyDescriptor(navigator, 'locks')
afterEach(() => {
  panel?.unmount()
  window.localStorage.clear()
  if (previousLocks) Object.defineProperty(navigator, 'locks', previousLocks)
  else Reflect.deleteProperty(navigator, 'locks')
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})
describe('默认关闭的设备模型交互', () => {
  it.each(['OWNER', 'ADMIN', 'OPERATOR'])(
    '%s 仅手动读取，选择模板不发送，分析始终禁用',
    async (role) => {
      mocks.user.info.roles = [role]
      page()
      expect(readAnalysisAvailability).not.toHaveBeenCalled()
      state().template = 'ALARM_EXPLANATION'
      await nextTick()
      expect(readAnalysisAvailability).not.toHaveBeenCalled()
      const buttons = panel.findAll('button')
      expect(buttons.at(-1)!.attributes()).toHaveProperty('disabled')
      await buttons[0].trigger('click')
      await flushPromises()
      expect(readAnalysisAvailability).toHaveBeenCalledExactlyOnceWith('p', expect.any(AbortSignal))
      expect(panel.text()).toContain('条件尚未满足')
      expect(panel.text()).toContain('已选择 1 个属性')
      expect(buttons.at(-1)!.attributes()).toHaveProperty('disabled')
    }
  )
  it('未明确确认或未知状态不能直接调用提交，不发送POST', async () => {
    page()
    state().availability = { businessAvailable: true, reason: 'invented' }
    await state().submit()
    expect(postAnalysisRun).not.toHaveBeenCalled()
    expect(state().result).toBeUndefined()
    expect(readAnalysisAvailability).not.toHaveBeenCalled()
  })
  it('VIEWER不可查询模型状态，仍保留事实提示', async () => {
    mocks.user.info.roles = ['VIEWER']
    page()
    await state().refresh()
    expect(readAnalysisAvailability).not.toHaveBeenCalled()
    expect(panel.findAll('button')).toHaveLength(0)
    expect(panel.text()).toContain('查看者可继续读取事实证据')
  })
  it('服务关闭和读取失败分别说明，不自动重新请求', async () => {
    page()
    vi.mocked(readAnalysisAvailability).mockResolvedValue({
      businessAvailable: false,
      reason: 'INTERNAL_TRANSPORT_DISABLED'
    })
    await state().refresh()
    expect(panel.text()).toContain('服务尚未启用')
    vi.mocked(readAnalysisAvailability).mockRejectedValue(new Error('private-response'))
    await state().refresh()
    expect(panel.text()).toContain('状态读取失败')
    expect(panel.text()).not.toContain('private-response')
    expect(state().availability).toBeUndefined()
    expect(readAnalysisAvailability).toHaveBeenCalledTimes(2)
  })
  it.each([
    'user',
    'tenant',
    'project',
    'role',
    'token',
    'device',
    'model',
    'keys',
    'question',
    'logout',
    'epoch'
  ])('%s变化立即取消并丢弃旧身份/选择响应', async (change) => {
    page()
    const pending = deferred()
    vi.mocked(readAnalysisAvailability).mockReturnValue(pending.promise)
    const run = state().refresh()
    const signal = vi.mocked(readAnalysisAvailability).mock.calls[0][1]
    state().template = 'ALARM_EXPLANATION'
    state().result = { call: null, category: 'UNAVAILABLE', result: null }
    if (change === 'user') mocks.user.info.userId = 'other'
    if (change === 'tenant') mocks.user.info.tenantId = 'other'
    if (change === 'project') mocks.user.info.currentProjectId = 'other'
    if (change === 'role') mocks.user.info.roles = ['VIEWER']
    if (change === 'token') mocks.user.accessToken = 'new-token'
    if (change === 'logout') mocks.user.isLogin = false
    if (change === 'epoch') invalidateIdentity()
    if (change === 'device') await panel.setProps({ deviceId: 'other' })
    if (change === 'model') await panel.setProps({ modelVersionId: 'other' })
    if (change === 'keys') await panel.setProps({ propertyKeys: ['flag'] })
    if (change === 'question') state().template = 'STATUS_SUMMARY'
    await nextTick()
    expect(signal.aborted).toBe(true)
    pending.resolve({ businessAvailable: false, reason: 'MODEL_ADMISSION_PENDING' })
    await run
    expect(state().availability).toBeUndefined()
    expect(state().result).toBeUndefined()
    expect(state().template).toBe('STATUS_SUMMARY')
    expect(state().busy).toBe(false)
  })
  it('其他标签存储变化清除瞬时结果但不发新请求', async () => {
    page()
    state().result = { call: null, category: 'UNAVAILABLE', result: null }
    await nextTick()
    window.dispatchEvent(new StorageEvent('storage', { key: 'tc-agent-analysis-intent:v1:u' }))
    await nextTick()
    expect(state().result).toBeUndefined()
    expect(postAnalysisRun).not.toHaveBeenCalled()
  })
  it('父层读取期间和重复点击不新增请求，卸载取消未完成读取', async () => {
    page()
    await panel.setProps({ parentBusy: true })
    await state().refresh()
    expect(readAnalysisAvailability).not.toHaveBeenCalled()
    await panel.setProps({ parentBusy: false })
    const pending = deferred()
    vi.mocked(readAnalysisAvailability).mockReturnValue(pending.promise)
    const run = state().refresh()
    await state().refresh()
    expect(readAnalysisAvailability).toHaveBeenCalledTimes(1)
    const signal = vi.mocked(readAnalysisAvailability).mock.calls[0][1]
    panel.unmount()
    expect(signal.aborted).toBe(true)
    pending.resolve({ businessAvailable: false, reason: 'MODEL_ADMISSION_PENDING' })
    await run
  })
})

const scope = {
  accountId: '00000000-0000-4000-8000-000000000001',
  tenantId: '00000000-0000-4000-8000-000000000002',
  projectId: '00000000-0000-4000-8000-000000000003'
}
const deviceId = '00000000-0000-4000-8000-000000000004'
const modelVersionId = '00000000-0000-4000-8000-000000000005'
const approved = { businessAvailable: true, reason: 'REVIEWED_CONFIGURATION_AVAILABLE' } as const
const storageKey = `tc-agent-analysis-intent:v1:${scope.accountId}`
async function reviewedPage() {
  Object.assign(mocks.user.info, {
    userId: scope.accountId,
    tenantId: scope.tenantId,
    currentProjectId: scope.projectId
  })
  Object.defineProperty(navigator, 'locks', {
    configurable: true,
    value: { request: async (_name: string, callback: () => unknown) => callback() }
  })
  page({ projectId: scope.projectId, deviceId, modelVersionId, propertyKeys: ['temperature'] })
  vi.mocked(readAnalysisAvailability).mockResolvedValue(approved)
  await state().refresh()
  state().consented = true
  await nextTick()
}
describe('受审状态下的显式单次交互（合成响应）', () => {
  it('点击重新GET，原意图落盘后才POST一次，完成保留原键并撤销勾选', async () => {
    await reviewedPage()
    expect(postAnalysisRun).not.toHaveBeenCalled()
    expect(window.localStorage.getItem(storageKey)).toBeNull()
    vi.mocked(postAnalysisRun).mockImplementation(async (intent) => {
      expect(JSON.parse(window.localStorage.getItem(storageKey)!)).toEqual(intent)
      expect(readAnalysisAvailability).toHaveBeenCalledTimes(2)
      return { call: null, category: 'UNAVAILABLE', result: null }
    })
    await state().submit()
    expect(postAnalysisRun).toHaveBeenCalledTimes(1)
    expect(window.localStorage.getItem(storageKey)).not.toBeNull()
    expect(state().consented).toBe(false)
    expect(state().availability).toBeUndefined()
    expect(panel.text()).toContain('当前设备有待核对')
    await state().submit()
    expect(postAnalysisRun).toHaveBeenCalledTimes(1)
  })
  it('新鲜状态已关闭时不生成原键或POST', async () => {
    await reviewedPage()
    vi.mocked(readAnalysisAvailability).mockResolvedValue({
      businessAvailable: false,
      reason: 'MODEL_ADMISSION_PENDING'
    })
    await state().submit()
    expect(panel.text()).toContain('本次未提交分析')
    expect(postAnalysisRun).not.toHaveBeenCalled()
    expect(window.localStorage.getItem(storageKey)).toBeNull()
    expect(state().consented).toBe(false)
  })
  it('状态复核失败不保存意图或回显私有错误', async () => {
    await reviewedPage()
    vi.mocked(readAnalysisAvailability).mockRejectedValue(new Error('private-state-error'))
    await state().submit()
    expect(postAnalysisRun).not.toHaveBeenCalled()
    expect(window.localStorage.getItem(storageKey)).toBeNull()
    expect(panel.text()).not.toContain('private-state-error')
  })
  it('复核期间撤权丢弃迟到可用状态，不POST', async () => {
    await reviewedPage()
    const pending = deferred()
    vi.mocked(readAnalysisAvailability).mockReturnValue(pending.promise)
    const run = state().submit()
    mocks.user.info.roles = ['VIEWER']
    await nextTick()
    pending.resolve(approved)
    await run
    expect(postAnalysisRun).not.toHaveBeenCalled()
    expect(state().availability).toBeUndefined()
    expect(state().consented).toBe(false)
  })
  it('发送失败保留原意图，重复点击和再次确认不自动换键', async () => {
    await reviewedPage()
    vi.mocked(postAnalysisRun).mockRejectedValue(new Error('private-supplier-error'))
    await state().submit()
    const original = window.localStorage.getItem(storageKey)
    expect(original).not.toBeNull()
    await state().submit()
    await state().refresh()
    state().consented = true
    await state().submit()
    expect(postAnalysisRun).toHaveBeenCalledTimes(1)
    expect(window.localStorage.getItem(storageKey)).toBe(original)
    expect(panel.text()).not.toContain('private-supplier-error')
  })
  it('发送后切换身份取消等待但保留原键，迟到正文不能返回', async () => {
    await reviewedPage()
    const pending = deferred()
    vi.mocked(postAnalysisRun).mockReturnValue(pending.promise)
    const run = state().submit()
    await flushPromises()
    expect(postAnalysisRun).toHaveBeenCalledTimes(1)
    const signal = vi.mocked(postAnalysisRun).mock.calls[0][1]
    mocks.user.info.userId = 'other'
    await nextTick()
    expect(signal.aborted).toBe(true)
    pending.resolve({ call: null, category: 'UNAVAILABLE', result: null })
    await run
    expect(state().result).toBeUndefined()
    expect(window.localStorage.getItem(storageKey)).not.toBeNull()
  })
  it('已有其他设备原意图不新增POST', async () => {
    await reviewedPage()
    const store = analysisIntentStorage(window.localStorage, navigator.locks)
    await store.create(
      scope,
      {
        deviceId: '00000000-0000-4000-8000-000000000099',
        expectedModelVersionId: modelVersionId,
        propertyKeys: ['temperature'],
        template: 'STATUS_SUMMARY'
      },
      () => undefined
    )
    const original = window.localStorage.getItem(storageKey)
    await state().submit()
    expect(postAnalysisRun).not.toHaveBeenCalled()
    expect(window.localStorage.getItem(storageKey)).toBe(original)
  })
})

it('存储失败时页面不绕过原意图而发送', async () => {
  await reviewedPage()
  vi.spyOn(window.localStorage, 'setItem').mockImplementation(() => {
    throw new Error('private-quota')
  })
  await state().submit()
  expect(postAnalysisRun).not.toHaveBeenCalled()
  expect(window.localStorage.getItem(storageKey)).toBeNull()
  expect(panel.text()).not.toContain('private-quota')
})

it('首次成功仅在当前页面纯文本显示，重新查看状态清除正文', async () => {
  await reviewedPage()
  const now = new Date().toISOString()
  vi.mocked(postAnalysisRun).mockResolvedValue({
    category: 'SUCCEEDED',
    call: {
      id: '019c1234-5678-7890-8123-456789abcdef',
      status: 'SUCCEEDED',
      createdAt: now,
      dispatchedAt: now,
      finishedAt: now,
      deadline: now,
      expiresAt: now
    },
    result: {
      model: 'deepseek-flash',
      promptVersion: 'thingslink-agent-single-analysis-v1',
      summary: '<script>synthetic-summary</script>',
      findings: [{ kind: 'HYPOTHESIS', statement: 'synthetic-finding', evidenceIds: ['e-device'] }],
      limitations: ['synthetic-limit'],
      usage: {
        promptTokens: 10,
        completionTokens: 2,
        totalTokens: 12,
        cacheHitTokens: 0,
        cacheMissTokens: 10
      }
    }
  })
  await state().submit()
  expect(panel.text()).toContain('<script>synthetic-summary</script>')
  expect(panel.find('script').exists()).toBe(false)
  expect(window.localStorage.getItem(storageKey)).not.toContain('synthetic-summary')
  await state().refresh()
  expect(panel.text()).not.toContain('synthetic-summary')
  expect(state().consented).toBe(false)
  expect(postAnalysisRun).toHaveBeenCalledTimes(1)
})
