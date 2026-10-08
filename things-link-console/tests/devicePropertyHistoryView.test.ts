import { afterEach, expect, it, vi } from 'vitest'
import { defineComponent, h } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
const api = vi.hoisted(() => ({ fetchDevicePropertyHistory: vi.fn() }))
vi.mock('@/api/device-property-history', () => api)
import DevicePropertyHistory from '@/views/device/components/DevicePropertyHistory.vue'
const item = {
  deviceId: 'device',
  propertyKey: 'payload',
  dataType: 'OBJECT',
  ts: '2026-10-07T00:00:00Z',
  modelVersion: '1.0.0',
  thingModelVersionId: 'model',
  quality: 0,
  valueText: '{"n":12345678901234567890123456789012345678,"text":"<script>bad</script>"}'
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
it('原始值按纯文本保真展示，关闭宿主清旧内容；空态与错误分离', async () => {
  api.fetchDevicePropertyHistory.mockResolvedValue({
    items: [item],
    nextCursor: undefined,
    hasMore: false
  })
  wrapper = mount(DevicePropertyHistory, {
    props: { projectId: 'project', deviceId: 'device', active: true },
    global: {
      directives: { loading: () => {} },
      stubs: {
        ElButton: button,
        ElForm: { template: '<form><slot/></form>' },
        ElFormItem: { template: '<div><slot/></div>' },
        ElInput: true,
        ElTable: table,
        ElTableColumn: column,
        ElEmpty: { props: ['description'], template: '<p>{{description}}</p>' },
        ElAlert: { props: ['title'], template: '<p>{{title}}</p>' }
      }
    }
  })
  await flushPromises()
  expect(wrapper.get('pre').text()).toContain('12345678901234567890123456789012345678')
  expect(wrapper.get('pre').text()).toContain('<script>bad</script>')
  expect(wrapper.find('script').exists()).toBe(false)
  expect(wrapper.text()).toContain('查询范围受当前套餐可读历史窗口限制')
  await wrapper.setProps({ active: false })
  expect(wrapper.find('pre').exists()).toBe(false)
  api.fetchDevicePropertyHistory.mockResolvedValue({
    items: [],
    nextCursor: undefined,
    hasMore: false
  })
  await wrapper.setProps({ active: true })
  await flushPromises()
  expect(wrapper.text()).toContain('当前筛选与套餐可读窗口内暂无数据')
  api.fetchDevicePropertyHistory.mockRejectedValue(new Error('private'))
  await wrapper.get('[data-testid="device-property-history-refresh"]').trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('原始属性历史不可用')
  expect(wrapper.text()).not.toContain('private')
})
