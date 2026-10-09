import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { defineComponent, h, reactive } from 'vue'
import { flushPromises, mount } from '@vue/test-utils'
import { ElTabs, ElTabPane } from 'element-plus'
const api = vi.hoisted(() => ({ fetchDeviceTasks: vi.fn() }))
vi.mock('@/api/device-tasks', () => api)
const user = reactive({ info: { userId: 'actor', tenantId: 'home' } })
vi.mock('@/store/modules/user', () => ({ useUserStore: () => user }))
import DeviceTasks from '@/views/device/components/DeviceTasks.vue'
const item = {
  id: 'task-1',
  name: '任务一',
  status: 'PAUSED',
  version: 2,
  targetType: 'ALL_DEVICES',
  targetGroupId: null,
  commandKey: 'restart',
  createdAt: '2026-09-30T12:00:00Z'
}
const history = {
  id: 'execution-1',
  jobId: 'task-1',
  triggerType: 'MANUAL',
  executionStatus: 'RUNNING',
  targetStatus: 'ACCEPTED',
  commandKey: 'original-command',
  startedAt: '2026-09-30T12:00:00Z'
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
  api.fetchDeviceTasks.mockResolvedValue({ items: [item], nextCursor: null })
})
afterEach(() => wrapper?.unmount())
function page() {
  wrapper = mount(DeviceTasks, {
    props: { projectId: 'project-a', deviceId: 'device-a' },
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
  if (['当前任务', '执行历史'].includes(label)) {
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
describe('设备任务', () => {
  it('以服务端游标往返翻页，刷新回首页，不查询全项目后筛选', async () => {
    api.fetchDeviceTasks.mockResolvedValueOnce({
      items: [item],
      nextCursor: 'signed-next'
    })
    page()
    await flushPromises()
    await click('下一页')
    expect(api.fetchDeviceTasks.mock.calls[1].slice(0, 4)).toEqual([
      'project-a',
      'device-a',
      'jobs',
      'signed-next'
    ])
    expect(wrapper.text()).toContain('第 2 页')
    await click('上一页')
    expect(api.fetchDeviceTasks.mock.calls[2][3]).toBeUndefined()
    await click('执行历史')
    await click('当前任务')
    expect(wrapper.text()).toContain('第 1 页')
    expect(wrapper.text()).toContain('当前目标包含该设备的任务')
  })
  it('换设备取消旧请求，旧响应不能落到新设备详情', async () => {
    let finish!: (v: unknown) => void
    api.fetchDeviceTasks.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve
        })
    )
    page()
    const signal = api.fetchDeviceTasks.mock.calls[0][4] as AbortSignal
    api.fetchDeviceTasks.mockResolvedValue({
      items: [{ ...item, id: 'new-task' }]
    })
    await wrapper.setProps({ deviceId: 'device-b' })
    await flushPromises()
    expect(signal.aborted).toBe(true)
    finish({ items: [item] })
    await flushPromises()
    expect(wrapper.text()).toContain('new-task')
    expect(wrapper.text()).not.toContain('task-1')
  })
  it('切换账号后非法任务状态不显示为空任务', async () => {
    page()
    await flushPromises()
    api.fetchDeviceTasks.mockResolvedValue({
      items: [{ ...item, status: 'UNKNOWN' }]
    })
    user.info.userId = 'next-actor'
    await flushPromises()
    expect(wrapper.text()).toContain('任务关系不可用')
    expect(wrapper.text()).not.toContain('暂无可查询')
    expect(wrapper.text()).not.toContain('UNKNOWN')
  })
  it('切换项目刷新关系，失败仍可恢复读取', async () => {
    page()
    await flushPromises()
    api.fetchDeviceTasks.mockRejectedValueOnce(new Error('unavailable'))
    await wrapper.setProps({ projectId: 'project-b' })
    await flushPromises()
    expect(wrapper.text()).toContain('设备任务读取失败')
    api.fetchDeviceTasks.mockResolvedValue({ items: [] })
    await click('执行历史')
    await click('当前任务')
    expect(wrapper.text()).toContain('暂无当前关联任务')
    expect(wrapper.text()).not.toContain('设备任务读取失败')
  })
  it('离开页面取消正在读取的关系', async () => {
    api.fetchDeviceTasks.mockImplementation(() => new Promise(() => {}))
    page()
    const signal = api.fetchDeviceTasks.mock.calls[0][4] as AbortSignal
    wrapper.unmount()
    expect(signal.aborted).toBe(true)
  })
  it('配置与历史切换重置游标，并且丢弃旧配置响应', async () => {
    let finish!: (v: unknown) => void
    api.fetchDeviceTasks.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          finish = resolve
        })
    )
    page()
    const signal = api.fetchDeviceTasks.mock.calls[0][4] as AbortSignal
    api.fetchDeviceTasks.mockResolvedValue({ items: [history], nextCursor: null })
    await click('执行历史')
    expect(signal.aborted).toBe(true)
    expect(api.fetchDeviceTasks.mock.calls[1].slice(0, 4)).toEqual([
      'project-a',
      'device-a',
      'history',
      undefined
    ])
    finish({ items: [item], nextCursor: 'old-cursor' })
    await flushPromises()
    expect(wrapper.text()).toContain('execution-1')
    expect(wrapper.text()).not.toContain('任务一')
    expect(wrapper.text()).toContain('整体执行状态与该设备结果分别展示')
    api.fetchDeviceTasks.mockResolvedValue({ items: [] })
    await click('当前任务')
    expect(wrapper.find('.device-detail-pagination').exists()).toBe(false)
    expect(api.fetchDeviceTasks.mock.calls.at(-1)?.[3]).toBeUndefined()
  })
})

it('空列表隐藏分页，刷新取得数据后恢复分页', async () => {
  api.fetchDeviceTasks.mockResolvedValue({ items: [], nextCursor: null })
  page()
  await flushPromises()
  expect(wrapper.find('.device-detail-pagination').exists()).toBe(false)
  api.fetchDeviceTasks.mockResolvedValue({ items: [item], nextCursor: null })
  await click('执行历史')
  await click('当前任务')
  expect(wrapper.find('.device-detail-pagination').exists()).toBe(true)
})
it('只保留当前任务和执行历史两个标签', async () => {
  page()
  await flushPromises()
  api.fetchDeviceTasks.mockResolvedValue({ items: [history], nextCursor: null })
  await click('执行历史')
  expect(api.fetchDeviceTasks.mock.lastCall?.[2]).toBe('history')
  expect(wrapper.get('#tab-history').attributes('aria-selected')).toBe('true')
  expect(wrapper.findAll('[role="tab"]')).toHaveLength(2)
  expect(wrapper.find('#tab-refresh').exists()).toBe(false)
  expect(wrapper.text()).toContain('execution-1')
})
