import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { defineComponent, h, nextTick, reactive } from 'vue'
import Panel from '@/components/agent/AnalysisRecoveryPanel.vue'
import { readAnalysisCallByKey } from '@/api/assistant-analysis'
import { analysisIntentStorage } from '@/features/agent/analysis-intent'
import { invalidateIdentity } from '@/utils/http/identity-scope'
import { memoryStorage } from './commercialStorageFixture'
const mocks = vi.hoisted(() => ({ user: {} as any }))
vi.mock('@/store/modules/user', () => ({ useUserStore: () => mocks.user }))
vi.mock('@/api/assistant-analysis', () => ({ readAnalysisCallByKey: vi.fn() }))
const scope = {
  accountId: '00000000-0000-4000-8000-000000000001',
  tenantId: '00000000-0000-4000-8000-000000000002',
  projectId: '00000000-0000-4000-8000-000000000003'
}
const request = {
  deviceId: '00000000-0000-4000-8000-000000000004',
  expectedModelVersionId: '00000000-0000-4000-8000-000000000005',
  propertyKeys: ['temperature'],
  template: 'STATUS_SUMMARY' as const
}
const mutex = { request: async <T>(_name: string, cb: () => Promise<T>) => cb() }
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
const checkbox = defineComponent({
  props: { modelValue: Boolean, disabled: Boolean },
  emits: ['update:modelValue'],
  setup:
    (props, { slots, emit }) =>
    () =>
      h('label', [
        h('input', {
          type: 'checkbox',
          checked: props.modelValue,
          disabled: props.disabled,
          onChange: (e: Event) => emit('update:modelValue', (e.target as HTMLInputElement).checked)
        }),
        slots.default?.()
      ])
})
let panel: ReturnType<typeof mount> | undefined
let storage: Storage
let key: string
const storageKey = `tc-agent-analysis-intent:v1:${scope.accountId}`
const state = () => (panel!.vm as any).$.setupState
const result = () => ({
  id: 'authorized-call-id',
  status: 'SUCCEEDED',
  finishedAt: new Date().toISOString(),
  deadline: new Date(Date.now() + 60_000).toISOString()
})
function page() {
  panel = mount(Panel, {
    props: { projectId: scope.projectId, deviceId: request.deviceId, parentBusy: false },
    global: {
      stubs: {
        ElButton: button,
        ElCheckbox: checkbox,
        ElAlert: { props: ['title'], template: '<p>{{title}}</p>' }
      }
    }
  })
}
beforeEach(async () => {
  vi.resetAllMocks()
  vi.useFakeTimers()
  vi.setSystemTime(new Date('2026-10-05T06:00:00Z'))
  storage = memoryStorage()
  vi.stubGlobal('localStorage', storage)
  vi.stubGlobal('navigator', { locks: mutex })
  mocks.user = reactive({
    isLogin: true,
    accessToken: 'synthetic',
    info: {
      userId: scope.accountId,
      tenantId: scope.tenantId,
      currentProjectId: scope.projectId,
      roles: ['OWNER']
    }
  })
  key = (await analysisIntentStorage(storage, mutex).create(scope, request, () => undefined)).key
  vi.mocked(readAnalysisCallByKey).mockResolvedValue(result() as never)
})
afterEach(() => {
  panel?.unmount()
  panel = undefined
  vi.unstubAllGlobals()
  vi.useRealTimers()
})
it('只手动查询原键，不展示本地请求信息，不自动清除或生成分析', async () => {
  page()
  expect(readAnalysisCallByKey).not.toHaveBeenCalled()
  expect(panel!.text()).not.toContain(key)
  expect(panel!.text()).not.toContain(request.deviceId)
  await state().lookup()
  expect(readAnalysisCallByKey).toHaveBeenCalledExactlyOnceWith(
    scope.projectId,
    key,
    expect.any(AbortSignal)
  )
  expect(panel!.text()).toContain('authorized-call-id')
  expect(panel!.text()).toContain('状态查询不返回正文')
  expect(storage.getItem(storageKey)).not.toBeNull()
  expect(panel!.find('input').exists()).toBe(true)
  await state().forget()
  expect(storage.getItem(storageKey)).not.toBeNull()
  storage.setItem('unrelated', 'keep')
  await panel!.find('input').setValue(true)
  await state().forget()
  expect(storage.getItem(storageKey)).toBeNull()
  expect(storage.getItem('unrelated')).toBe('keep')
  expect(panel!.text()).toContain('没有发起新的分析')
  expect(readAnalysisCallByKey).toHaveBeenCalledTimes(1)
})
it('未知状态在执行窗口内禁止处置，到期后手动核对才显示确认', async () => {
  vi.mocked(readAnalysisCallByKey).mockResolvedValue({ ...result(), status: 'UNKNOWN' } as never)
  page()
  await state().lookup()
  expect(panel!.find('input').exists()).toBe(false)
  vi.setSystemTime(Date.now() + 60_000)
  await state().lookup()
  expect(panel!.find('input').exists()).toBe(true)
  expect(panel!.text()).toContain('不能推断零费用')
})
it('无可确认状态时到期加执行窗口后只允许明确的本地处置', async () => {
  vi.setSystemTime(Date.now() + 86_460_000)
  page()
  expect(panel!.find('input').exists()).toBe(true)
  await state().lookup()
  expect(readAnalysisCallByKey).not.toHaveBeenCalled()
  expect(storage.getItem(storageKey)).not.toBeNull()
  await panel!.find('input').setValue(true)
  await state().forget()
  expect(storage.getItem(storageKey)).toBeNull()
})
it('404或权限错误保留原意图且不泄漏响应错误文本', async () => {
  vi.mocked(readAnalysisCallByKey).mockRejectedValue(new Error('private-response'))
  page()
  await state().lookup()
  expect(panel!.text()).toContain('未找到记录不代表未消费')
  expect(panel!.text()).not.toContain('private-response')
  expect(panel!.find('input').exists()).toBe(false)
  expect(storage.getItem(storageKey)).not.toBeNull()
})
it.each(['project', 'device'])('其他%s的本地意图不显示标识且零网络', async (change) => {
  const intent = JSON.parse(storage.getItem(storageKey)!)
  if (change === 'project') intent.projectId = request.expectedModelVersionId
  else intent.request.deviceId = request.expectedModelVersionId
  storage.setItem(storageKey, JSON.stringify(intent))
  page()
  expect(panel!.text()).toContain('其他范围')
  expect(panel!.text()).not.toContain(request.expectedModelVersionId)
  await state().lookup()
  expect(readAnalysisCallByKey).not.toHaveBeenCalled()
})
it.each([
  'user',
  'tenant',
  'project',
  'role',
  'token',
  'device',
  'epoch',
  'logout',
  'busy',
  'storage',
  'unmount'
])('%s变化取消并丢弃迟到状态，保留原意图', async (change) => {
  let finish!: (value: any) => void
  vi.mocked(readAnalysisCallByKey).mockImplementation(
    () =>
      new Promise((yes) => {
        finish = yes
      })
  )
  page()
  const run = state().lookup()
  await state().lookup()
  expect(readAnalysisCallByKey).toHaveBeenCalledTimes(1)
  const signal = vi.mocked(readAnalysisCallByKey).mock.calls[0][2]
  if (change === 'user') mocks.user.info.userId = 'other'
  if (change === 'tenant') mocks.user.info.tenantId = 'other'
  if (change === 'project') mocks.user.info.currentProjectId = 'other'
  if (change === 'role') mocks.user.info.roles = ['VIEWER']
  if (change === 'token') mocks.user.accessToken = 'changed'
  if (change === 'logout') mocks.user.isLogin = false
  if (change === 'epoch') invalidateIdentity()
  if (change === 'device') await panel!.setProps({ deviceId: 'other' })
  if (change === 'busy') await panel!.setProps({ parentBusy: true })
  if (change === 'storage') window.dispatchEvent(new StorageEvent('storage', { key: storageKey }))
  if (change === 'unmount') panel!.unmount()
  await nextTick()
  expect(signal.aborted).toBe(true)
  finish(result())
  await run
  expect(state().call).toBeUndefined()
  expect(storage.getItem(storageKey)).not.toBeNull()
})
it('无Web Locks时仍可查询，但处置失败保留原意图', async () => {
  vi.stubGlobal('navigator', {})
  page()
  await state().lookup()
  await panel!.find('input').setValue(true)
  await state().forget()
  expect(panel!.text()).toContain('处置未能确认')
  expect(storage.getItem(storageKey)).not.toBeNull()
})
it('同键请求被其他标签修改时人工确认不能删除新内容', async () => {
  page()
  await state().lookup()
  await panel!.find('input').setValue(true)
  const changed = JSON.parse(storage.getItem(storageKey)!)
  changed.request.template = 'ALARM_EXPLANATION'
  storage.setItem(storageKey, JSON.stringify(changed))
  await state().forget()
  expect(JSON.parse(storage.getItem(storageKey)!)).toEqual(changed)
  expect(panel!.text()).toContain('处置未能确认')
})
