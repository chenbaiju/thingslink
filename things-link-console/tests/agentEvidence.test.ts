import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, h, nextTick, reactive } from 'vue'
import Panel from '@/views/device/components/DeviceAgentEvidence.vue'
import { readDeviceEvidence } from '@/api/assistant-evidence'
import { fetchBindingMetadata } from '@/api/dashboard-binding'
const mocks = vi.hoisted(() => ({ user: {} as any, close: vi.fn(), scope: {} }))
vi.mock('@/store/modules/user', () => ({ useUserStore: () => mocks.user }))
vi.mock('@/api/assistant-evidence', () => ({ readDeviceEvidence: vi.fn() }))
vi.mock('@/api/dashboard-binding', () => ({
  fetchBindingMetadata: vi.fn(),
  createDesignerReadScope: () => ({ close: mocks.close })
}))
const version = '019c1234-5678-7890-8123-456789abcdef'
const stamp = '2026-10-04T00:00:00.123456789Z'
const metadata = {
  model: { versionId: version },
  properties: ['zero', 'flag', 'text', 'missing', 'unknown', 'mismatch'].map((key) => ({ key }))
}
const property = (key = 'zero', value: unknown = 0) => ({
  key,
  value,
  occurredAt: stamp,
  reportedRevision: '0',
  sourceModelVersionId: version,
  readAt: stamp,
  availability: 'PRESENT'
})
const evidence = () => ({
  schemaVersion: 1,
  projectId: 'p',
  deviceId: 'd',
  modelVersionId: version,
  collectionStartedAt: stamp,
  collectionFinishedAt: stamp,
  device: { status: 'ONLINE', lastOnlineAt: null, readAt: stamp },
  alarmSummary: { state: 'NORMAL', observedAt: stamp },
  properties: [property()]
})
const button = defineComponent({
  props: ['disabled', 'loading'],
  emits: ['click'],
  setup:
    (p, { slots, emit }) =>
    () =>
      h(
        'button',
        { disabled: p.disabled || p.loading, onClick: () => emit('click') },
        slots.default?.()
      )
})
const slots = defineComponent({
  setup:
    (_, { slots }) =>
    () =>
      h('div', slots.default?.())
})
const table = defineComponent({
  props: ['data'],
  setup: (p) => () => h('section', JSON.stringify(p.data))
})
let panel: ReturnType<typeof mount>
const vm = () => (panel.vm as any).$.setupState
function deferred() {
  let resolve!: (value: any) => void, reject!: (value: any) => void
  const promise = new Promise<any>((yes, no) => {
    resolve = yes
    reject = no
  })
  return { promise, resolve, reject }
}
function page() {
  panel = mount(Panel, {
    props: { projectId: 'p', deviceId: 'd' },
    global: {
      stubs: {
        ElButton: button,
        ElSelect: slots,
        ElOption: true,
        ElTable: table,
        ElTableColumn: true,
        ElDescriptions: slots,
        ElDescriptionsItem: slots,
        ElAlert: { props: ['title'], template: '<p>{{title}}</p>' }
      }
    }
  })
}
async function ready(keys = ['zero']) {
  await vm().loadCatalog()
  vm().selected = keys
  await nextTick()
}
beforeEach(() => {
  vi.resetAllMocks()
  mocks.user = reactive({
    isLogin: true,
    accessToken: 'synthetic-token',
    info: { userId: 'u', tenantId: 't', currentProjectId: 'p', roles: ['OWNER'] }
  })
  vi.mocked(fetchBindingMetadata).mockResolvedValue(metadata as any)
  vi.mocked(readDeviceEvidence).mockResolvedValue(evidence() as any)
})
afterEach(() => panel?.unmount())
describe('受权只读设备证据', () => {
  it.each(['OWNER', 'ADMIN', 'OPERATOR', 'VIEWER'])('%s 手动读取且不自动分析', async (role) => {
    mocks.user.info.roles = [role]
    page()
    expect(fetchBindingMetadata).not.toHaveBeenCalled()
    expect(readDeviceEvidence).not.toHaveBeenCalled()
    expect(panel.text()).toContain('读取证据不会调用外部模型')
    await ready()
    await vm().read()
    expect(readDeviceEvidence).toHaveBeenCalledExactlyOnceWith(
      'p',
      'd',
      version,
      ['zero'],
      expect.any(AbortSignal)
    )
    expect(vm().snapshot).toEqual(evidence())
    expect(panel.text()).toContain('在线')
    expect(panel.text()).toContain(stamp)
  })
  it('零、false和转义文本保留；缺失和未知来源不当作正常值', async () => {
    page()
    const result = evidence()
    result.properties = [
      property(),
      property('flag', false),
      property('text', '<img src=x onerror=alert(1)>'),
      {
        ...property('missing', null),
        availability: 'MISSING',
        sourceModelVersionId: null,
        reportedRevision: null,
        occurredAt: null
      } as any,
      {
        ...property('unknown', null),
        availability: 'SOURCE_UNKNOWN',
        sourceModelVersionId: null
      } as any,
      {
        ...property('mismatch', null),
        availability: 'MODEL_MISMATCH',
        sourceModelVersionId: '019c1234-5678-7890-8123-456789abcdee'
      }
    ]
    vi.mocked(readDeviceEvidence).mockResolvedValue(result as any)
    await ready(result.properties.map((p) => p.key))
    await vm().read()
    expect(vm().snapshot).toEqual(result)
    expect(vm().displayValue(result.properties[0])).toBe('0')
    expect(vm().displayValue(result.properties[1])).toBe('false')
    expect(vm().displayValue(result.properties[2])).toContain('<img')
    for (const p of result.properties.slice(3)) expect(vm().displayValue(p)).toBe('不可用')
    expect(panel.find('img').exists()).toBe(false)
  })
  it.each([
    'project',
    'device',
    'account',
    'tenant',
    'role',
    'logout',
    'token',
    'selection',
    'unmount'
  ])('%s 取消并丢弃迟到证据', async (change) => {
    page()
    await ready()
    const pending = deferred()
    vi.mocked(readDeviceEvidence).mockReturnValue(pending.promise)
    const work = vm().read()
    const signal = vi.mocked(readDeviceEvidence).mock.calls[0][4]
    if (change === 'project') {
      mocks.user.info.currentProjectId = 'other'
      await panel.setProps({ projectId: 'other' })
    } else if (change === 'device') await panel.setProps({ deviceId: 'other' })
    else if (change === 'account') mocks.user.info.userId = 'other'
    else if (change === 'tenant') mocks.user.info.tenantId = 'other'
    else if (change === 'role') mocks.user.info.roles = []
    else if (change === 'logout') mocks.user.isLogin = false
    else if (change === 'token') mocks.user.accessToken = 'new-token'
    else if (change === 'selection') vm().selected = ['flag']
    else panel.unmount()
    await nextTick()
    expect(signal.aborted).toBe(true)
    pending.resolve(evidence())
    await work
    expect(vm().snapshot).toBeUndefined()
  })
  it('目录迟到和旧失败不污染新设备，也不自动加载新目录', async () => {
    const pending = deferred()
    vi.mocked(fetchBindingMetadata).mockReturnValueOnce(pending.promise)
    page()
    const work = vm().loadCatalog()
    await panel.setProps({ deviceId: 'other' })
    expect(mocks.close).toHaveBeenCalled()
    pending.reject(new Error('synthetic-sensitive-error'))
    await work
    expect(vm().metadata).toBeUndefined()
    expect(vm().error).toBe('')
    expect(fetchBindingMetadata).toHaveBeenCalledOnce()
  })
  it('模型变化必须重新加载，不自动重发或回显错误', async () => {
    page()
    await ready()
    vi.mocked(readDeviceEvidence).mockRejectedValue({ code: 10009, message: 'synthetic-private' })
    await vm().read()
    expect(vm().snapshot).toBeUndefined()
    expect(vm().metadata).toBeUndefined()
    expect(panel.text()).toContain('设备物模型已变化')
    expect(panel.text()).not.toContain('synthetic-private')
    await vm().read()
    expect(readDeviceEvidence).toHaveBeenCalledOnce()
    expect(fetchBindingMetadata).toHaveBeenCalledOnce()
  })
  it.each([
    'projectId',
    'deviceId',
    'modelVersionId',
    'duplicate',
    'unrequested',
    'availability',
    'source',
    'nonNullMissing',
    'time'
  ])('%s 不匹配整批拒绝', async (field) => {
    page()
    await ready()
    const result = evidence()
    if (['projectId', 'deviceId', 'modelVersionId'].includes(field))
      (result as any)[field] = 'other'
    else if (field === 'duplicate') result.properties.push(property())
    else if (field === 'unrequested') result.properties = [property('flag')]
    else if (field === 'availability') result.properties[0].availability = 'UNKNOWN'
    else if (field === 'source')
      result.properties[0].sourceModelVersionId = '019c1234-5678-7890-8123-456789abcdee'
    else if (field === 'nonNullMissing') result.properties[0].availability = 'MISSING'
    else result.device.readAt = 'invalid'
    vi.mocked(readDeviceEvidence).mockResolvedValue(result as any)
    await vm().read()
    expect(vm().snapshot).toBeUndefined()
    expect(vm().error).toContain('证据读取不可用')
    expect(readDeviceEvidence).toHaveBeenCalledOnce()
  })
  it('无模型/属性和非法选择不请求证据，按钮及方法均防重', async () => {
    page()
    await vm().read()
    expect(readDeviceEvidence).not.toHaveBeenCalled()
    await ready([])
    for (const keys of [[], ['zero', 'zero'], ['unlisted'], Array(11).fill('zero')]) {
      vm().selected = keys
      await vm().read()
    }
    expect(readDeviceEvidence).not.toHaveBeenCalled()
    vm().selected = ['zero']
    const pending = deferred()
    vi.mocked(readDeviceEvidence).mockReturnValue(pending.promise)
    const work = vm().read()
    await vm().read()
    expect(readDeviceEvidence).toHaveBeenCalledOnce()
    pending.resolve(evidence())
    await work
  })
  it('失败清空旧值，只允许手动重试', async () => {
    page()
    await ready()
    await vm().read()
    vi.mocked(readDeviceEvidence).mockRejectedValue(new Error('private-message'))
    await vm().read()
    expect(vm().snapshot).toBeUndefined()
    expect(vm().error).not.toContain('private-message')
    await flushPromises()
    expect(readDeviceEvidence).toHaveBeenCalledTimes(2)
  })
})
