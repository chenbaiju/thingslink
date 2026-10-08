import { mount, flushPromises } from '@vue/test-utils'
import { reactive } from 'vue'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
const mocks = vi.hoisted(() => ({
  current: vi.fn(),
  create: vi.fn(),
  canCreate: false,
  deviceDetail: vi.fn(),
  recordRecent: vi.fn(),
  typeDetail: vi.fn(),
  typeSearch: vi.fn(),
  route: { path: '/device/list', query: {} as Record<string, string> },
  push: vi.fn(),
  replace: vi.fn(),
  definitions: vi.fn(),
  epoch: 1,
  user: {
    info: {
      currentProjectId: 'project',
      userId: 'user',
      tenantId: 'tenant',
      buttons: ['device:read'] as string[]
    },
    accessToken: 'token'
  }
}))
vi.mock('@/utils/workbench-recent', () => ({ recordRecentResource: mocks.recordRecent }))
vi.mock('vue-router', async () => {
  const { reactive } = await import('vue')
  return {
    useRoute: () => reactive(mocks.route),
    useRouter: () => ({ push: mocks.push, replace: mocks.replace })
  }
})
vi.mock('@/api/device', () => ({
  fetchDeviceDetail: mocks.deviceDetail,
  fetchDeviceTypePage: mocks.typeSearch,
  fetchDeviceTypeDetail: mocks.typeDetail,
  fetchDeviceEventDefinitions: vi.fn().mockResolvedValue([]),
  fetchSearchDevices: vi.fn().mockResolvedValue({ items: [] }),
  fetchCreateDevice: mocks.create,
  fetchUpdateDevice: vi.fn(),
  fetchDeleteDevice: vi.fn(),
  fetchDeviceCredentials: vi.fn().mockResolvedValue([]),
  fetchDeviceMessages: vi.fn().mockResolvedValue({ items: [] }),
  fetchDeviceConnections: vi.fn().mockResolvedValue([]),
  fetchDeviceShadow: vi.fn().mockResolvedValue({ desired: '{}', version: 0 }),
  fetchBatchCurrentValues: mocks.current,
  fetchGenerateCredential: vi.fn(),
  fetchRevokeCredential: vi.fn(),
  fetchUpdateDesired: vi.fn(),
  fetchDevicePropertyDefinitions: mocks.definitions,
  fetchDeviceCommandDefinitions: vi.fn().mockResolvedValue([]),
  fetchSubmitDeviceCommand: vi.fn(),
  fetchDeviceCommand: vi.fn(),
  fetchPropertyHistory: vi.fn().mockResolvedValue({ points: [] })
}))
vi.mock('@/api/device-group', () => ({ fetchDeviceGroups: vi.fn().mockResolvedValue([]) }))
vi.mock('@/api/dashboard-binding', () => ({
  createDesignerReadScope: () => ({ close: vi.fn() }),
  fetchBindingMetadata: vi.fn().mockRejectedValue(new Error('无可用模型'))
}))
vi.mock('@/api/dashboard-alarms', () => ({ fetchDesignerAlarms: vi.fn() }))
vi.mock('@/store/modules/user', () => ({ useUserStore: () => mocks.user }))
vi.mock('@/hooks/core/useAuth', () => ({
  useAuth: () => ({ hasAuth: (auth: string) => auth === 'device:create' && mocks.canCreate })
}))
vi.mock('@/utils/http/error', () => ({ HttpError: class extends Error {} }))
vi.mock('@/utils/http/identity-scope', () => ({ currentIdentityEpoch: () => mocks.epoch }))
vi.mock('@/utils/realtime', () => ({
  RealtimeClient: class {
    connect() {}
    close() {}
  }
}))
vi.mock('@/composables/usePagedDeviceCatalog', async () => {
  const { ref } = await import('vue')
  return {
    usePagedDeviceCatalog: () => ({
      deviceTypes: ref([]),
      deviceTypesLoading: ref(false),
      loadDeviceTypes: vi.fn(),
      onDeviceTypePopupScroll: vi.fn()
    })
  }
})
vi.mock('@/views/device/components/DeviceTagEditor.vue', () => ({
  default: { render: () => null }
}))
vi.mock('@/views/device/components/DeviceAdvancedFilter.vue', () => ({
  default: { render: () => null }
}))
vi.mock('@/views/device/components/DeviceCapabilityContent.vue', () => ({
  default: { render: () => null }
}))
vi.mock('@/views/device/components/DeviceAlarmStatus.vue', () => ({
  default: { render: () => null }
}))
vi.mock('@/views/device/components/DeviceCommandHistory.vue', () => ({
  default: { render: () => null }
}))
vi.mock('@/views/device/components/DeviceAgentEvidence.vue', () => ({
  default: { render: () => null }
}))
vi.mock('@/views/device/components/DeviceEndUsers.vue', () => ({
  default: { template: '<div />' }
}))
vi.mock('@/views/device/components/DeviceTasks.vue', () => ({ default: { render: () => null } }))
vi.mock('@/views/device/components/DeviceMessageRules.vue', () => ({
  default: { render: () => null }
}))
vi.mock('@/views/device/components/DeviceScenes.vue', () => ({
  default: { render: () => null }
}))
vi.mock('@/views/device/components/DeviceAutomations.vue', () => ({
  default: { render: () => null }
}))
import Devices from '@/views/device/index/index.vue'
interface DetailState {
  formVisible: boolean
  openCreate(): void
  submit(): Promise<void>
  formRef?: { validate(): Promise<void> }
  form: { deviceTypeId: string }
  resourceError: string
  typeHandoffError: string
  loadDetail(row: {
    id: string
    name?: string
    deviceTypeId?: string
    gatewayId?: string
  }): Promise<void>
  loadDetailDeviceType(): Promise<void>
  openDetail(row: { id: string }): Promise<void>
  returnToList(): Promise<void>
  detailVisible: boolean
  detailName: string
  closeRealtime(): void
  shadowReported: string
  detailProperties: Array<{ propertyKey: string }>
  detailDeviceType?: { id: string; name: string; deviceKind: string; payloadProtocol: string }
  detailTypeLoading: boolean
  detailTypeUnavailable: boolean
  detailTypeName: string
  detailTypeId: string
  capabilityTabs: Array<{ key: string }>
  deviceTypes: Array<{ id: string; name: string; deviceKind: string; payloadProtocol: string }>
  typeMap: Record<string, string>
}
const wrappers: ReturnType<typeof mount>[] = []
function detail() {
  const wrapper = mount({ ...Devices, render: () => null })
  wrappers.push(wrapper)
  return (wrapper.vm.$ as unknown as { setupState: DetailState }).setupState
}
function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: Error) => void
  const promise = new Promise<T>((done, fail) => {
    resolve = done
    reject = fail
  })
  return { promise, resolve, reject }
}
function response(deviceId: string, value: number, revision: string) {
  return {
    items: [
      {
        deviceId,
        values: { temperature: value },
        occurredAt: { temperature: '2026-09-12T00:00:00Z' },
        reportedRevisions: { temperature: revision },
        thingModelVersionIds: {}
      }
    ]
  }
}
beforeEach(() => {
  mocks.create.mockReset().mockResolvedValue({ id: 'created' })
  mocks.canCreate = false
  mocks.route.query = {}
  mocks.push.mockImplementation(async (target) => {
    reactive(mocks.route).query = target.query
  })
  mocks.replace.mockImplementation(async (target) => {
    reactive(mocks.route).query = target.query
  })
  mocks.deviceDetail.mockReset()
  mocks.recordRecent.mockReset()
  mocks.typeSearch.mockReset().mockResolvedValue({ items: [], hasMore: false })
  mocks.typeDetail.mockReset().mockImplementation(async (projectId, id) => ({
    id,
    projectId,
    name: '当前类型',
    deviceKind: 'DIRECT',
    payloadProtocol: 'STANDARD'
  }))
  mocks.user = reactive({
    info: {
      currentProjectId: 'project',
      userId: 'user',
      tenantId: 'tenant',
      buttons: ['device:read'] as string[]
    },
    accessToken: 'token'
  })
  mocks.epoch = 1
  mocks.current.mockReset()
  mocks.current.mockResolvedValue({ items: [] })
  mocks.definitions.mockReset()
  mocks.definitions.mockResolvedValue([{ propertyKey: 'temperature', dataType: 'NUMBER' }])
})
afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount())
})
it('真实详情编排拒绝同项目 A→B→A 旧 REST', async () => {
  const old = deferred<ReturnType<typeof response>>()
  mocks.current
    .mockImplementationOnce(() => old.promise)
    .mockResolvedValueOnce(response('b', 2, '2'))
    .mockResolvedValueOnce(response('a', 3, '3'))
  const state = detail()
  const original = state.loadDetail({ id: 'a', deviceTypeId: 'type' })
  await flushPromises()
  await state.loadDetail({ id: 'b', deviceTypeId: 'type' })
  await state.loadDetail({ id: 'a', deviceTypeId: 'type' })
  old.resolve(response('a', 1, '1'))
  await original
  await flushPromises()
  expect(JSON.parse(state.shadowReported)).toEqual({ temperature: 3 })
})
it('迟到模型定义不能按新设备创建旧属性订阅', async () => {
  const old = deferred<Array<{ propertyKey: string; dataType: string }>>()
  mocks.definitions.mockImplementationOnce(() => old.promise)
  mocks.current.mockResolvedValue(response('b', 2, '2'))
  const state = detail(),
    original = state.loadDetail({ id: 'a', deviceTypeId: 'old-type' })
  await flushPromises()
  await state.loadDetail({ id: 'b', deviceTypeId: 'new-type' })
  old.resolve([{ propertyKey: 'old-secret', dataType: 'NUMBER' }])
  await original
  expect(state.detailProperties.map((item) => item.propertyKey)).toEqual(['temperature'])
  expect(mocks.current).toHaveBeenCalledTimes(1)
})
it('关闭或身份换代后迟到 REST 不恢复已清视图', async () => {
  const old = deferred<ReturnType<typeof response>>()
  mocks.current.mockImplementationOnce(() => old.promise)
  const state = detail(),
    pending = state.loadDetail({ id: 'a', deviceTypeId: 'type' })
  await flushPromises()
  state.closeRealtime()
  mocks.epoch++
  old.resolve(response('a', 1, '1'))
  await pending
  expect(state.shadowReported).toBe('')
})

it('详情导航通过设备参数加载事实，返回清除参数并关闭详情', async () => {
  mocks.deviceDetail.mockResolvedValue({ id: 'a', name: '设备 A', deviceTypeId: 'type' })
  mocks.current.mockResolvedValue(response('a', 3, '3'))
  const state = detail()
  await state.openDetail({ id: 'a' })
  await flushPromises()
  expect(mocks.deviceDetail).toHaveBeenCalledWith('project', 'a')
  expect(state.detailVisible).toBe(true)
  expect(state.detailName).toBe('设备 A')
  await state.returnToList()
  await flushPromises()
  expect(mocks.route.query.deviceId).toBeUndefined()
  expect(state.detailVisible).toBe(false)
})

const type = (name = '当前直连类型', projectId = 'project', id = 'type') => ({
  id,
  projectId,
  name,
  deviceKind: 'DIRECT',
  payloadProtocol: 'STANDARD'
})
it('详情缓存未命中只发单条GET，不扫描任何目录页', async () => {
  const state = detail()
  await flushPromises()
  mocks.typeSearch.mockClear()
  await state.loadDetail({ id: 'a', deviceTypeId: 'type' })
  expect(mocks.typeDetail).toHaveBeenCalledExactlyOnceWith('project', 'type')
  expect(mocks.typeSearch).not.toHaveBeenCalled()
  expect(state.detailTypeName).toBe('当前类型')
})
it('缓存旧网关不能决定当前能力，每次打开都重新GET直连事实', async () => {
  const state = detail()
  state.deviceTypes = [
    { id: 'type', name: '旧网关', deviceKind: 'GATEWAY', payloadProtocol: 'STANDARD_GATEWAY' }
  ]
  state.typeMap = { type: '旧网关' }
  await state.loadDetail({ id: 'a', deviceTypeId: 'type' })
  expect(state.detailTypeName).toBe('当前类型')
  expect(state.capabilityTabs.map((tab) => tab.key)).not.toContain('topology')
  expect(state.capabilityTabs.map((tab) => tab.key)).not.toContain('modbus')
  await state.loadDetail({ id: 'a', deviceTypeId: 'type' })
  expect(mocks.typeDetail).toHaveBeenCalledTimes(2)
  expect(mocks.typeSearch).not.toHaveBeenCalled()
})
it('当前单条网关协议事实保留拓扑与Modbus能力', async () => {
  mocks.typeDetail.mockResolvedValue({
    ...type(),
    deviceKind: 'GATEWAY',
    payloadProtocol: 'STANDARD_GATEWAY'
  })
  const state = detail()
  await state.loadDetail({ id: 'a', deviceTypeId: 'type' })
  expect(state.capabilityTabs.map((tab) => tab.key)).toContain('topology')
  expect(state.capabilityTabs.map((tab) => tab.key)).toContain('modbus')
})
it.each(['404', '403', '撤权'])(
  '类型%s独立不可用，清能力和名称缓存，保设备事实并可手动重试',
  async (reason) => {
    const state = detail()
    await state.loadDetail({ id: 'a', name: '设备 A', deviceTypeId: 'type' })
    mocks.typeDetail.mockRejectedValueOnce(new Error(reason))
    await state.loadDetailDeviceType()
    expect(state.detailVisible).toBe(true)
    expect(state.detailName).toBe('设备 A')
    expect(state.detailDeviceType).toBeUndefined()
    expect(state.detailTypeUnavailable).toBe(true)
    expect(state.detailTypeName).toBe('不可用')
    expect(state.typeMap.type).toBeUndefined()
    expect(state.detailTypeLoading).toBe(false)
    await state.loadDetailDeviceType()
    expect(state.detailTypeUnavailable).toBe(false)
    expect(state.detailTypeName).toBe('当前类型')
    expect(mocks.typeDetail).toHaveBeenCalledTimes(3)
  }
)
it.each([type('跨项目', 'another-project'), type('错误类型', 'project', 'another-type')])(
  '单条响应必须匹配当前project和type，不能缓存或展示错域事实',
  async (value) => {
    mocks.typeDetail.mockResolvedValue(value)
    const state = detail()
    await state.loadDetail({ id: 'a', deviceTypeId: 'type' })
    expect(state.detailTypeUnavailable).toBe(true)
    expect(state.detailDeviceType).toBeUndefined()
    expect(state.typeMap.type).toBeUndefined()
    expect(state.capabilityTabs.map((tab) => tab.key)).not.toContain('modbus')
  }
)
it('无绑定类型零GET，独立设备gateway引用仍保留拓扑', async () => {
  const state = detail()
  await state.loadDetail({ id: 'a', gatewayId: 'gateway' })
  expect(mocks.typeDetail).not.toHaveBeenCalled()
  expect(state.detailTypeName).toBe('无')
  expect(state.detailTypeUnavailable).toBe(false)
  expect(state.capabilityTabs.map((tab) => tab.key)).toContain('topology')
})
it('同项目设备A→B→A旧类型成功与finally不能覆盖第三次读取', async () => {
  const old = deferred<ReturnType<typeof type>>()
  const newest = deferred<ReturnType<typeof type>>()
  mocks.typeDetail
    .mockReturnValueOnce(old.promise)
    .mockResolvedValueOnce(type('B类型'))
    .mockReturnValueOnce(newest.promise)
  const state = detail()
  const first = state.loadDetail({ id: 'a', deviceTypeId: 'type' })
  await flushPromises()
  await state.loadDetail({ id: 'b', deviceTypeId: 'type' })
  const third = state.loadDetail({ id: 'a', deviceTypeId: 'type' })
  await flushPromises()
  old.resolve(type('旧A类型'))
  await first
  expect(state.detailDeviceType).toBeUndefined()
  expect(state.detailTypeLoading).toBe(true)
  newest.resolve(type('新A类型'))
  await third
  expect(state.detailTypeName).toBe('新A类型')
})
it('身份换代后的类型成功不恢复已清事实，旧finally不清新loading', async () => {
  const old = deferred<ReturnType<typeof type>>()
  const newest = deferred<ReturnType<typeof type>>()
  mocks.typeDetail.mockReturnValueOnce(old.promise).mockReturnValueOnce(newest.promise)
  const state = detail()
  const first = state.loadDetail({ id: 'a', deviceTypeId: 'type' })
  await flushPromises()
  state.closeRealtime()
  mocks.epoch++
  const second = state.loadDetail({ id: 'a', deviceTypeId: 'type' })
  await flushPromises()
  old.resolve(type('旧身份类型'))
  await first
  expect(state.detailTypeLoading).toBe(true)
  expect(state.typeMap.type).toBeUndefined()
  newest.resolve(type('新身份类型'))
  await second
  expect(state.detailTypeName).toBe('新身份类型')
})
it('类型切换时旧响应不得写名称或清当前读取状态', async () => {
  const old = deferred<ReturnType<typeof type>>()
  mocks.typeDetail.mockReturnValueOnce(old.promise)
  const state = detail()
  const first = state.loadDetail({ id: 'a', deviceTypeId: 'type' })
  await flushPromises()
  state.detailTypeId = 'new-type'
  await state.loadDetailDeviceType()
  old.resolve(type('旧类型'))
  await first
  expect(state.detailDeviceType?.id).toBe('new-type')
  expect(state.typeMap.type).toBeUndefined()
  expect(state.detailTypeLoading).toBe(false)
})
it('关闭详情立即清类型与名称，迟到成功不能复活', async () => {
  const old = deferred<ReturnType<typeof type>>()
  mocks.typeDetail.mockReturnValueOnce(old.promise)
  const state = detail()
  const pending = state.loadDetail({ id: 'a', deviceTypeId: 'type' })
  await flushPromises()
  state.closeRealtime()
  state.detailVisible = false
  old.resolve(type('关闭后旧类型'))
  await pending
  expect(state.detailDeviceType).toBeUndefined()
  expect(state.detailTypeLoading).toBe(false)
  expect(state.typeMap.type).toBeUndefined()
})
it('类型读取独立，待响应时其他设备事实与返回操作不被整体loading阻挡', async () => {
  const old = deferred<ReturnType<typeof type>>()
  mocks.typeDetail.mockReturnValueOnce(old.promise)
  const state = detail()
  await state.loadDetail({ id: 'a', name: '设备 A', deviceTypeId: 'type' })
  expect(state.detailName).toBe('设备 A')
  expect(state.detailTypeLoading).toBe(true)
  state.closeRealtime()
  old.resolve(type('不应展示'))
  await flushPromises()
  expect(state.detailDeviceType).toBeUndefined()
  expect(state.detailTypeLoading).toBe(false)
})
it('项目A→B→A旧类型失败及finally不能影响新项目详情', async () => {
  const old = deferred<ReturnType<typeof type>>()
  const newest = deferred<ReturnType<typeof type>>()
  mocks.typeDetail
    .mockReturnValueOnce(old.promise)
    .mockResolvedValueOnce(type('B类型', 'project-b'))
    .mockReturnValueOnce(newest.promise)
  const state = detail()
  await state.loadDetail({ id: 'a', deviceTypeId: 'type' })
  mocks.user.info.currentProjectId = 'project-b'
  await flushPromises()
  await state.loadDetail({ id: 'a', deviceTypeId: 'type' })
  mocks.user.info.currentProjectId = 'project'
  await flushPromises()
  await state.loadDetail({ id: 'a', deviceTypeId: 'type' })
  old.reject(new Error('旧项目拒绝'))
  await flushPromises()
  expect(state.detailTypeLoading).toBe(true)
  expect(state.detailTypeUnavailable).toBe(false)
  newest.resolve(type('新A类型'))
  await flushPromises()
  expect(state.detailTypeName).toBe('新A类型')
})

it('类型深链校验单读后打开创建表单，消费URL意图并保留类型名称', async () => {
  mocks.canCreate = true
  mocks.user.info.buttons = ['device:create']
  const id = '11111111-1111-4111-8111-111111111111'
  mocks.route.query = { createTypeId: id, contextProjectId: 'project' }
  mocks.typeDetail.mockResolvedValue({
    id,
    projectId: 'project',
    status: 'PUBLISHED',
    name: '指定类型'
  })
  const state = detail()
  await flushPromises()
  expect(state.formVisible).toBe(true)
  expect(state.form.deviceTypeId).toBe(id)
  expect(state.deviceTypes.some((type) => type.id === id && type.name === '指定类型')).toBe(true)
  expect(mocks.route.query.createTypeId).toBeUndefined()
  mocks.user.info.currentProjectId = 'other'
  await flushPromises()
  expect(state.formVisible).toBe(false)
  expect(state.form.deviceTypeId).toBe('')
})
it('类型深链缺少权限不读取类型，显示可恢复提示', async () => {
  mocks.route.query = {
    createTypeId: '11111111-1111-4111-8111-111111111111',
    contextProjectId: 'project'
  }
  const state = detail()
  await flushPromises()
  expect(mocks.typeDetail).not.toHaveBeenCalled()
  expect(state.formVisible).toBe(false)
  expect(state.typeHandoffError).toContain('创建设备权限')
})
it('类型深链读取期间切换项目不打开旧项目表单', async () => {
  mocks.canCreate = true
  mocks.user.info.buttons = ['device:create']
  const id = '11111111-1111-4111-8111-111111111111'
  mocks.route.query = { createTypeId: id, contextProjectId: 'project' }
  const pending = deferred<any>()
  mocks.typeDetail.mockReturnValueOnce(pending.promise)
  const state = detail()
  mocks.user.info.currentProjectId = 'other'
  pending.resolve({ id, projectId: 'project', status: 'PUBLISHED' })
  await flushPromises()
  expect(state.formVisible).toBe(false)
  expect(state.form.deviceTypeId).toBe('')
})

it('创建验证尚未结束时切换项目，禁止向新旧项目发起写入', async () => {
  mocks.canCreate = true
  mocks.user.info.buttons = ['device:create']
  const state = detail()
  await flushPromises()
  state.openCreate()
  const validation = deferred<void>()
  state.formRef = { validate: () => validation.promise }
  const submit = state.submit()
  mocks.user.info.currentProjectId = 'other'
  validation.resolve()
  await submit
  expect(mocks.create).not.toHaveBeenCalled()
  expect(state.formVisible).toBe(false)
})
it('创建失败保留当前输入和打开状态，可原地重试', async () => {
  mocks.canCreate = true
  mocks.user.info.buttons = ['device:create']
  const state = detail()
  await flushPromises()
  state.openCreate()
  state.form.deviceTypeId = 'chosen'
  state.formRef = { validate: async () => {} }
  mocks.create.mockRejectedValueOnce(new Error('network'))
  const quiet = vi.spyOn(console, 'error').mockImplementation(() => {})
  await state.submit()
  quiet.mockRestore()
  expect(state.formVisible).toBe(true)
  expect(state.form.deviceTypeId).toBe('chosen')
  expect(mocks.create).toHaveBeenCalledOnce()
})

const recentDeviceId = '11111111-1111-4111-8111-111111111111'
it('最近设备引用必须重新单读当前项目，成功后才登记访问', async () => {
  mocks.route.query = { resourceId: recentDeviceId, contextProjectId: 'project' }
  mocks.deviceDetail.mockResolvedValue({ id: recentDeviceId, name: '最近设备' })
  const state = detail()
  await flushPromises()
  expect(mocks.deviceDetail).toHaveBeenCalledExactlyOnceWith('project', recentDeviceId)
  expect(state.detailName).toBe('最近设备')
  expect(mocks.recordRecent).toHaveBeenCalledWith(
    { userId: 'user', tenantId: 'tenant', projectId: 'project' },
    { kind: 'device', id: recentDeviceId, label: '最近设备' }
  )
})
it.each(['missing-project', 'wrong-project', 'legacy-wrong-project', 'conflicting-id', 'no-read'])(
  '最近设备引用%s不发出读取且提示可恢复',
  async (kind) => {
    mocks.route.query = { resourceId: recentDeviceId, contextProjectId: 'project' }
    if (kind === 'missing-project') delete mocks.route.query.contextProjectId
    if (kind === 'wrong-project') mocks.route.query.contextProjectId = 'other'
    if (kind === 'legacy-wrong-project')
      mocks.route.query = { deviceId: recentDeviceId, contextProjectId: 'other' }
    if (kind === 'conflicting-id') mocks.route.query.deviceId = 'other'
    if (kind === 'no-read') mocks.user.info.buttons = []
    const state = detail()
    await flushPromises()
    expect(mocks.deviceDetail).not.toHaveBeenCalled()
    expect(mocks.recordRecent).not.toHaveBeenCalled()
    expect(state.detailVisible).toBe(false)
    expect(state.resourceError).toContain('无法读取来源设备')
  }
)
it('最近设备迟响应遇到项目切换不记录或打开旧设备', async () => {
  mocks.route.query = { resourceId: recentDeviceId, contextProjectId: 'project' }
  const old = deferred<any>()
  mocks.deviceDetail.mockReturnValueOnce(old.promise)
  const state = detail()
  mocks.user.info.currentProjectId = 'other'
  old.resolve({ id: recentDeviceId, name: '旧设备' })
  await flushPromises()
  expect(state.detailVisible).toBe(false)
  expect(mocks.recordRecent).not.toHaveBeenCalled()
})
it('从无效最近引用点击设备行时清除旧引用并选择新设备', async () => {
  mocks.route.query = { resourceId: recentDeviceId, contextProjectId: 'other' }
  mocks.deviceDetail.mockResolvedValue({ id: 'selected', name: '已选设备' })
  const state = detail()
  await state.openDetail({ id: 'selected' })
  await flushPromises()
  expect(mocks.route.query.resourceId).toBeUndefined()
  expect(mocks.route.query.contextProjectId).toBe('project')
  expect(mocks.deviceDetail).toHaveBeenCalledExactlyOnceWith('project', 'selected')
  expect(state.detailName).toBe('已选设备')
})
