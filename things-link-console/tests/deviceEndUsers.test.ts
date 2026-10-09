import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h, reactive } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
const api = vi.hoisted(() => ({ fetchDeviceEndUsers: vi.fn() }))
vi.mock('@/api/device-end-users', () => api)
const user = reactive({ info: { userId: 'actor', tenantId: 'home' } })
vi.mock('@/store/modules/user', () => ({ useUserStore: () => user }))
import DeviceEndUsers from '@/views/device/components/DeviceEndUsers.vue'
const item = {
  id: 'relation-1',
  appUserId: 'end-user-1',
  displayName: null,
  relationRole: 'READ_ONLY',
  createdAt: '2026-09-30T12:00:00Z'
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
  api.fetchDeviceEndUsers.mockResolvedValue({ items: [item], nextCursor: null })
})
afterEach(() => wrapper?.unmount())
function page() {
  wrapper = mount(DeviceEndUsers, {
    props: { projectId: 'project-a', deviceId: 'device-a' },
    global: {
      directives: { loading: () => {} },
      stubs: {
        ElButton: button,
        ElTable: table,
        ElTableColumn: true,
        ElEmpty: { props: ['description'], template: '<p>{{description}}</p>' },
        ElAlert: { props: ['title'], template: '<p>{{title}}<slot /></p>' }
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
describe('设备终端用户', () => {
  it('以服务端游标往返翻页，刷新回首页，不查询全项目后筛选', async () => {
    api.fetchDeviceEndUsers.mockResolvedValueOnce({
      items: [item],
      nextCursor: 'signed-next'
    })
    page()
    await flushPromises()
    await click('下一页')
    expect(api.fetchDeviceEndUsers.mock.calls[1].slice(0, 3)).toEqual([
      'project-a',
      'device-a',
      'signed-next'
    ])
    expect(wrapper.text()).toContain('第 2 页')
    await click('上一页')
    expect(api.fetchDeviceEndUsers.mock.calls[2][2]).toBeUndefined()
    await click('刷新用户')
    expect(wrapper.text()).toContain('第 1 页')
    expect(wrapper.text()).toContain('仅列出账号、项目角色和设备关系均有效')
  })
  it('换设备取消旧请求，旧响应不能落到新设备详情', async () => {
    let finish!: (v: unknown) => void
    api.fetchDeviceEndUsers.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve
        })
    )
    page()
    const signal = api.fetchDeviceEndUsers.mock.calls[0][3] as AbortSignal
    api.fetchDeviceEndUsers.mockResolvedValue({
      items: [{ ...item, id: 'new-relation' }]
    })
    await wrapper.setProps({ deviceId: 'device-b' })
    await flushPromises()
    expect(signal.aborted).toBe(true)
    finish({ items: [item] })
    await flushPromises()
    expect(wrapper.text()).toContain('new-relation')
    expect(wrapper.text()).not.toContain('relation-1')
  })
  it('切换账号后非法关系角色不显示为无用户', async () => {
    page()
    await flushPromises()
    api.fetchDeviceEndUsers.mockResolvedValue({
      items: [{ ...item, relationRole: 'UNKNOWN' }]
    })
    user.info.userId = 'next-actor'
    await flushPromises()
    expect(wrapper.text()).toContain('用户关系不可用')
    expect(wrapper.text()).not.toContain('暂无可查询')
    expect(wrapper.text()).not.toContain('UNKNOWN')
  })
  it('切换项目刷新关系，失败仍可恢复读取', async () => {
    page()
    await flushPromises()
    api.fetchDeviceEndUsers.mockRejectedValueOnce(new Error('unavailable'))
    await wrapper.setProps({ projectId: 'project-b' })
    await flushPromises()
    expect(wrapper.text()).toContain('终端用户读取失败')
    api.fetchDeviceEndUsers.mockResolvedValue({ items: [] })
    await click('刷新用户')
    expect(wrapper.text()).toContain('暂无当前有效终端用户')
    expect(wrapper.text()).not.toContain('终端用户读取失败')
  })
  it('离开页面取消正在读取的关系', async () => {
    api.fetchDeviceEndUsers.mockImplementation(() => new Promise(() => {}))
    page()
    const signal = api.fetchDeviceEndUsers.mock.calls[0][3] as AbortSignal
    wrapper.unmount()
    expect(signal.aborted).toBe(true)
  })
})

it('空列表隐藏分页，刷新取得数据后恢复分页', async () => {
  api.fetchDeviceEndUsers.mockResolvedValue({ items: [], nextCursor: null })
  page()
  await flushPromises()
  expect(wrapper.find('.device-detail-pagination').exists()).toBe(false)
  api.fetchDeviceEndUsers.mockResolvedValue({ items: [item], nextCursor: null })
  await click('刷新用户')
  expect(wrapper.find('.device-detail-pagination').exists()).toBe(true)
})
