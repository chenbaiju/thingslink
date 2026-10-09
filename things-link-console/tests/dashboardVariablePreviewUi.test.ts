vi.mock('@/store/modules/user', () => ({ useUserStore: () => ({ accessToken: 'test-token' }) }))
import { mount, flushPromises } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { validateDashboardSchemaV1 } from '@things-link/client-contracts/dashboard/v1'
const mocks = vi.hoisted(() => ({ catalog: vi.fn(), load: vi.fn(), close: vi.fn() }))
vi.mock('../src/api/dashboard-binding', () => ({
  fetchDesignerDeviceCatalog: mocks.catalog,
  createDesignerReadScope: () => ({ close: mocks.close }),
  fetchPreviewSnapshots: vi.fn(),
  fetchPreviewCurrent: vi.fn()
}))
vi.mock('../src/features/dashboard/device-preview', () => ({
  loadDesignerDevicePreview: mocks.load
}))
import DesignerDevicePreview from '../src/views/dashboard/designer/components/DesignerDevicePreview.vue'
import DesignerDeviceSelector from '../src/views/dashboard/designer/components/DesignerDeviceSelector.vue'
import DesignerDeviceValues from '../src/views/dashboard/designer/components/DesignerDeviceValues.vue'
const id = (n: number) => `11111111-1111-4111-8111-${String(n).padStart(12, '0')}`
const modelId = '22222222-2222-4222-8222-222222222222'
const stubs = {
  ElButton: { props: ['disabled'], template: '<button :disabled="disabled"><slot /></button>' },
  ElAlert: true
}
const item = (n: number) => ({
  deviceId: id(n),
  name: `设备${n}`,
  deviceStatus: 'ONLINE',
  currentModelVersionId: modelId
})
function schema() {
  return validateDashboardSchemaV1(
    new TextEncoder().encode(
      JSON.stringify({
        schemaVersion: 'tc.dashboard/v1',
        presentation: { mode: 'RESPONSIVE_GRID' },
        models: [
          {
            key: 'model',
            versionId: modelId,
            digest: 'a'.repeat(64),
            digestAlgorithm: 'PG_JSONB_TEXT_V1_SHA256',
            profile: 'TC_PROPERTY_COMPOSITE_V1'
          }
        ],
        variables: [
          {
            key: 'devices',
            type: 'DEVICE_MULTI',
            title: '对比设备',
            modelKey: 'model',
            maxItems: 2
          }
        ],
        pages: [
          {
            id: 'main',
            title: '主页',
            components: ['first', 'second'].map((id, index) => ({
              id,
              kind: 'DEVICE_SELECTOR',
              componentVersion: '1.0.0',
              layout: { x: 0, y: index * 10, w: 12, h: 10 },
              props: { title: id, pageSize: 2 },
              bindings: { directory: { source: 'DEVICE_DIRECTORY', variableKey: 'devices' } }
            }))
          }
        ]
      })
    )
  ).schema
}
beforeEach(() => {
  mocks.load.mockReset().mockResolvedValue([])
  mocks.catalog.mockReset()
  mocks.close.mockClear()
})
describe('共享运行选择与完整表格', () => {
  it('同变量双选择器同步，默认值不被运行选择改写', async () => {
    mocks.catalog
      .mockReset()
      .mockResolvedValue({ items: [item(1), item(2)], hasMore: false, nextCursor: null })
    mocks.load.mockReset().mockResolvedValue([])
    const original = schema(),
      wrapper = mount(DesignerDevicePreview, {
        props: { schema: original, pageId: 'main', projectId: id(9), available: true },
        global: { stubs }
      })
    await flushPromises() // 等待自动首轮完成，目录由同一顶层调度器接收。
    const selectors = wrapper.findAllComponents(DesignerDeviceSelector)
    await selectors[0]!.find('button').trigger('click')
    await flushPromises()
    expect(selectors[0]!.props('selected')).toEqual([])
    await selectors[0]!.find('input').setValue(true)
    await flushPromises()
    expect(selectors[0]!.props('selected')).toEqual([id(1)])
    expect(selectors[1]!.props('selected')).toEqual([id(1)])
    expect(mocks.load.mock.calls[0]![3]).toEqual({})
    expect(mocks.load.mock.calls.at(-1)![3]).toEqual({ devices: [id(1)] })
    expect(original.variables[0]).toHaveProperty('defaultDeviceIds', [])
    await wrapper.setProps({ projectId: id(8) })
    expect(selectors[0]!.props('selected')).toEqual([])
    wrapper.unmount()
  })
  it('跨页目录保留用户选中项，不以当前页缺少判断撤权', async () => {
    mocks.catalog
      .mockReset()
      .mockResolvedValueOnce({ items: [item(1), item(2)], nextCursor: 'next', hasMore: true })
      .mockResolvedValueOnce({ items: [item(3)], nextCursor: null, hasMore: false })
    const wrapper = mount(DesignerDeviceSelector, {
      props: {
        projectId: id(9),
        modelVersionId: modelId,
        variableKey: 'devices',
        title: '设备',
        multiple: true,
        maxItems: 2,
        pageSize: 2,
        selected: [id(1)],
        disabled: false
      },
      global: { stubs }
    })
    await wrapper.find('button').trigger('click')
    await flushPromises()
    await wrapper.findAll('button')[1]!.trigger('click')
    await flushPromises()
    expect(wrapper.find(`[data-selected-device="${id(1)}"]`).exists()).toBe(true)
    expect(wrapper.emitted('change')).toBeUndefined()
    expect(wrapper.text()).toContain('设备3')
    await wrapper.setProps({ disabled: true })
    expect(wrapper.findAll('input')).toHaveLength(0)
    wrapper.unmount()
  })
  it('在途切换立即丢旧值，物理返回后只重读最后选择', async () => {
    let resolve!: (rows: unknown[]) => void
    mocks.load
      .mockReset()
      .mockImplementationOnce(
        () =>
          new Promise((done) => {
            resolve = done
          })
      )
      .mockResolvedValue([{ componentId: 'fresh', title: '新选择', text: '新值' }])
    const wrapper = mount(DesignerDevicePreview, {
      props: { schema: schema(), pageId: 'main', projectId: id(9), available: true },
      global: { stubs }
    })
    await flushPromises() // 自动首轮已实际开始；不要用禁用按钮伪造额外刷新。
    const selector = wrapper.findComponent(DesignerDeviceSelector)
    selector.vm.$emit('change', [id(1)])
    await flushPromises()
    selector.vm.$emit('change', [id(2)])
    await flushPromises()
    resolve([{ componentId: 'old', title: '旧数据', text: '禁止回填' }])
    await flushPromises()
    expect(mocks.load).toHaveBeenCalledTimes(2)
    expect(mocks.load.mock.calls[1]![3]).toEqual({ devices: [id(2)] })
    expect(wrapper.text()).not.toContain('禁止回填')
    expect(wrapper.text()).toContain('新值')
    wrapper.unmount()
  })
  it('完整表本地分页，换事实重置页码', async () => {
    const table = {
      columns: [{ id: 'temperature', label: '温度' }],
      rowLimit: 1,
      rows: [1, 2].map((n) => ({ deviceId: id(n), name: `设备${n}`, cells: [{ text: `${n}.50` }] }))
    }
    const wrapper = mount(DesignerDeviceValues, { props: { table }, global: { stubs } })
    expect(wrapper.text()).toContain('完整设备数：2')
    expect(wrapper.text()).not.toContain('设备2')
    await wrapper.findAll('button')[1]!.trigger('click')
    expect(wrapper.text()).toContain('设备2')
    await wrapper.setProps({ table: { ...table, rows: [table.rows[0]!] } })
    expect(wrapper.text()).toContain('设备1')
    expect(wrapper.text()).not.toContain('设备2')
    wrapper.unmount()
  })
  it.each(['catalog', 'preview'])('明确失权双向清目录与预览：%s', async (origin) => {
    mocks.catalog
      .mockReset()
      .mockResolvedValue({ items: [item(1)], nextCursor: null, hasMore: false })
    mocks.load
      .mockReset()
      .mockResolvedValue([{ componentId: 'value', title: '设备当前值', text: '敏感旧值' }])
    const wrapper = mount(DesignerDevicePreview, {
      props: { schema: schema(), pageId: 'main', projectId: id(9), available: true },
      global: { stubs }
    })
    await flushPromises() // 等待自动首轮完成，目录由同一顶层调度器接收。
    const selectors = wrapper.findAllComponents(DesignerDeviceSelector)
    for (const selector of selectors) {
      await selector.find('button').trigger('click')
      await flushPromises()
    }
    expect(wrapper.text()).toContain('敏感旧值')
    for (const selector of selectors) expect(selector.findAll('input')).toHaveLength(1)
    if (origin === 'catalog') {
      mocks.catalog.mockRejectedValueOnce(Object.assign(new Error('denied'), { status: 403 }))
      await selectors[0]!.find('button').trigger('click')
    } else {
      mocks.load.mockRejectedValueOnce(Object.assign(new Error('denied'), { status: 403 }))
      await wrapper.find('button').trigger('click')
    }
    await flushPromises()
    expect(wrapper.text()).not.toContain('敏感旧值')
    for (const selector of selectors) expect(selector.findAll('input')).toHaveLength(0)
    expect(wrapper.find('el-alert-stub[type="error"]').attributes('title')).toContain(
      '读取权限已变化'
    )
    wrapper.unmount()
  })
  it('相同选择器id的Schema换代清上一份目录，迟到目录不能回填', async () => {
    let resolve!: (value: unknown) => void
    mocks.load
      .mockReset()
      .mockResolvedValueOnce([{ componentId: 'old', title: '旧Schema', text: '旧快照' }])
      .mockResolvedValue([{ componentId: 'new', title: '新Schema', text: '新快照' }])
    mocks.catalog
      .mockReset()
      .mockImplementationOnce(
        () =>
          new Promise((done) => {
            resolve = done
          })
      )
      .mockResolvedValue({ items: [item(2)], nextCursor: null, hasMore: false })
    const wrapper = mount(DesignerDevicePreview, {
      props: { schema: schema(), pageId: 'main', projectId: id(9), available: true },
      global: { stubs }
    })
    await flushPromises()
    expect(wrapper.text()).toContain('旧快照')
    await wrapper.findComponent(DesignerDeviceSelector).find('button').trigger('click')
    await flushPromises() // 保证旧目录已真正进入provider，覆盖在途取消而非仅排队取消。
    expect(mocks.catalog).toHaveBeenCalledTimes(1)
    await wrapper.setProps({ schema: schema() })
    expect(wrapper.text()).not.toContain('旧快照')
    resolve({ items: [item(1)], nextCursor: null, hasMore: false })
    await flushPromises()
    expect(wrapper.findComponent(DesignerDeviceSelector).findAll('input')).toHaveLength(0)
    expect(wrapper.text()).not.toContain('设备1')
    expect(wrapper.text()).toContain('新快照')
    await wrapper.findComponent(DesignerDeviceSelector).find('button').trigger('click')
    await flushPromises()
    expect(wrapper.findComponent(DesignerDeviceSelector).text()).toContain('设备2')
    expect(wrapper.findComponent(DesignerDeviceSelector).text()).not.toContain('设备1')
    wrapper.unmount()
  })
})
