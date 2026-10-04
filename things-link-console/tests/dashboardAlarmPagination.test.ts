vi.mock('@/store/modules/user', () => ({ useUserStore: () => ({ accessToken: 'test-token' }) }))
import { mount, flushPromises } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  validateDashboardSchemaV1,
  type AlarmResult
} from '@things-link/client-contracts/dashboard/v1'
const mocks = vi.hoisted(() => ({ load: vi.fn(), alarms: vi.fn(), close: vi.fn() }))
vi.mock('@/api/dashboard-binding', () => ({
  createDesignerReadScope: () => ({ close: mocks.close }),
  fetchPreviewSnapshots: vi.fn(),
  fetchPreviewCurrent: vi.fn(),
  fetchDesignerDeviceCatalog: vi.fn()
}))
vi.mock('@/api/dashboard-alarms', () => ({ fetchDesignerAlarms: mocks.alarms }))
vi.mock('@/features/dashboard/device-preview', () => ({ loadDesignerDevicePreview: mocks.load }))
import DesignerDevicePreview from '@/views/dashboard/designer/components/DesignerDevicePreview.vue'
import DesignerDeviceSelector from '@/views/dashboard/designer/components/DesignerDeviceSelector.vue'
const id = '11111111-1111-4111-8111-111111111111'
const schema = () =>
  validateDashboardSchemaV1(
    new TextEncoder().encode(
      JSON.stringify({
        schemaVersion: 'tc.dashboard/v1',
        presentation: { mode: 'RESPONSIVE_GRID' },
        models: [],
        variables: [],
        pages: [{ id: 'main', title: '主页', components: [] }]
      })
    )
  ).schema
const query = {
  queryId: 'shared',
  devices: [{ deviceId: id, expectedModelVersionId: id }],
  conditionStates: ['ACTIVE'],
  ackStates: ['UNACKNOWLEDGED'],
  severities: ['MAJOR'],
  limit: 1
}
function result(alarmType = '第一页', more = true): AlarmResult {
  return {
    componentId: 'one',
    queryId: 'shared',
    status: 'READY',
    nextCursor: more ? 'signed-next' : null,
    hasMore: more,
    items: [
      {
        id,
        deviceId: id,
        alarmType,
        severity: 'MAJOR',
        conditionState: 'ACTIVE',
        ackState: 'UNACKNOWLEDGED',
        firstConditionAt: '2026-09-08T00:00:00Z',
        lastReceivedAt: '2026-09-08T00:00:00Z',
        activatedAt: null,
        clearedAt: null,
        acknowledgedAt: null,
        version: 0
      }
    ]
  }
}
const stubs = {
  ElButton: { props: ['disabled'], template: '<button :disabled="disabled"><slot /></button>' },
  ElAlert: true
}
async function fixture(input = schema()) {
  mocks.load.mockResolvedValue([
    { componentId: 'value', title: '温度', text: '保留温度' },
    ...['one', 'two'].map((componentId) => ({
      componentId,
      title: componentId,
      text: '当前页告警事实',
      alarm: { query, showClearedAt: true, result: { ...result(), componentId } }
    }))
  ])
  const wrapper = mount(DesignerDevicePreview, {
    props: { schema: input, pageId: 'main', projectId: id, available: true },
    global: { stubs }
  })
  await wrapper.get('button').trigger('click')
  await flushPromises()
  return wrapper
}
function button(wrapper: ReturnType<typeof mount>, label: string) {
  return wrapper.findAll('button').find((node) => node.text() === label)!
}
describe('告警独立分页代次', () => {
  beforeEach(() => {
    mocks.load.mockReset()
    mocks.alarms.mockReset()
    mocks.close.mockClear()
  })
  it('同查询双列表同步替换，分页不重读无关完整页面', async () => {
    const wrapper = await fixture()
    let resolve!: (value: AlarmResult) => void
    mocks.alarms.mockImplementation(
      () =>
        new Promise((done) => {
          resolve = done
        })
    )
    await button(wrapper, '下一页告警').trigger('click')
    expect(wrapper.text()).not.toContain('第一页')
    expect(wrapper.text()).toContain('保留温度')
    expect(button(wrapper, '下一页告警').attributes('disabled')).toBeDefined()
    resolve(result('第二页', false))
    await flushPromises()
    expect(wrapper.findAll('tbody').map((body) => body.text())).toEqual([
      expect.stringContaining('第二页'),
      expect.stringContaining('第二页')
    ])
    expect(mocks.load).toHaveBeenCalledTimes(1)
    expect(mocks.alarms).toHaveBeenCalledTimes(1)
    expect(mocks.alarms.mock.calls[0]?.[3]).toBe('signed-next')
    mocks.alarms.mockResolvedValue(result())
    await button(wrapper, '返回告警首页').trigger('click')
    await flushPromises()
    expect(mocks.alarms.mock.calls[1]?.[3]).toBeUndefined()
    expect(wrapper.text()).toContain('第一页')
    wrapper.unmount()
  })
  it('同ID换Schema后迟到页不恢复旧数据', async () => {
    const wrapper = await fixture()
    let resolve!: (value: AlarmResult) => void
    mocks.alarms.mockImplementation(
      () =>
        new Promise((done) => {
          resolve = done
        })
    )
    await button(wrapper, '下一页告警').trigger('click')
    mocks.load.mockResolvedValueOnce([{ componentId: 'new', title: '新草稿', text: '新权威值' }])
    await wrapper.setProps({ schema: schema() })
    resolve(result('旧身份页'))
    await flushPromises()
    expect(wrapper.text()).not.toContain('旧身份页')
    expect(wrapper.text()).not.toContain('保留温度')
    expect(wrapper.text()).toContain('新权威值')
    expect(mocks.load).toHaveBeenCalledTimes(2)
    expect(mocks.close).toHaveBeenCalled()
    wrapper.unmount()
  })
  it('404清全页，500只清关联列表并保留显式重试', async () => {
    const wrapper = await fixture()
    mocks.alarms.mockRejectedValueOnce(Object.assign(new Error('server'), { status: 500 }))
    await button(wrapper, '下一页告警').trigger('click')
    await flushPromises()
    expect(wrapper.text()).not.toContain('第一页')
    expect(wrapper.text()).toContain('保留温度')
    expect(wrapper.text()).toContain('重试告警当前页')
    mocks.alarms.mockRejectedValueOnce(Object.assign(new Error('denied'), { status: 404 }))
    await button(wrapper, '重试告警当前页').trigger('click')
    await flushPromises()
    expect(wrapper.text()).not.toContain('保留温度')
    expect(wrapper.findAll('[data-preview-alarm]')).toHaveLength(0)
    wrapper.unmount()
  })
  it('分页在途改变设备选择只启动一次新完整轮，旧页迟到丢弃', async () => {
    const input = JSON.parse(JSON.stringify(schema()))
    input.models = [
      {
        key: 'model',
        versionId: id,
        digest: 'a'.repeat(64),
        digestAlgorithm: 'PG_JSONB_TEXT_V1_SHA256',
        profile: 'TC_PROPERTY_COMPOSITE_V1'
      }
    ]
    input.variables = [
      {
        key: 'device',
        type: 'DEVICE_SINGLE',
        title: '设备',
        modelKey: 'model',
        defaultDeviceId: id
      }
    ]
    input.pages[0].components = [
      {
        id: 'selector',
        kind: 'DEVICE_SELECTOR',
        componentVersion: '1.0.0',
        layout: { x: 0, y: 0, w: 12, h: 10 },
        props: {},
        bindings: { directory: { source: 'DEVICE_DIRECTORY', variableKey: 'device' } }
      }
    ]
    const original = validateDashboardSchemaV1(
      new TextEncoder().encode(JSON.stringify(input))
    ).schema
    const wrapper = await fixture(original)
    let resolve!: (value: AlarmResult) => void
    mocks.alarms.mockImplementation(
      () =>
        new Promise((done) => {
          resolve = done
        })
    )
    await button(wrapper, '下一页告警').trigger('click')
    const nextId = '22222222-2222-4222-8222-222222222222'
    wrapper.findComponent(DesignerDeviceSelector).vm.$emit('change', [nextId])
    await flushPromises()
    expect(mocks.load).toHaveBeenCalledTimes(1)
    resolve(result('旧设备迟到页'))
    await flushPromises()
    expect(mocks.load).toHaveBeenCalledTimes(2)
    expect(mocks.load.mock.calls[1]?.[3]).toEqual({ device: [nextId] })
    expect(wrapper.text()).not.toContain('旧设备迟到页')
    wrapper.unmount()
  })
})
