import { reactive } from 'vue'
import { mount, flushPromises } from '@vue/test-utils'
import { beforeEach, afterEach, expect, it, vi } from 'vitest'
import { emptyDashboard } from '@/features/dashboard/designer-model'
import type { DesignerRealtimeOptions, DesignerRealtime } from '@/api/dashboard-realtime'
const mocks = vi.hoisted(() => ({
  load: vi.fn(),
  refresh: vi.fn(),
  factory: vi.fn(),
  current: vi.fn()
}))
const user = reactive({ accessToken: 'test-token' })
vi.mock('@/store/modules/user', () => ({ useUserStore: () => user }))
vi.mock('@/api/dashboard-binding', () => ({
  createDesignerReadScope: () => ({
    deadline: performance.now() + 30_000,
    close: vi.fn(),
    tryReserveWebSocket: () => true
  }),
  fetchPreviewSnapshots: vi.fn(),
  fetchPreviewCurrent: mocks.current,
  fetchDesignerDeviceCatalog: vi.fn()
}))
vi.mock('@/api/dashboard-realtime', () => ({ createDesignerRealtime: mocks.factory }))
vi.mock('@/features/dashboard/device-preview', () => ({
  loadDesignerDevicePreview: mocks.load,
  refreshDesignerCurrent: mocks.refresh,
  DesignerCurrentRecoveryRequired: class extends Error {}
}))
import DesignerDeviceSelector from '@/views/dashboard/designer/components/DesignerDeviceSelector.vue'
import DesignerDevicePreview from '@/views/dashboard/designer/components/DesignerDevicePreview.vue'
import { closeDesignerRealtime } from '@/features/dashboard/realtime-lifecycle'
const plan = {
  devices: [{ deviceId: 'device', expectedModelVersionId: 'model', propertyKeys: ['temperature'] }]
}
const row = (text: string) => [{ componentId: 'value', title: '温度', text }]
const connections: {
  options: DesignerRealtimeOptions
  connection: DesignerRealtime
  dirty: boolean
}[] = []
let wrappers: ReturnType<typeof mount>[] = []
function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => {
    resolve = done
  })
  return { promise, resolve }
}
function fixture() {
  const wrapper = mount(DesignerDevicePreview, {
    props: { schema: emptyDashboard(), pageId: 'main', projectId: 'project', available: true },
    global: {
      stubs: {
        ElButton: {
          props: ['disabled'],
          template: '<button :disabled="disabled"><slot /></button>'
        },
        ElAlert: true
      }
    }
  })
  wrappers.push(wrapper)
  return wrapper
}
function dirty(index = connections.length - 1) {
  const owned = connections[index]!
  owned.dirty = true
  owned.options.onDirty()
}
beforeEach(() => {
  user.accessToken = 'test-token'
  vi.clearAllMocks()
  connections.length = 0
  mocks.current.mockResolvedValue({})
  mocks.load.mockImplementation(async (_schema, _page, ports) => {
    await ports.beforeCurrentRead(plan)
    await ports.current({ devices: plan.devices })
    return row('原权威值')
  })
  mocks.refresh.mockResolvedValue(row('新权威值'))
  mocks.factory.mockImplementation((options: DesignerRealtimeOptions) => {
    const owned = { options, connection: undefined as unknown as DesignerRealtime, dirty: false }
    owned.connection = {
      start: vi.fn(async () => {
        options.onState('SUBSCRIBED')
        return 'SUBSCRIBED' as const
      }),
      takeDirty: vi.fn(() => {
        const result = owned.dirty ? plan.devices : []
        owned.dirty = false
        return result
      }),
      close: vi.fn(async () => {})
    }
    connections.push(owned)
    return owned.connection
  })
})
afterEach(async () => {
  for (const wrapper of wrappers) wrapper.unmount()
  wrappers = []
  for (const owned of connections) vi.mocked(owned.connection.close).mockResolvedValue(undefined)
  await closeDesignerRealtime()
  vi.restoreAllMocks()
})
it('ACK尚未确认不读取PG，确认后完整首读才发布视图', async () => {
  const ack = deferred<'SUBSCRIBED'>()
  const original = mocks.factory.getMockImplementation()!
  mocks.factory.mockImplementation((options) => {
    const connection = original(options)
    connection.start = vi.fn(async () => {
      const result = await ack.promise
      options.onState(result)
      return result
    })
    return connection
  })
  const wrapper = fixture()
  await flushPromises()
  expect(mocks.current).not.toHaveBeenCalled()
  expect(wrapper.text()).not.toContain('原权威值')
  ack.resolve('SUBSCRIBED')
  await flushPromises()
  expect(mocks.current).toHaveBeenCalledOnce()
  expect(wrapper.text()).toContain('原权威值')
})
it('在途新增dirty不丢，后续只current且启动至少间隔一秒', async () => {
  const wrapper = fixture()
  await flushPromises()
  const pending = deferred<ReturnType<typeof row>>()
  const starts: number[] = []
  mocks.refresh
    .mockImplementationOnce(() => {
      starts.push(performance.now())
      return pending.promise
    })
    .mockImplementationOnce(async () => {
      starts.push(performance.now())
      return row('第二次权威值')
    })
  dirty()
  await vi.waitFor(() => expect(mocks.refresh).toHaveBeenCalledTimes(1), { timeout: 1500 })
  dirty()
  await flushPromises()
  expect(mocks.refresh).toHaveBeenCalledTimes(1)
  pending.resolve(row('第一次权威值'))
  await vi.waitFor(() => expect(wrapper.text()).toContain('第二次权威值'), { timeout: 1600 })
  expect(starts[1]! - starts[0]!).toBeGreaterThanOrEqual(1000)
  expect(mocks.load).toHaveBeenCalledOnce()
  expect(mocks.current).toHaveBeenCalledOnce()
})
it('新页面必须等旧socket物理关闭，取消后旧current成功不回填', async () => {
  const wrapper = fixture()
  await flushPromises()
  const reading = deferred<ReturnType<typeof row>>()
  mocks.refresh.mockReturnValueOnce(reading.promise)
  dirty()
  await vi.waitFor(() => expect(mocks.refresh).toHaveBeenCalledOnce(), { timeout: 1500 })
  const closing = deferred<void>()
  vi.mocked(connections[0]!.connection.close).mockReturnValue(closing.promise)
  await wrapper.setProps({ projectId: 'next-project' })
  reading.resolve(row('旧请求迟到值'))
  await flushPromises()
  expect(wrapper.text()).not.toContain('旧请求迟到值')
  expect(connections).toHaveLength(1)
  closing.resolve(undefined)
  await flushPromises()
  expect(connections).toHaveLength(2)
  expect(wrapper.text()).not.toContain('旧请求迟到值')
})
it('实时1008触发一次完整REST复核，不能构成握手拒绝重连循环', async () => {
  const wrapper = fixture()
  await flushPromises()
  connections[0]!.options.onRevoked()
  await flushPromises()
  expect(mocks.load).toHaveBeenCalledTimes(2)
  expect(connections).toHaveLength(1)
  expect(wrapper.get('[data-testid=preview-realtime-state]').attributes('data-state')).toBe(
    'REST_READY'
  )
})
it('current网络失败清旧值并锁存，预算通知或可见性不得自动恢复', async () => {
  const wrapper = fixture()
  await flushPromises()
  mocks.refresh.mockRejectedValueOnce(new Error('network'))
  dirty()
  await vi.waitFor(
    () =>
      expect(wrapper.get('[data-testid=preview-rest-state]').attributes('data-state')).toBe(
        'RETRY_REQUIRED'
      ),
    { timeout: 1500 }
  )
  expect(wrapper.text()).not.toContain('原权威值')
  await wrapper.setProps({ available: false })
  await wrapper.setProps({ available: true })
  await flushPromises()
  expect(connections).toHaveLength(1)
  expect(mocks.load).toHaveBeenCalledOnce()
  expect(connections[0]!.connection.close).toHaveBeenCalled()
})
it('关闭失败跨组件卸载仍阻止新连接，确认旧连接关闭后才可显式恢复', async () => {
  const first = fixture()
  await flushPromises()
  vi.mocked(connections[0]!.connection.close).mockRejectedValue(new Error('physical close pending'))
  first.unmount()
  wrappers = []
  const next = fixture()
  await flushPromises()
  expect(connections).toHaveLength(1)
  expect(next.get('[data-testid=preview-rest-state]').attributes('data-state')).toBe(
    'RETRY_REQUIRED'
  )
  vi.mocked(connections[0]!.connection.close).mockResolvedValue(undefined)
  await next.get('button').trigger('click')
  await flushPromises()
  expect(connections).toHaveLength(2)
  expect(next.text()).toContain('原权威值')
})
it('正常令牌轮换等待旧物理关闭后仅REST恢复，旧dirty不能读取', async () => {
  const wrapper = fixture()
  await flushPromises()
  const closing = deferred<void>()
  vi.mocked(connections[0]!.connection.close).mockReturnValue(closing.promise)
  user.accessToken = 'rotated-token'
  dirty(0)
  await flushPromises()
  expect(wrapper.text()).not.toContain('原权威值')
  expect(mocks.load).toHaveBeenCalledOnce()
  expect(mocks.refresh).not.toHaveBeenCalled()
  closing.resolve(undefined)
  await flushPromises()
  expect(mocks.load).toHaveBeenCalledTimes(2)
  expect(connections).toHaveLength(1)
  expect(wrapper.get('[data-testid=preview-realtime-state]').attributes('data-state')).toBe(
    'REST_READY'
  )
})
it('令牌轮换不解锁旧失败，空令牌立即清旧事实并关闭', async () => {
  const wrapper = fixture()
  await flushPromises()
  connections[0]!.options.onFailure!(new Error('existing failure'))
  await flushPromises()
  user.accessToken = 'rotated-token'
  await flushPromises()
  expect(mocks.load).toHaveBeenCalledOnce()
  expect(wrapper.get('[data-testid=preview-rest-state]').attributes('data-state')).toBe(
    'RETRY_REQUIRED'
  )
  await wrapper.get('button').trigger('click')
  await flushPromises()
  user.accessToken = ''
  await flushPromises()
  expect(wrapper.text()).not.toContain('原权威值')
  expect(connections.at(-1)!.connection.close).toHaveBeenCalled()
  expect(wrapper.get('[data-testid=preview-rest-state]').attributes('data-state')).toBe(
    'RETRY_REQUIRED'
  )
})
it('完整首读模型失配等待关闭并稳定REST，不循环恢复旧订阅', async () => {
  const closing = deferred<void>()
  mocks.load.mockImplementationOnce(async (_schema, _page, ports) => {
    await ports.beforeCurrentRead(plan)
    await ports.current({ devices: plan.devices })
    vi.mocked(connections[0]!.connection.close).mockReturnValue(closing.promise)
    await ports.afterCurrentInvalidation()
    return row('模型已变化')
  })
  const wrapper = fixture()
  await flushPromises()
  expect(wrapper.text()).not.toContain('模型已变化')
  dirty(0)
  closing.resolve(undefined)
  await flushPromises()
  expect(wrapper.text()).toContain('模型已变化')
  expect(wrapper.get('[data-testid=preview-realtime-state]').attributes('data-state')).toBe(
    'REST_READY'
  )
  expect(mocks.load).toHaveBeenCalledOnce()
  expect(mocks.refresh).not.toHaveBeenCalled()
  expect(connections).toHaveLength(1)
})

it('目录拒绝读取关闭既有订阅并清除全部事实，不只清目录', async () => {
  const wrapper = fixture()
  await flushPromises()
  const base = emptyDashboard()
  const schema: ReturnType<typeof emptyDashboard> = {
    ...base,
    models: [
      {
        key: 'model',
        versionId: '11111111-1111-4111-8111-111111111111',
        digest: 'a'.repeat(64),
        digestAlgorithm: 'PG_JSONB_TEXT_V1_SHA256',
        profile: 'TC_PROPERTY_COMPOSITE_V1'
      }
    ],
    variables: [
      { key: 'device', type: 'DEVICE_SINGLE', title: '设备', modelKey: 'model', required: false }
    ],
    pages: [
      {
        ...base.pages[0]!,
        components: [
          {
            id: 'selector',
            kind: 'DEVICE_SELECTOR',
            componentVersion: '1.0.0',
            layout: { x: 0, y: 0, w: 12, h: 4 },
            props: { pageSize: 20, placeholder: '请选择设备' },
            bindings: { directory: { source: 'DEVICE_DIRECTORY', variableKey: 'device' } }
          }
        ]
      }
    ]
  }
  await wrapper.setProps({ schema })
  await flushPromises()
  const connection = connections.at(-1)!.connection
  wrapper.getComponent(DesignerDeviceSelector).vm.$emit('denied')
  await flushPromises()
  expect(connection.close).toHaveBeenCalled()
  expect(wrapper.text()).not.toContain('原权威值')
  expect(wrapper.get('[data-testid=preview-rest-state]').attributes('data-state')).toBe(
    'RETRY_REQUIRED'
  )
})
