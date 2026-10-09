import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h, reactive } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { ElTabs, ElTabPane } from 'element-plus'
const api = vi.hoisted(() => ({ fetchDeviceScenes: vi.fn() }))
vi.mock('@/api/device-scenes', () => api)
const user = reactive({ info: { userId: 'actor', tenantId: 'home' } })
vi.mock('@/store/modules/user', () => ({ useUserStore: () => user }))
import DeviceScenes from '@/views/device/components/DeviceScenes.vue'
const item = {
  id: 'scene-1',
  name: '场景一',
  status: 'PAUSED',
  activeVersionId: 'version-1',
  versionNumber: 2,
  relationScope: 'PROJECT_CANDIDATE',
  usesConditionInput: false,
  hasDeviceAction: true,
  createdAt: '2026-09-30T12:00:00Z'
}
const history = {
  id: 'execution-1',
  sceneId: 'scene-1',
  sceneVersionId: 'version-1',
  status: 'DISPATCHED',
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
  api.fetchDeviceScenes.mockResolvedValue({ items: [item], nextCursor: null })
})
afterEach(() => wrapper?.unmount())
function page(canManage = true) {
  wrapper = mount(DeviceScenes, {
    props: { projectId: 'project-a', deviceId: 'device-a', canManage },
    global: {
      components: { ElTabs, ElTabPane },
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
  if (['项目场景候选', '执行历史'].includes(label)) {
    await wrapper
      .findAll('[role="tab"]')
      .find((tab) => tab.text() === label)!
      .trigger('click')
    await flushPromises()
    return
  }
  await wrapper
    .findAll('button')
    .find((b) => b.text() === label)!
    .trigger('click')
  await flushPromises()
}
describe('设备场景', () => {
  it('以服务端游标往返翻页，刷新回首页，不查询全项目后筛选', async () => {
    api.fetchDeviceScenes.mockResolvedValueOnce({
      items: [item],
      nextCursor: 'signed-next'
    })
    page()
    await flushPromises()
    await click('下一页')
    expect(api.fetchDeviceScenes.mock.calls[1].slice(0, 4)).toEqual([
      'project-a',
      'device-a',
      'definitions',
      'signed-next'
    ])
    expect(wrapper.text()).toContain('第 2 页')
    await click('上一页')
    expect(api.fetchDeviceScenes.mock.calls[2][3]).toBeUndefined()
    await click('执行历史')
    await click('项目场景候选')
    expect(wrapper.text()).toContain('第 1 页')
    expect(wrapper.text()).toContain('项目场景候选未绑定此设备')
  })
  it('换设备取消旧请求，旧响应不能落到新设备详情', async () => {
    let finish!: (v: unknown) => void
    api.fetchDeviceScenes.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve
        })
    )
    page()
    const signal = api.fetchDeviceScenes.mock.calls[0][4] as AbortSignal
    api.fetchDeviceScenes.mockResolvedValue({
      items: [{ ...item, id: 'new-scene' }]
    })
    await wrapper.setProps({ deviceId: 'device-b' })
    await flushPromises()
    expect(signal.aborted).toBe(true)
    finish({ items: [item] })
    await flushPromises()
    expect(wrapper.text()).toContain('new-scene')
    expect(wrapper.text()).not.toContain('scene-1')
  })
  it('切换账号后非法场景状态不显示为空候选', async () => {
    page()
    await flushPromises()
    api.fetchDeviceScenes.mockResolvedValue({
      items: [{ ...item, status: 'UNKNOWN' }]
    })
    user.info.userId = 'next-actor'
    await flushPromises()
    expect(wrapper.text()).toContain('场景关系不可用')
    expect(wrapper.text()).not.toContain('暂无可查询')
    expect(wrapper.text()).not.toContain('UNKNOWN')
  })
  it('切换项目刷新关系，失败仍可恢复读取', async () => {
    page()
    await flushPromises()
    api.fetchDeviceScenes.mockRejectedValueOnce(new Error('unavailable'))
    await wrapper.setProps({ projectId: 'project-b' })
    await flushPromises()
    expect(wrapper.text()).toContain('场景读取失败')
    api.fetchDeviceScenes.mockResolvedValue({ items: [] })
    await click('执行历史')
    await click('项目场景候选')
    expect(wrapper.text()).toContain('暂无当前发布的项目场景候选')
    expect(wrapper.text()).not.toContain('场景读取失败')
  })
  it('拒绝未明确项目候选范围的响应，避免显示为设备绑定', async () => {
    api.fetchDeviceScenes.mockResolvedValue({ items: [{ ...item, relationScope: 'DEVICE_BOUND' }] })
    page()
    await flushPromises()
    expect(wrapper.text()).toContain('场景关系不可用')
    expect(wrapper.text()).not.toContain('DEVICE_BOUND')
  })
  it('离开页面取消正在读取的关系', async () => {
    api.fetchDeviceScenes.mockImplementation(() => new Promise(() => {}))
    page()
    const signal = api.fetchDeviceScenes.mock.calls[0][4] as AbortSignal
    wrapper.unmount()
    expect(signal.aborted).toBe(true)
  })
  it('配置与历史切换重置游标，并且丢弃旧配置响应', async () => {
    let finish!: (v: unknown) => void
    api.fetchDeviceScenes.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve
        })
    )
    page()
    const signal = api.fetchDeviceScenes.mock.calls[0][4] as AbortSignal
    api.fetchDeviceScenes.mockResolvedValue({ items: [history], nextCursor: null })
    await click('执行历史')
    expect(signal.aborted).toBe(true)
    expect(api.fetchDeviceScenes.mock.calls[1].slice(0, 4)).toEqual([
      'project-a',
      'device-a',
      'history',
      undefined
    ])
    finish({ items: [item], nextCursor: 'old-cursor' })
    await flushPromises()
    expect(wrapper.text()).toContain('execution-1')
    expect(wrapper.text()).not.toContain('场景一')
    expect(wrapper.text()).toContain('动作意图受理及设备动作记录数均不代表设备物理执行成功')
    api.fetchDeviceScenes.mockResolvedValue({ items: [] })
    await click('项目场景候选')
    expect(wrapper.find('.device-detail-pagination').exists()).toBe(false)
    expect(api.fetchDeviceScenes.mock.calls.at(-1)?.[3]).toBeUndefined()
  })
  it('非管理者仅请求历史，管理权限撤回后取消配置读取', async () => {
    api.fetchDeviceScenes.mockResolvedValue({ items: [history] })
    page(false)
    await flushPromises()
    expect(api.fetchDeviceScenes.mock.calls[0][2]).toBe('history')
    expect(wrapper.findAll('[role="tab"]').map((tab) => tab.text())).toEqual(['执行历史'])
    await wrapper.setProps({ canManage: true })
    await flushPromises()
    let finish!: (v: unknown) => void
    api.fetchDeviceScenes.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve
        })
    )
    await click('项目场景候选')
    const signal = api.fetchDeviceScenes.mock.calls.at(-1)![4] as AbortSignal
    api.fetchDeviceScenes.mockResolvedValue({ items: [history] })
    await wrapper.setProps({ canManage: false })
    await flushPromises()
    expect(signal.aborted).toBe(true)
    finish({ items: [item] })
    await flushPromises()
    expect(wrapper.text()).not.toContain('场景一')
    expect(wrapper.text()).toContain('execution-1')
    expect(api.fetchDeviceScenes.mock.calls.at(-1)![2]).toBe('history')
  })
})

it('空列表隐藏分页，刷新取得数据后恢复分页', async () => {
  api.fetchDeviceScenes.mockResolvedValue({ items: [], nextCursor: null })
  page()
  await flushPromises()
  expect(wrapper.find('.device-detail-pagination').exists()).toBe(false)
  api.fetchDeviceScenes.mockResolvedValue({ items: [item], nextCursor: null })
  await click('执行历史')
  await click('项目场景候选')
  expect(wrapper.find('.device-detail-pagination').exists()).toBe(true)
})

it('切换入口采用标签样式且没有刷新入口', async () => {
  page()
  await flushPromises()
  expect(wrapper.findAll('[role="tab"]').map((tab) => tab.text())).toEqual([
    '项目场景候选',
    '执行历史'
  ])
  expect(wrapper.text()).not.toContain('刷新场景')
})
