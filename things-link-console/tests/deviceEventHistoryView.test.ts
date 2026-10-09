import { afterEach, expect, it, vi } from 'vitest'
import { defineComponent, h } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
const api = vi.hoisted(() => ({ fetchDeviceEvent: vi.fn(), fetchDeviceEvents: vi.fn() }))
vi.mock('@/api/device-event-history', () => api)
import DeviceEventHistory from '@/views/device/components/DeviceEventHistory.vue'
const item = {
  messageId: 'event',
  deviceId: 'device',
  eventKey: 'alarm',
  level: 'WARNING',
  modelVersion: '1.0.0',
  thingModelVersionId: 'model',
  eligibility: 'CURRENT',
  occurredAt: '2026-10-06T00:00:00Z',
  receivedAt: '2026-10-06T00:00:01Z',
  acceptedAt: '2026-10-06T00:00:02Z',
  paramsRedacted: true,
  paramsText: '{"n":12345678901234567890123456789012345678,"text":"<script>bad</script>"}'
}
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
const table = defineComponent({
  props: ['data'],
  setup:
    (p, { slots }) =>
    () =>
      h('div', p.data.length ? slots.default?.() : slots.empty?.())
})
const column = defineComponent({
  setup:
    (_, { slots }) =>
    () =>
      h('div', slots.default?.({ row: item }))
})
let wrapper: ReturnType<typeof mount>
afterEach(() => wrapper?.unmount())
it('详情是纯文本精确投影与脱敏说明，关闭宿主清旧内容；独立空态与错误', async () => {
  api.fetchDeviceEvents.mockResolvedValue({
    items: [item],
    nextCursor: null,
    windowFrom: 'from',
    windowTo: 'to',
    retentionDays: 90
  })
  api.fetchDeviceEvent.mockResolvedValue(item)
  wrapper = mount(DeviceEventHistory, {
    props: { projectId: 'project', deviceId: 'device', active: true },
    global: {
      directives: { loading: () => {} },
      stubs: {
        ElButton: button,
        ElForm: { template: '<form><slot/></form>' },
        ElFormItem: { template: '<div><slot/></div>' },
        ElInput: true,
        ElSelect: true,
        ElOption: true,
        ElTable: table,
        ElTableColumn: column,
        ElEmpty: { props: ['description'], template: '<p>{{description}}</p>' },
        ElAlert: { props: ['title'], template: '<p>{{title}}<slot /></p>' }
      }
    }
  })
  await flushPromises()
  expect(wrapper.find('[aria-label="事件历史分页"]').exists()).toBe(true)
  await wrapper.get('[data-testid="device-event-detail-open"]').trigger('click')
  await flushPromises()
  expect(wrapper.get('pre').text()).toContain('12345678901234567890123456789012345678')
  expect(wrapper.get('pre').text()).toContain('<script>bad</script>')
  expect(wrapper.find('script').exists()).toBe(false)
  expect(wrapper.text()).toContain('参数已按存储规则脱敏')
  await wrapper.setProps({ active: false })
  expect(wrapper.find('pre').exists()).toBe(false)
  api.fetchDeviceEvents.mockResolvedValue({
    items: [],
    nextCursor: null,
    windowFrom: 'from',
    windowTo: 'to',
    retentionDays: 90
  })
  await wrapper.setProps({ active: true })
  await flushPromises()
  expect(wrapper.text()).toContain('当前可读窗口内暂无事件')
  expect(wrapper.find('[aria-label="事件历史分页"]').exists()).toBe(false)
  api.fetchDeviceEvents.mockRejectedValue(new Error('private'))
  await wrapper.get('[data-testid="device-event-history-refresh"]').trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('读取不可用')
  expect(wrapper.text()).not.toContain('private')
})
