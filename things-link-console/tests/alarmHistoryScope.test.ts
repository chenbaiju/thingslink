import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { computed, h, inject, provide, reactive, ref } from 'vue'
import History from '@/views/alarm/history/index.vue'
import {
  fetchAlarmEvents,
  fetchAlarmInstance,
  fetchAlarmInstances,
  fetchClearAlarm
} from '@/api/alarm'
import { invalidateIdentity } from '@/utils/http/identity-scope'
const state = vi.hoisted(() => ({ user: {} as any, confirm: vi.fn() }))
vi.mock('@/store/modules/user', () => ({ useUserStore: () => state.user }))
vi.mock('@/hooks/core/useAuth', () => ({ useAuth: () => ({ hasAuth: () => true }) }))
vi.mock('@/composables/usePagedDeviceCatalog', () => ({
  usePagedDeviceCatalog: () => ({
    devices: ref([]),
    ensureDevices: vi.fn().mockResolvedValue(undefined)
  })
}))
vi.mock('@/api/alarm', () => ({
  fetchAlarmEvents: vi.fn(),
  fetchAlarmInstance: vi.fn(),
  fetchAlarmInstances: vi.fn(),
  fetchClearAlarm: vi.fn(),
  fetchAcknowledgeAlarm: vi.fn()
}))
vi.mock('@/views/alarm/history/AlarmNotificationDeliveries.vue', () => ({
  default: { props: ['instanceId'], template: '<div class="delivery">{{instanceId}}</div>' }
}))
vi.mock('element-plus', () => ({
  ElMessageBox: { confirm: (...args: any[]) => state.confirm(...args) },
  ElMessage: { success: vi.fn() }
}))
let wrapper: VueWrapper
const row = {
  id: 'instance-a',
  deviceId: 'device',
  alarmType: 'CURRENT',
  version: 1,
  conditionState: 'ACTIVE' as const,
  ackState: 'UNACKNOWLEDGED' as const
}
const deferred = () => {
  let resolve!: (v: any) => void
  const promise = new Promise<any>((r) => {
    resolve = r
  })
  return { promise, resolve }
}
const action = (name: string) => wrapper.findAll('button').find((b) => b.text() === name)!
function render() {
  wrapper = mount(History, {
    global: {
      stubs: {
        ElCard: { template: '<div><slot/></div>' },
        ElAlert: { props: ['title'], template: '<div>{{title}}<slot/></div>' },
        ElButton: { template: '<button><slot/></button>' },
        ElSkeleton: true,
        ElEmpty: true,
        ConsoleWorkspaceHeader: true,
        ElTag: { template: '<span><slot/></span>' },
        ElDescriptions: { template: '<div class="summary"><slot/></div>' },
        ElDescriptionsItem: { template: '<div><slot/></div>' },
        ElTimeline: { template: '<div><slot/></div>' },
        ElTimelineItem: { template: '<div><slot/></div>' },
        ConsoleTableAction: { props: ['label'], template: '<button>{{label}}</button>' },
        ElDrawer: {
          props: ['modelValue'],
          emits: ['update:modelValue'],
          template:
            '<div v-if="modelValue"><button @click="$emit(\'update:modelValue\',false)">关闭详情</button><slot/></div>'
        },
        ElTable: {
          props: ['data'],
          setup(props: any, { slots }: any) {
            provide(
              'rows',
              computed(() => props.data)
            )
            return () => h('div', slots.default?.())
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
beforeEach(() => {
  vi.resetAllMocks()
  vi.stubGlobal('ElMessageBox', { confirm: (...args: any[]) => state.confirm(...args) })
  vi.stubGlobal('ElMessage', { success: vi.fn() })
  state.user = reactive({
    info: { currentProjectId: 'project-a', userId: 'user', tenantId: 'tenant' }
  })
  vi.mocked(fetchAlarmInstances).mockResolvedValue({ items: [row], hasMore: false })
  vi.mocked(fetchAlarmInstance).mockResolvedValue(row)
  vi.mocked(fetchAlarmEvents).mockResolvedValue({ items: [], hasMore: false })
})
afterEach(() => {
  wrapper?.unmount()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})
it('切换项目拒绝旧实例列表', async () => {
  const old = deferred()
  vi.mocked(fetchAlarmInstances).mockReturnValueOnce(old.promise)
  render()
  state.user.info.currentProjectId = 'project-b'
  await flushPromises()
  expect(wrapper.text()).toContain('CURRENT')
  old.resolve({ items: [{ ...row, alarmType: 'OLD_PROJECT' }], hasMore: true })
  await flushPromises()
  expect(wrapper.text()).not.toContain('OLD_PROJECT')
})
it('关闭详情卸载投递面板并拒绝迟到事件', async () => {
  const old = deferred()
  vi.mocked(fetchAlarmEvents).mockReturnValueOnce(old.promise)
  render()
  await flushPromises()
  await action('事件').trigger('click')
  await flushPromises()
  expect(wrapper.find('.delivery').text()).toBe('instance-a')
  await action('关闭详情').trigger('click')
  expect(wrapper.find('.delivery').exists()).toBe(false)
  old.resolve({
    items: [{ id: 'old', eventType: 'ACTIVATED', traceId: 'OLD_PRIVATE' }],
    hasMore: true
  })
  await flushPromises()
  await action('事件').trigger('click')
  await flushPromises()
  expect(wrapper.text()).not.toContain('OLD_PRIVATE')
})
it('身份epoch变更清空详情并拒绝旧事件', async () => {
  const old = deferred()
  vi.mocked(fetchAlarmEvents).mockReturnValueOnce(old.promise)
  render()
  await flushPromises()
  await action('事件').trigger('click')
  invalidateIdentity()
  await flushPromises()
  expect(wrapper.find('.delivery').exists()).toBe(false)
  old.resolve({ items: [{ id: 'old', traceId: 'OLD_PRIVATE' }] })
  await flushPromises()
  expect(wrapper.text()).not.toContain('OLD_PRIVATE')
})
it('确认框期间换项目不得发送清除', async () => {
  const confirm = deferred()
  state.confirm.mockReturnValue(confirm.promise)
  render()
  await flushPromises()
  await action('人工清除').trigger('click')
  expect(state.confirm).toHaveBeenCalledTimes(1)
  expect(fetchClearAlarm).not.toHaveBeenCalled()
  state.user.info.currentProjectId = 'project-b'
  await flushPromises()
  confirm.resolve('confirm')
  await flushPromises()
  expect(fetchClearAlarm).not.toHaveBeenCalled()
})

it('打开详情重新读取摘要，外部确认不继承列表的旧未确认状态', async () => {
  vi.mocked(fetchAlarmInstance).mockResolvedValue({ ...row, ackState: 'ACKNOWLEDGED', version: 2 })
  render()
  await flushPromises()
  expect(wrapper.text()).toContain('未确认')
  await action('事件').trigger('click')
  await flushPromises()
  expect(fetchAlarmInstance).toHaveBeenCalledWith('project-a', row.id)
  expect(wrapper.find('.summary').text()).toContain('已确认')
  expect(wrapper.find('.summary').text()).not.toContain('未确认')
  expect(wrapper.find('.summary').text()).toContain('活动')
})
it('刷新清空旧摘要，失败显示不可用且重试重置事件游标', async () => {
  vi.spyOn(console, 'error').mockImplementation(() => {})
  vi.mocked(fetchAlarmEvents).mockResolvedValueOnce({
    items: [{ id: 'first', traceId: 'OLD_EVENT' }],
    hasMore: true,
    nextCursor: 'old-cursor'
  })
  render()
  await flushPromises()
  await action('事件').trigger('click')
  await flushPromises()
  expect(wrapper.find('.summary').exists()).toBe(true)
  const failed = deferred()
  vi.mocked(fetchAlarmInstance).mockReturnValueOnce(failed.promise)
  await action('刷新详情').trigger('click')
  expect(wrapper.find('.summary').exists()).toBe(false)
  expect(wrapper.text()).not.toContain('OLD_EVENT')
  failed.resolve(Promise.reject(new Error('unavailable')))
  await flushPromises()
  expect(wrapper.text()).toContain('告警摘要暂不可用')
  expect(wrapper.find('.summary').exists()).toBe(false)
  expect(wrapper.find('.delivery').exists()).toBe(false)
  await action('刷新详情').trigger('click')
  await flushPromises()
  expect(wrapper.text()).not.toContain('告警摘要暂不可用')
  expect(fetchAlarmEvents).toHaveBeenLastCalledWith('project-a', row.id, undefined)
  expect(wrapper.find('.summary').exists()).toBe(true)
})
it.each(['close', 'project', 'identity'] as const)(
  '%s 使迟到摘要失效，不发出跨范围事件读取',
  async (change) => {
    const late = deferred()
    vi.mocked(fetchAlarmInstance).mockReturnValueOnce(late.promise)
    render()
    await flushPromises()
    await action('事件').trigger('click')
    if (change === 'close') await action('关闭详情').trigger('click')
    if (change === 'project') state.user.info.currentProjectId = 'project-b'
    if (change === 'identity') invalidateIdentity()
    late.resolve({ ...row, alarmType: 'PRIVATE_OLD_SUMMARY' })
    await flushPromises()
    expect(wrapper.text()).not.toContain('PRIVATE_OLD_SUMMARY')
    expect(fetchAlarmEvents).not.toHaveBeenCalled()
    expect(wrapper.find('.delivery').exists()).toBe(false)
  }
)
it('切换告警拒绝上一告警迟到摘要', async () => {
  const late = deferred()
  const other = { ...row, id: 'instance-b', alarmType: 'NEW_ALARM' }
  vi.mocked(fetchAlarmInstances).mockResolvedValue({ items: [row, other] })
  vi.mocked(fetchAlarmInstance).mockReturnValueOnce(late.promise).mockResolvedValueOnce(other)
  render()
  await flushPromises()
  const buttons = wrapper.findAll('button').filter((b) => b.text() === '事件')
  await buttons[0].trigger('click')
  await buttons[1].trigger('click')
  await flushPromises()
  late.resolve({ ...row, alarmType: 'PRIVATE_OLD_SUMMARY' })
  await flushPromises()
  expect(wrapper.find('.summary').text()).toContain('NEW_ALARM')
  expect(wrapper.text()).not.toContain('PRIVATE_OLD_SUMMARY')
  expect(fetchAlarmEvents).toHaveBeenCalledTimes(1)
  expect(fetchAlarmEvents).toHaveBeenCalledWith('project-a', other.id, undefined)
})
it('事件失败不显示暂无事件，刷新可恢复', async () => {
  vi.spyOn(console, 'error').mockImplementation(() => {})
  vi.mocked(fetchAlarmEvents).mockRejectedValueOnce(new Error('events unavailable'))
  render()
  await flushPromises()
  await action('事件').trigger('click')
  await flushPromises()
  expect(wrapper.text()).toContain('告警事件暂不可用')
  expect(wrapper.find('.summary').exists()).toBe(true)
  await action('刷新详情').trigger('click')
  await flushPromises()
  expect(wrapper.text()).not.toContain('告警事件暂不可用')
})

it('切换告警后迟到分页不污染新事件或游标', async () => {
  const other = { ...row, id: 'instance-b' }
  const late = deferred()
  vi.mocked(fetchAlarmInstances).mockResolvedValue({ items: [row, other] })
  vi.mocked(fetchAlarmInstance).mockResolvedValueOnce(row).mockResolvedValueOnce(other)
  vi.mocked(fetchAlarmEvents)
    .mockResolvedValueOnce({ items: [{ id: 'first' }], hasMore: true, nextCursor: 'cursor-a' })
    .mockReturnValueOnce(late.promise)
    .mockResolvedValueOnce({
      items: [{ id: 'new', traceId: 'NEW_EVENT' }],
      hasMore: true,
      nextCursor: 'cursor-b'
    })
  render()
  await flushPromises()
  const buttons = wrapper.findAll('button').filter((b) => b.text() === '事件')
  await buttons[0].trigger('click')
  await flushPromises()
  await action('加载更多事件').trigger('click')
  expect(fetchAlarmEvents).toHaveBeenLastCalledWith('project-a', row.id, 'cursor-a')
  await buttons[1].trigger('click')
  await flushPromises()
  late.resolve({
    items: [{ id: 'old', traceId: 'PRIVATE_OLD_EVENT' }],
    hasMore: true,
    nextCursor: 'old-tail'
  })
  await flushPromises()
  expect(wrapper.text()).toContain('NEW_EVENT')
  expect(wrapper.text()).not.toContain('PRIVATE_OLD_EVENT')
  await action('加载更多事件').trigger('click')
  expect(fetchAlarmEvents).toHaveBeenLastCalledWith('project-a', other.id, 'cursor-b')
})
