import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { computed, h, inject, provide, reactive } from 'vue'
import Panel from '@/views/alarm/history/AlarmNotificationDeliveries.vue'
import { fetchAlarmNotificationDeliveries } from '@/api/alarm'
import { invalidateIdentity } from '@/utils/http/identity-scope'
const state = vi.hoisted(() => ({ user: {} as any }))
vi.mock('@/store/modules/user', () => ({ useUserStore: () => state.user }))
vi.mock('@/api/alarm', () => ({ fetchAlarmNotificationDeliveries: vi.fn() }))
const projectId = '11111111-1111-4111-8111-111111111111',
  instanceId = '22222222-2222-4222-8222-222222222222',
  id = '33333333-3333-4333-8333-333333333333',
  otherId = '44444444-4444-4444-8444-444444444444',
  time = '2026-10-06T00:00:00Z'
const row = {
  id,
  instanceId,
  alarmEventId: otherId,
  channel: 'EMAIL' as const,
  target: '***',
  status: 'QUEUED' as const,
  attemptCount: 0,
  nextAttemptAt: time,
  createdAt: time,
  updatedAt: time
}
let panel: VueWrapper
function render() {
  panel = mount(Panel, {
    props: { projectId, instanceId },
    global: {
      stubs: {
        ElButton: {
          props: ['disabled'],
          template: '<button :disabled="disabled"><slot/></button>'
        },
        ElAlert: { props: ['title'], template: '<p>{{title}}</p>' },
        ElEmpty: { props: ['description'], template: '<p>{{description}}</p>' },
        ElTable: {
          props: ['data'],
          setup(props: any, { slots }: any) {
            provide(
              'rows',
              computed(() => props.data)
            )
            return () => h('div', props.data.length ? slots.default?.() : slots.empty?.())
          }
        },
        ElTableColumn: {
          props: ['prop'],
          setup(props: any, { slots }: any) {
            const rows = inject<any>('rows')
            return () =>
              h(
                'div',
                rows.value.map((row: any) =>
                  slots.default ? slots.default({ row }) : h('span', String(row[props.prop] ?? ''))
                )
              )
          }
        }
      }
    }
  })
}
function button(name: string) {
  return panel.findAll('button').find((b) => b.text() === name)!
}
beforeEach(() => {
  vi.resetAllMocks()
  state.user = reactive({ info: { userId: 'reader', tenantId: 'tenant', buttons: [] } })
  vi.mocked(fetchAlarmNotificationDeliveries).mockResolvedValue({
    items: [row],
    hasMore: false,
    nextCursor: null
  })
})
afterEach(() => panel?.unmount())
it.each([
  'QUEUED',
  'SENDING',
  'SUCCEEDED',
  'RETRY_SCHEDULED',
  'DEAD_LETTER',
  'SKIPPED_AUTHORIZATION',
  'SUPPRESSED_QUOTA',
  'TEMPLATE_INVALID'
])('按原合同展示状态%s且不存在重发/写接口', async (status) => {
  vi.mocked(fetchAlarmNotificationDeliveries).mockResolvedValue({
    items: [{ ...row, status: status as any }],
    hasMore: false
  })
  render()
  await flushPromises()
  expect(panel.text()).toContain(status)
  expect(panel.text()).toContain('执行成功不代表收件人或供应商已确认送达')
  expect(panel.findAll('button').map((b) => b.text())).toEqual(['刷新投递记录'])
  expect(fetchAlarmNotificationDeliveries).toHaveBeenCalledWith(
    projectId,
    instanceId,
    undefined,
    expect.any(AbortSignal)
  )
})
it('空集与权限读取失败分别展示，失败不能推断无投递', async () => {
  vi.mocked(fetchAlarmNotificationDeliveries).mockResolvedValue({ items: [], hasMore: false })
  render()
  await flushPromises()
  expect(panel.text()).toContain('此告警暂无投递记录')
  vi.mocked(fetchAlarmNotificationDeliveries).mockRejectedValueOnce({ status: 403 })
  await button('刷新投递记录').trigger('click')
  await flushPromises()
  expect(panel.text()).toContain('投递记录读取失败')
  expect(panel.text()).not.toContain('此告警暂无投递记录')
})
it.each([
  { ...row, target: 'PRIVATE@example.com' },
  { ...row, bodySnapshot: 'PRIVATE' },
  { ...row, instanceId: otherId },
  { ...row, status: 'unknown' },
  { ...row, attemptCount: -1 },
  { ...row, createdAt: 'invalid' }
])('拒绝错误实例、未脱敏或非法合同：%j', async (value) => {
  vi.mocked(fetchAlarmNotificationDeliveries).mockResolvedValue({
    items: [value as any],
    hasMore: false
  })
  render()
  await flushPromises()
  expect(panel.text()).toContain('投递记录读取失败')
  expect(panel.text()).not.toContain('PRIVATE')
  expect(panel.text()).not.toContain('（QUEUED）')
})
it('下一页始终过滤原告警、保留游标；重复点击只一次，刷新归第一页', async () => {
  vi.mocked(fetchAlarmNotificationDeliveries).mockResolvedValueOnce({
    items: [row],
    hasMore: true,
    nextCursor: 'opaque'
  })
  render()
  await flushPromises()
  let done!: (page: any) => void
  vi.mocked(fetchAlarmNotificationDeliveries).mockReturnValueOnce(
    new Promise((resolve) => {
      done = resolve
    })
  )
  await button('加载更多投递记录').trigger('click')
  await button('加载更多投递记录').trigger('click')
  expect(fetchAlarmNotificationDeliveries).toHaveBeenCalledTimes(2)
  expect(fetchAlarmNotificationDeliveries).toHaveBeenLastCalledWith(
    projectId,
    instanceId,
    'opaque',
    expect.any(AbortSignal)
  )
  done({ items: [{ ...row, id: otherId }], hasMore: false })
  await flushPromises()
  expect(panel.text()).toContain(id)
  expect(panel.text()).toContain(otherId)
  await button('刷新投递记录').trigger('click')
  await flushPromises()
  expect(fetchAlarmNotificationDeliveries).toHaveBeenLastCalledWith(
    projectId,
    instanceId,
    undefined,
    expect.any(AbortSignal)
  )
})
it('循环游标和跨页重复记录拒绝追加，但不丢已有事实', async () => {
  vi.mocked(fetchAlarmNotificationDeliveries).mockResolvedValueOnce({
    items: [row],
    hasMore: true,
    nextCursor: 'opaque'
  })
  render()
  await flushPromises()
  vi.mocked(fetchAlarmNotificationDeliveries).mockResolvedValueOnce({
    items: [row],
    hasMore: true,
    nextCursor: 'opaque'
  })
  await button('加载更多投递记录').trigger('click')
  await flushPromises()
  expect(panel.text()).toContain('投递记录读取失败')
  expect(panel.text()).toContain(id)
})
it('换告警取消旧读取并拒绝晚到旧响应', async () => {
  let done!: (page: any) => void
  vi.mocked(fetchAlarmNotificationDeliveries).mockReturnValueOnce(
    new Promise((resolve) => {
      done = resolve
    })
  )
  render()
  const signal = vi.mocked(fetchAlarmNotificationDeliveries).mock.calls[0][3]!
  vi.mocked(fetchAlarmNotificationDeliveries).mockResolvedValueOnce({ items: [], hasMore: false })
  await panel.setProps({ instanceId: otherId })
  await flushPromises()
  expect(signal.aborted).toBe(true)
  done({ items: [row], hasMore: false })
  await flushPromises()
  expect(panel.text()).not.toContain(id)
  expect(panel.text()).toContain('此告警暂无投递记录')
})
it('身份代次变化清旧事实，旧失败不影响新身份成功', async () => {
  render()
  await flushPromises()
  expect(panel.text()).toContain(id)
  vi.mocked(fetchAlarmNotificationDeliveries).mockResolvedValueOnce({ items: [], hasMore: false })
  invalidateIdentity()
  await flushPromises()
  expect(panel.text()).not.toContain(id)
})
it('关闭/卸载停止旧请求，不能回填', async () => {
  let done!: (page: any) => void
  vi.mocked(fetchAlarmNotificationDeliveries).mockReturnValueOnce(
    new Promise((resolve) => {
      done = resolve
    })
  )
  render()
  const signal = vi.mocked(fetchAlarmNotificationDeliveries).mock.calls[0][3]!
  panel.unmount()
  expect(signal.aborted).toBe(true)
  done({ items: [row], hasMore: false })
  await flushPromises()
})

it('没有下次计划的合法终态仍展示，空时间不是读取失败', async () => {
  vi.mocked(fetchAlarmNotificationDeliveries).mockResolvedValue({
    items: [{ ...row, status: 'TEMPLATE_INVALID', nextAttemptAt: null }],
    hasMore: false
  })
  render()
  await flushPromises()
  expect(panel.text()).toContain('TEMPLATE_INVALID')
  expect(panel.text()).not.toContain('投递记录读取失败')
})

it('非法上下文不能显示暂无投递或发出请求', async () => {
  render()
  await flushPromises()
  vi.mocked(fetchAlarmNotificationDeliveries).mockClear()
  await panel.setProps({ instanceId: 'invalid' })
  await flushPromises()
  expect(fetchAlarmNotificationDeliveries).not.toHaveBeenCalled()
  expect(panel.text()).toContain('投递记录读取失败')
  expect(panel.text()).not.toContain('此告警暂无投递记录')
})
