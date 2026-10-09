import { afterEach, expect, it, vi } from 'vitest'
import { defineComponent, h } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
const api = vi.hoisted(() => ({ fetchOtaDeviceJobs: vi.fn() }))
vi.mock('@/api/ota', () => api)
import DeviceCapabilityContent from '@/views/device/components/DeviceCapabilityContent.vue'

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
let wrapper: ReturnType<typeof mount>
afterEach(() => wrapper?.unmount())
function setup() {
  wrapper = mount(DeviceCapabilityContent, {
    props: { projectId: 'project', device: { id: 'device' }, kind: 'ota' },
    global: {
      directives: { loading: () => {} },
      stubs: {
        ElButton: button,
        ElAlert: { template: '<div><slot /></div>' },
        ElTable: { template: '<div><slot /></div>' },
        ElTableColumn: true,
        ElEmpty: true
      }
    }
  })
}
it('固件列表按已访问游标上一页、下一页，末页禁用下一页，换设备重置页码', async () => {
  api.fetchOtaDeviceJobs.mockImplementation(async (_p, _d, { cursor }) => ({
    items: [{ id: cursor ?? 'first-job' }],
    nextCursor: cursor === 'page-3' ? undefined : cursor === 'page-2' ? 'page-3' : 'page-2',
    hasMore: cursor !== 'page-3'
  }))
  setup()
  await flushPromises()
  const pagination = () => wrapper.get('[aria-label="固件升级分页"]')
  const buttons = () => pagination().findAll('button')
  expect(buttons()[0].attributes('disabled')).toBeDefined()
  await buttons()[1].trigger('click')
  await flushPromises()
  await buttons()[1].trigger('click')
  await flushPromises()
  expect(pagination().text()).toContain('第 3 页')
  expect(buttons()[1].attributes('disabled')).toBeDefined()
  await buttons()[0].trigger('click')
  await flushPromises()
  expect(pagination().text()).toContain('第 2 页')
  expect(api.fetchOtaDeviceJobs.mock.lastCall?.[2]).toEqual({ cursor: 'page-2', limit: 50 })
  await buttons()[0].trigger('click')
  await flushPromises()
  expect(api.fetchOtaDeviceJobs.mock.lastCall?.[2].cursor).toBeUndefined()
  await buttons()[1].trigger('click')
  await flushPromises()
  await wrapper.setProps({ device: { id: 'other-device' } })
  await flushPromises()
  expect(pagination().text()).toContain('第 1 页')
  expect(api.fetchOtaDeviceJobs.mock.lastCall?.[1]).toBe('other-device')
  expect(api.fetchOtaDeviceJobs.mock.lastCall?.[2].cursor).toBeUndefined()
})
it('固件列表无数据时隐藏分页，有数据时恢复分页', async () => {
  api.fetchOtaDeviceJobs.mockResolvedValue({ items: [], hasMore: false })
  setup()
  await flushPromises()
  expect(wrapper.find('[aria-label="固件升级分页"]').exists()).toBe(false)
  api.fetchOtaDeviceJobs.mockResolvedValue({ items: [{ id: 'job' }], hasMore: false })
  await wrapper.setProps({ device: { id: 'device-with-jobs' } })
  await flushPromises()
  expect(wrapper.find('[aria-label="固件升级分页"]').exists()).toBe(true)
})
