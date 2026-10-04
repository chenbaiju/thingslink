import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h, reactive } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
const api = vi.hoisted(() => ({ fetchDeviceCommandHistory: vi.fn() }))
vi.mock('@/api/device-command-history', () => api)
const user = reactive({ info: { userId: 'actor', tenantId: 'home' } })
vi.mock('@/store/modules/user', () => ({ useUserStore: () => user }))
import DeviceCommandHistory from '@/views/device/components/DeviceCommandHistory.vue'
const item = {
  id: 'history-1',
  deviceId: 'device-a',
  operationType: 'COMMAND',
  commandKey: 'restart',
  status: 'ACCEPTED',
  attemptCount: 1,
  maxAttempts: 3,
  acceptedAt: '2026-09-30T12:00:00Z'
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
      h('section', p.data.length ? JSON.stringify(p.data) : slots.empty?.())
})
let wrapper: ReturnType<typeof mount>
beforeEach(() => {
  vi.clearAllMocks()
  user.info.userId = 'actor'
  api.fetchDeviceCommandHistory.mockResolvedValue({ items: [item], nextCursor: null })
})
afterEach(() => wrapper?.unmount())
function page() {
  wrapper = mount(DeviceCommandHistory, {
    props: { projectId: 'project-a', deviceId: 'device-a' },
    global: {
      directives: { loading: () => {} },
      stubs: {
        ElButton: button,
        ElTable: table,
        ElTableColumn: true,
        ElEmpty: { props: ['description'], template: '<p>{{description}}</p>' },
        ElAlert: { props: ['title'], template: '<p>{{title}}</p>' }
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
describe('设备命令历史', () => {
  it('以服务端游标往返翻页，刷新回首页，不查询全项目后筛选', async () => {
    api.fetchDeviceCommandHistory.mockResolvedValueOnce({
      items: [item],
      nextCursor: 'signed-next'
    })
    page()
    await flushPromises()
    await click('下一页')
    expect(api.fetchDeviceCommandHistory.mock.calls[1].slice(0, 3)).toEqual([
      'project-a',
      'device-a',
      'signed-next'
    ])
    expect(wrapper.text()).toContain('第 2 页')
    await click('上一页')
    expect(api.fetchDeviceCommandHistory.mock.calls[2][2]).toBeUndefined()
    await click('刷新历史')
    expect(wrapper.text()).toContain('第 1 页')
    expect(wrapper.text()).toContain('不代表设备执行成功')
  })
  it('换设备取消旧请求，旧响应不能落到新设备详情', async () => {
    let finish!: (v: unknown) => void
    api.fetchDeviceCommandHistory.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve
        })
    )
    page()
    const signal = api.fetchDeviceCommandHistory.mock.calls[0][3] as AbortSignal
    api.fetchDeviceCommandHistory.mockResolvedValue({
      items: [{ ...item, id: 'new-history', deviceId: 'device-b' }]
    })
    await wrapper.setProps({ deviceId: 'device-b' })
    await flushPromises()
    expect(signal.aborted).toBe(true)
    finish({ items: [item] })
    await flushPromises()
    expect(wrapper.text()).toContain('new-history')
    expect(wrapper.text()).not.toContain('history-1')
  })
  it('异账号、跨设备或损坏响应不显示为无命令', async () => {
    page()
    await flushPromises()
    api.fetchDeviceCommandHistory.mockResolvedValue({
      items: [{ ...item, deviceId: 'foreign-device' }]
    })
    user.info.userId = 'next-actor'
    await flushPromises()
    expect(wrapper.text()).toContain('历史状态不可用')
    expect(wrapper.text()).not.toContain('暂无可查询')
    expect(wrapper.text()).not.toContain('foreign-device')
  })
  it('新命令标识触发历史刷新，失败仍可恢复读取', async () => {
    page()
    await flushPromises()
    api.fetchDeviceCommandHistory.mockRejectedValueOnce(new Error('unavailable'))
    await wrapper.setProps({ refreshKey: 'new-command' })
    await flushPromises()
    expect(wrapper.text()).toContain('命令历史读取失败')
    api.fetchDeviceCommandHistory.mockResolvedValue({ items: [] })
    await click('刷新历史')
    expect(wrapper.text()).toContain('暂无可查询的命令历史')
    expect(wrapper.text()).not.toContain('命令历史读取失败')
  })
  it('离开页面取消正在读取的历史', async () => {
    api.fetchDeviceCommandHistory.mockImplementation(() => new Promise(() => {}))
    page()
    const signal = api.fetchDeviceCommandHistory.mock.calls[0][3] as AbortSignal
    wrapper.unmount()
    expect(signal.aborted).toBe(true)
  })
})
