import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'

const api = vi.hoisted(() => ({ metadata: vi.fn(), alarms: vi.fn(), close: vi.fn() }))
vi.mock('@/api/dashboard-binding', () => ({
  fetchBindingMetadata: api.metadata,
  createDesignerReadScope: () => ({ close: api.close })
}))
vi.mock('@/api/dashboard-alarms', () => ({ fetchDesignerAlarms: api.alarms }))
vi.mock('@/utils/http/identity-scope', () => ({ currentIdentityEpoch: () => 1 }))
import DeviceCapabilityContent from '@/views/device/components/DeviceCapabilityContent.vue'

const button = defineComponent({
  props: ['disabled', 'loading'],
  emits: ['click'],
  setup:
    (p, { slots, emit }) =>
    () =>
      h(
        'button',
        {
          disabled: p.disabled || p.loading,
          onClick: () => emit('click')
        },
        slots.default?.()
      )
})
const toggle = defineComponent({
  props: ['modelValue', 'activeText'],
  emits: ['update:modelValue'],
  setup:
    (p, { emit }) =>
    () =>
      h(
        'button',
        {
          onClick: () => emit('update:modelValue', !p.modelValue)
        },
        p.activeText
      )
})
const table = defineComponent({
  props: ['data'],
  setup:
    (p, { slots }) =>
    () =>
      h('section', p.data.length ? JSON.stringify(p.data) : slots.empty?.())
})
let wrapper: ReturnType<typeof mount>
beforeEach(() => {
  vi.useFakeTimers()
  vi.clearAllMocks()
  vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible')
  api.metadata.mockResolvedValue({ model: { versionId: 'model-a' } })
  api.alarms.mockResolvedValue({ status: 'READY', items: [], hasMore: false })
})
afterEach(() => {
  wrapper?.unmount()
  vi.useRealTimers()
  vi.restoreAllMocks()
})
function page() {
  wrapper = mount(DeviceCapabilityContent, {
    props: { projectId: 'project-a', device: { id: 'device-a' }, kind: 'alarms' },
    global: {
      directives: { loading: () => {} },
      stubs: {
        ElButton: button,
        ElSwitch: toggle,
        ElTable: table,
        ElTableColumn: true,
        ElAlert: { props: ['title'], template: '<p>{{title}}<slot /></p>' },
        ElEmpty: { props: ['description'], template: '<p>{{description}}</p>' }
      }
    }
  })
}
async function click(label: string) {
  await wrapper
    .findAll('button')
    .find((b) => b.text() === label)!
    .trigger('click')
  await flushPromises()
}
describe('设备告警持续监测', () => {
  it('从当前设备及模型读取新告警，不下发或清除告警', async () => {
    page()
    await flushPromises()
    api.alarms.mockResolvedValue({
      status: 'READY',
      items: [{ id: 'alarm-a', conditionState: 'ACTIVE' }],
      hasMore: false
    })
    await vi.advanceTimersByTimeAsync(5000)
    expect(api.alarms).toHaveBeenCalledTimes(2)
    expect(api.alarms.mock.calls[1][1].devices).toEqual([
      { deviceId: 'device-a', expectedModelVersionId: 'model-a' }
    ])
    expect(wrapper.text()).toContain('alarm-a')
  })
  it('页面隐藏或用户暂停时停止定时读取', async () => {
    page()
    await flushPromises()
    vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('hidden')
    await vi.advanceTimersByTimeAsync(10000)
    expect(api.alarms).toHaveBeenCalledTimes(1)
    vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible')
    await click('持续监测告警')
    await vi.advanceTimersByTimeAsync(5000)
    expect(api.alarms).toHaveBeenCalledTimes(1)
  })
  it('读请求未完成时不重叠，离开页签后释放定时器', async () => {
    api.alarms.mockImplementation(() => new Promise(() => {}))
    page()
    await flushPromises()
    await vi.advanceTimersByTimeAsync(15000)
    expect(api.alarms).toHaveBeenCalledTimes(1)
    wrapper.unmount()
    await vi.advanceTimersByTimeAsync(15000)
    expect(api.alarms).toHaveBeenCalledTimes(1)
    expect(api.close).toHaveBeenCalled()
  })
  it('翻页时保留游标页，用户刷新才回首页', async () => {
    api.alarms.mockResolvedValueOnce({
      status: 'READY',
      items: [{ id: 'alarm-a' }],
      hasMore: true,
      nextCursor: 'next-a'
    })
    page()
    await flushPromises()
    await click('下一页')
    await vi.advanceTimersByTimeAsync(5000)
    expect(api.alarms).toHaveBeenCalledTimes(2)
    expect(api.alarms.mock.calls[1][3]).toBe('next-a')
    await click('刷新告警')
    expect(api.alarms.mock.calls[2][3]).toBeUndefined()
  })
})
