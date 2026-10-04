import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h, reactive } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
const api = vi.hoisted(() => ({ fetchDeviceAutomations: vi.fn() }))
vi.mock('@/api/device-automations', () => api)
const user = reactive({ info: { userId: 'actor', tenantId: 'home' } })
vi.mock('@/store/modules/user', () => ({ useUserStore: () => user }))
import DeviceAutomations from '@/views/device/components/DeviceAutomations.vue'
const item = {
  id: 'automation-1',
  name: '自动化一',
  status: 'PAUSED',
  activeVersionId: 'version-1',
  versionNumber: 2,
  triggerType: 'ONE_SHOT',
  usesConditionInput: false,
  hasDeviceAction: true,
  createdAt: '2026-09-30T12:00:00Z'
}
const history = {
  id: 'execution-1',
  automationId: 'automation-1',
  automationVersionId: 'version-1',
  triggerType: 'ONE_SHOT',
  status: 'DISPATCHED',
  attemptCount: 1,
  deviceActionRecordCount: 0,
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
  api.fetchDeviceAutomations.mockResolvedValue({ items: [item], nextCursor: null })
})
afterEach(() => wrapper?.unmount())
function page(canManage = true) {
  wrapper = mount(DeviceAutomations, {
    props: { projectId: 'project-a', deviceId: 'device-a', canManage },
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
describe('设备自动化', () => {
  it('以服务端游标往返翻页，刷新回首页，不查询全项目后筛选', async () => {
    api.fetchDeviceAutomations.mockResolvedValueOnce({
      items: [item],
      nextCursor: 'signed-next'
    })
    page()
    await flushPromises()
    await click('下一页')
    expect(api.fetchDeviceAutomations.mock.calls[1].slice(0, 4)).toEqual([
      'project-a',
      'device-a',
      'definitions',
      'signed-next'
    ])
    expect(wrapper.text()).toContain('第 2 页')
    await click('上一页')
    expect(api.fetchDeviceAutomations.mock.calls[2][3]).toBeUndefined()
    await click('刷新自动化')
    expect(wrapper.text()).toContain('第 1 页')
    expect(wrapper.text()).toContain('仅显示当前发布版本的设备关系')
  })
  it('换设备取消旧请求，旧响应不能落到新设备详情', async () => {
    let finish!: (v: unknown) => void
    api.fetchDeviceAutomations.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve
        })
    )
    page()
    const signal = api.fetchDeviceAutomations.mock.calls[0][4] as AbortSignal
    api.fetchDeviceAutomations.mockResolvedValue({
      items: [{ ...item, id: 'new-automation' }]
    })
    await wrapper.setProps({ deviceId: 'device-b' })
    await flushPromises()
    expect(signal.aborted).toBe(true)
    finish({ items: [item] })
    await flushPromises()
    expect(wrapper.text()).toContain('new-automation')
    expect(wrapper.text()).not.toContain('automation-1')
  })
  it('切换账号后非法任务状态不显示为空任务', async () => {
    page()
    await flushPromises()
    api.fetchDeviceAutomations.mockResolvedValue({
      items: [{ ...item, status: 'UNKNOWN' }]
    })
    user.info.userId = 'next-actor'
    await flushPromises()
    expect(wrapper.text()).toContain('自动化关系不可用')
    expect(wrapper.text()).not.toContain('暂无可查询')
    expect(wrapper.text()).not.toContain('UNKNOWN')
  })
  it('切换项目刷新关系，失败仍可恢复读取', async () => {
    page()
    await flushPromises()
    api.fetchDeviceAutomations.mockRejectedValueOnce(new Error('unavailable'))
    await wrapper.setProps({ projectId: 'project-b' })
    await flushPromises()
    expect(wrapper.text()).toContain('自动化读取失败')
    api.fetchDeviceAutomations.mockResolvedValue({ items: [] })
    await click('刷新自动化')
    expect(wrapper.text()).toContain('暂无当前发布的关联自动化')
    expect(wrapper.text()).not.toContain('自动化读取失败')
  })
  it('离开页面取消正在读取的关系', async () => {
    api.fetchDeviceAutomations.mockImplementation(() => new Promise(() => {}))
    page()
    const signal = api.fetchDeviceAutomations.mock.calls[0][4] as AbortSignal
    wrapper.unmount()
    expect(signal.aborted).toBe(true)
  })
  it('配置与历史切换重置游标，并且丢弃旧配置响应', async () => {
    let finish!: (v: unknown) => void
    api.fetchDeviceAutomations.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve
        })
    )
    page()
    const signal = api.fetchDeviceAutomations.mock.calls[0][4] as AbortSignal
    api.fetchDeviceAutomations.mockResolvedValue({ items: [history], nextCursor: null })
    await click('执行历史')
    expect(signal.aborted).toBe(true)
    expect(api.fetchDeviceAutomations.mock.calls[1].slice(0, 4)).toEqual([
      'project-a',
      'device-a',
      'history',
      undefined
    ])
    finish({ items: [item], nextCursor: 'old-cursor' })
    await flushPromises()
    expect(wrapper.text()).toContain('execution-1')
    expect(wrapper.text()).not.toContain('自动化一')
    expect(wrapper.text()).toContain('动作意图受理及设备动作记录数均不代表设备物理执行成功')
    api.fetchDeviceAutomations.mockResolvedValue({ items: [] })
    await click('当前自动化')
    expect(wrapper.text()).toContain('第 1 页')
    expect(api.fetchDeviceAutomations.mock.calls.at(-1)?.[3]).toBeUndefined()
  })
  it('非管理者仅请求历史，管理权限撤回后取消配置读取', async () => {
    api.fetchDeviceAutomations.mockResolvedValue({ items: [history] })
    page(false)
    await flushPromises()
    expect(api.fetchDeviceAutomations.mock.calls[0][2]).toBe('history')
    expect(wrapper.findAll('button').some((b) => b.text() === '当前自动化')).toBe(false)
    await wrapper.setProps({ canManage: true })
    await flushPromises()
    let finish!: (v: unknown) => void
    api.fetchDeviceAutomations.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve
        })
    )
    await click('当前自动化')
    const signal = api.fetchDeviceAutomations.mock.calls.at(-1)![4] as AbortSignal
    api.fetchDeviceAutomations.mockResolvedValue({ items: [history] })
    await wrapper.setProps({ canManage: false })
    await flushPromises()
    expect(signal.aborted).toBe(true)
    finish({ items: [item] })
    await flushPromises()
    expect(wrapper.text()).not.toContain('自动化一')
    expect(wrapper.text()).toContain('execution-1')
    expect(api.fetchDeviceAutomations.mock.calls.at(-1)![2]).toBe('history')
  })
})
