import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h, reactive } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
const api = vi.hoisted(() => ({ fetchDeviceMessageRules: vi.fn() }))
vi.mock('@/api/device-message-rules', () => api)
const user = reactive({ info: { userId: 'actor', tenantId: 'home' } })
vi.mock('@/store/modules/user', () => ({ useUserStore: () => user }))
import DeviceMessageRules from '@/views/device/components/DeviceMessageRules.vue'
const item = {
  id: 'rule-1',
  name: '消息规则一',
  status: 'PAUSED',
  activeVersionId: 'version-1',
  versionNumber: 2,
  relationScope: 'PROJECT_CANDIDATE',
  hasDeviceAction: true,
  createdAt: '2026-09-30T12:00:00Z'
}
const history = {
  id: 'execution-1',
  ruleId: 'rule-1',
  ruleVersionId: 'version-1',
  messageId: 'message-1',
  status: 'SUCCESS',
  attempt: 1,
  resultCode: 'SUCCESS',
  durationMillis: 10,
  inputBytes: 2,
  outputBytes: 2,
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
  api.fetchDeviceMessageRules.mockResolvedValue({ items: [item], nextCursor: null })
})
afterEach(() => wrapper?.unmount())
function page(canManage = true) {
  wrapper = mount(DeviceMessageRules, {
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
describe('设备消息规则', () => {
  it('以服务端游标往返翻页，刷新回首页，不查询全项目后筛选', async () => {
    api.fetchDeviceMessageRules.mockResolvedValueOnce({
      items: [item],
      nextCursor: 'signed-next'
    })
    page()
    await flushPromises()
    await click('下一页')
    expect(api.fetchDeviceMessageRules.mock.calls[1].slice(0, 4)).toEqual([
      'project-a',
      'device-a',
      'definitions',
      'signed-next'
    ])
    expect(wrapper.text()).toContain('第 2 页')
    await click('上一页')
    expect(api.fetchDeviceMessageRules.mock.calls[2][3]).toBeUndefined()
    await click('刷新消息规则')
    expect(wrapper.text()).toContain('第 1 页')
    expect(wrapper.text()).toContain('项目规则候选未绑定此设备')
  })
  it('换设备取消旧请求，旧响应不能落到新设备详情', async () => {
    let finish!: (v: unknown) => void
    api.fetchDeviceMessageRules.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve
        })
    )
    page()
    const signal = api.fetchDeviceMessageRules.mock.calls[0][4] as AbortSignal
    api.fetchDeviceMessageRules.mockResolvedValue({
      items: [{ ...item, id: 'new-rule' }]
    })
    await wrapper.setProps({ deviceId: 'device-b' })
    await flushPromises()
    expect(signal.aborted).toBe(true)
    finish({ items: [item] })
    await flushPromises()
    expect(wrapper.text()).toContain('new-rule')
    expect(wrapper.text()).not.toContain('rule-1')
  })
  it('切换账号后非法消息规则状态不显示为空候选', async () => {
    page()
    await flushPromises()
    api.fetchDeviceMessageRules.mockResolvedValue({
      items: [{ ...item, status: 'UNKNOWN' }]
    })
    user.info.userId = 'next-actor'
    await flushPromises()
    expect(wrapper.text()).toContain('消息规则关系不可用')
    expect(wrapper.text()).not.toContain('暂无可查询')
    expect(wrapper.text()).not.toContain('UNKNOWN')
  })
  it('切换项目刷新关系，失败仍可恢复读取', async () => {
    page()
    await flushPromises()
    api.fetchDeviceMessageRules.mockRejectedValueOnce(new Error('unavailable'))
    await wrapper.setProps({ projectId: 'project-b' })
    await flushPromises()
    expect(wrapper.text()).toContain('消息规则读取失败')
    api.fetchDeviceMessageRules.mockResolvedValue({ items: [] })
    await click('刷新消息规则')
    expect(wrapper.text()).toContain('暂无当前发布的项目消息规则候选')
    expect(wrapper.text()).not.toContain('消息规则读取失败')
  })
  it('拒绝未明确项目候选范围的响应，避免显示为设备绑定', async () => {
    api.fetchDeviceMessageRules.mockResolvedValue({
      items: [{ ...item, relationScope: 'DEVICE_BOUND' }]
    })
    page()
    await flushPromises()
    expect(wrapper.text()).toContain('消息规则关系不可用')
    expect(wrapper.text()).not.toContain('DEVICE_BOUND')
  })
  it('离开页面取消正在读取的关系', async () => {
    api.fetchDeviceMessageRules.mockImplementation(() => new Promise(() => {}))
    page()
    const signal = api.fetchDeviceMessageRules.mock.calls[0][4] as AbortSignal
    wrapper.unmount()
    expect(signal.aborted).toBe(true)
  })
  it('配置与历史切换重置游标，并且丢弃旧配置响应', async () => {
    let finish!: (v: unknown) => void
    api.fetchDeviceMessageRules.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve
        })
    )
    page()
    const signal = api.fetchDeviceMessageRules.mock.calls[0][4] as AbortSignal
    api.fetchDeviceMessageRules.mockResolvedValue({ items: [history], nextCursor: null })
    await click('执行尝试')
    expect(signal.aborted).toBe(true)
    expect(api.fetchDeviceMessageRules.mock.calls[1].slice(0, 4)).toEqual([
      'project-a',
      'device-a',
      'history',
      undefined
    ])
    finish({ items: [item], nextCursor: 'old-cursor' })
    await flushPromises()
    expect(wrapper.text()).toContain('execution-1')
    expect(wrapper.text()).not.toContain('消息规则一')
    expect(wrapper.text()).toContain('旧日志设备未知，不推测回填')
    api.fetchDeviceMessageRules.mockResolvedValue({ items: [] })
    await click('项目消息规则候选')
    expect(wrapper.text()).toContain('第 1 页')
    expect(api.fetchDeviceMessageRules.mock.calls.at(-1)?.[3]).toBeUndefined()
  })
  it('非管理者仅请求历史，管理权限撤回后取消配置读取', async () => {
    api.fetchDeviceMessageRules.mockResolvedValue({ items: [history] })
    page(false)
    await flushPromises()
    expect(api.fetchDeviceMessageRules.mock.calls[0][2]).toBe('history')
    expect(wrapper.findAll('button').some((b) => b.text() === '项目消息规则候选')).toBe(false)
    await wrapper.setProps({ canManage: true })
    await flushPromises()
    let finish!: (v: unknown) => void
    api.fetchDeviceMessageRules.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve
        })
    )
    await click('项目消息规则候选')
    const signal = api.fetchDeviceMessageRules.mock.calls.at(-1)![4] as AbortSignal
    api.fetchDeviceMessageRules.mockResolvedValue({ items: [history] })
    await wrapper.setProps({ canManage: false })
    await flushPromises()
    expect(signal.aborted).toBe(true)
    finish({ items: [item] })
    await flushPromises()
    expect(wrapper.text()).not.toContain('消息规则一')
    expect(wrapper.text()).toContain('execution-1')
    expect(api.fetchDeviceMessageRules.mock.calls.at(-1)![2]).toBe('history')
  })
  it('动作使用独立游标用途，空列表不冒充完整旧日志', async () => {
    page(false)
    await flushPromises()
    api.fetchDeviceMessageRules.mockResolvedValue({
      items: [
        {
          id: 'action-1',
          ruleId: 'rule-1',
          ruleVersionId: 'version-1',
          messageId: 'message-1',
          operationType: 'COMMAND',
          status: 'REJECTED',
          commandId: null,
          createdAt: item.createdAt
        }
      ]
    })
    await click('设备动作')
    expect(api.fetchDeviceMessageRules.mock.calls.at(-1)?.slice(2, 4)).toEqual([
      'actions',
      undefined
    ])
    expect(wrapper.text()).toContain('action-1')
    expect(wrapper.text()).toContain('投递终态不代替物理设备验收')
    api.fetchDeviceMessageRules.mockResolvedValue({ items: [] })
    await click('执行尝试')
    expect(wrapper.text()).toContain('暂无已知设备身份的执行尝试')
    expect(wrapper.text()).toContain('旧日志设备未知')
  })
})
