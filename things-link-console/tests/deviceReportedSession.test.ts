import { mount, flushPromises } from '@vue/test-utils'
import { reactive } from 'vue'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
const mocks = vi.hoisted(() => ({
  current: vi.fn(),
  deviceDetail: vi.fn(),
  route: { path: '/device/list', query: {} as Record<string, string> },
  push: vi.fn(),
  replace: vi.fn(),
  definitions: vi.fn(),
  epoch: 1,
  user: { info: { currentProjectId: 'project', userId: 'user' }, accessToken: 'token' }
}))
vi.mock('vue-router', async () => {
  const { reactive } = await import('vue')
  return {
    useRoute: () => reactive(mocks.route),
    useRouter: () => ({ push: mocks.push, replace: mocks.replace })
  }
})
vi.mock('@/api/device', () => ({
  fetchDeviceDetail: mocks.deviceDetail,
  fetchDeviceTypePage: vi
    .fn()
    .mockResolvedValue({ items: [{ id: 'type', name: '测试类型' }], hasMore: false }),
  fetchDeviceEventDefinitions: vi.fn().mockResolvedValue([]),
  fetchSearchDevices: vi.fn().mockResolvedValue({ items: [] }),
  fetchCreateDevice: vi.fn(),
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
vi.mock('@/hooks/core/useAuth', () => ({ useAuth: () => ({ hasAuth: () => false }) }))
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
  loadDetail(row: { id: string; deviceTypeId: string }): Promise<void>
  openDetail(row: { id: string }): Promise<void>
  returnToList(): Promise<void>
  detailVisible: boolean
  detailName: string
  closeRealtime(): void
  shadowReported: string
  detailProperties: Array<{ propertyKey: string }>
}
const wrappers: ReturnType<typeof mount>[] = []
function detail() {
  const wrapper = mount({ ...Devices, render: () => null })
  wrappers.push(wrapper)
  return (wrapper.vm.$ as unknown as { setupState: DetailState }).setupState
}
function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => {
    resolve = done
  })
  return { promise, resolve }
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
  mocks.route.query = {}
  mocks.push.mockImplementation(async (target) => {
    reactive(mocks.route).query = target.query
  })
  mocks.replace.mockImplementation(async (target) => {
    reactive(mocks.route).query = target.query
  })
  mocks.deviceDetail.mockReset()
  mocks.epoch = 1
  mocks.current.mockReset()
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
