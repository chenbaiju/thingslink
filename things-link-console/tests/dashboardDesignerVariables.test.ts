import { mount, flushPromises } from '@vue/test-utils'
import { describe, expect, it, vi, beforeEach } from 'vitest'
import { emptyDashboard } from '../src/features/dashboard/designer-model'
import type { DashboardSchemaV1 } from '@things-link/client-contracts/dashboard/v1'
const api = vi.hoisted(() => ({ catalog: vi.fn(), metadata: vi.fn(), close: vi.fn() }))
vi.mock('@/api/dashboard-binding', () => ({
  fetchDesignerDeviceCatalog: api.catalog,
  fetchBindingMetadata: api.metadata,
  createDesignerReadScope: () => ({ close: api.close })
}))
import DesignerVariables from '../src/views/dashboard/designer/components/DesignerVariables.vue'
const device = '11111111-1111-4111-8111-111111111111'
const model = {
  versionId: '22222222-2222-4222-8222-222222222222',
  digestAlgorithm: 'PG_JSONB_TEXT_V1_SHA256' as const,
  digest: 'a'.repeat(64),
  profile: 'TC_PROPERTY_COMPOSITE_V1' as const
}
const schema: DashboardSchemaV1 = {
  ...emptyDashboard(),
  models: [{ key: 'model_1', ...model }],
  variables: [
    {
      key: 'devices',
      type: 'DEVICE_MULTI',
      title: '机组',
      required: true,
      modelKey: 'model_1',
      maxItems: 3,
      defaultDeviceIds: []
    }
  ]
}
const fixture = () =>
  mount(DesignerVariables, {
    props: { schema, projectId: 'project', disabled: false, bindingModel: model }
  })
beforeEach(() => {
  vi.clearAllMocks()
  api.catalog.mockResolvedValue({
    items: [
      {
        deviceId: device,
        name: '设备甲',
        deviceStatus: 'ONLINE',
        currentModelVersionId: model.versionId
      }
    ],
    nextCursor: null,
    hasMore: false
  })
  api.metadata.mockResolvedValue({
    model,
    properties: [
      { key: 'temperature', name: '温度', dataType: 'NUMBER' },
      { key: 'json', name: '复合', dataType: 'OBJECT' }
    ]
  })
})
describe('设备变量编辑表单', () => {
  it('目录不自动选择默认设备，跨页保留显式默认值且提交完整变量', async () => {
    const wrapper = fixture()
    await wrapper.get('[data-testid="variable-edit-devices"]').trigger('click')
    await wrapper
      .findAll('button')
      .find((b) => b.text() === '读取变量设备目录')!
      .trigger('click')
    await flushPromises()
    expect(wrapper.get<HTMLInputElement>('[aria-label="默认设备 设备甲"]').element.checked).toBe(
      false
    )
    await wrapper.get('[aria-label="默认设备 设备甲"]').setValue(true)
    api.catalog.mockResolvedValueOnce({ items: [], nextCursor: null, hasMore: false })
    await wrapper
      .findAll('button')
      .find((b) => b.text() === '读取变量设备目录')!
      .trigger('click')
    await flushPromises()
    await wrapper.get('[data-testid="variable-save"]').trigger('click')
    expect(wrapper.emitted('upsert')?.[0]?.[0]).toMatchObject({
      key: 'devices',
      type: 'DEVICE_MULTI',
      defaultDeviceIds: [device],
      model
    })
    wrapper.unmount()
  })
  it('显式读取同模型元数据后仅允许标量列，选择器与表复用同一变量', async () => {
    const wrapper = fixture()
    await wrapper.get('[data-testid="variable-edit-devices"]').trigger('click')
    await wrapper
      .findAll('button')
      .find((b) => b.text() === '读取变量设备目录')!
      .trigger('click')
    await flushPromises()
    await wrapper.get('[aria-label="组件设备变量"]').setValue('devices')
    await wrapper.get('[data-testid="variable-component-add"]').trigger('click')
    expect(wrapper.emitted('add')?.[0]?.[0]).toMatchObject({
      kind: 'DEVICE_SELECTOR',
      variableKey: 'devices'
    })
    await wrapper.get('[aria-label="变量组件类型"]').setValue('TABLE')
    expect(
      wrapper.get('[data-testid="variable-component-add"]').attributes('disabled')
    ).toBeDefined()
    await wrapper.get('[aria-label="变量属性元数据设备"]').setValue(device)
    await flushPromises()
    expect(wrapper.get('[aria-label="第1列属性"]').text()).not.toContain('复合')
    await wrapper.get('[aria-label="第1列属性"]').setValue('temperature')
    await wrapper.get('[data-testid="variable-component-add"]').trigger('click')
    expect(wrapper.emitted('add')?.[1]?.[0]).toMatchObject({
      kind: 'TABLE',
      variableKey: 'devices',
      model,
      columns: [{ propertyKey: 'temperature' }]
    })
    wrapper.unmount()
  })
  it('项目换代取消并丢弃迟到目录，失败不展示空目录事实', async () => {
    let resolve!: (value: unknown) => void
    api.catalog.mockReturnValueOnce(
      new Promise((done) => {
        resolve = done
      })
    )
    const wrapper = fixture()
    await wrapper.get('[data-testid="variable-edit-devices"]').trigger('click')
    await wrapper
      .findAll('button')
      .find((b) => b.text() === '读取变量设备目录')!
      .trigger('click')
    await wrapper.setProps({ projectId: 'other' })
    resolve({ items: [{ deviceId: device, name: '旧权限设备' }], nextCursor: null, hasMore: false })
    await flushPromises()
    expect(wrapper.text()).not.toContain('旧权限设备')
    expect(api.close).toHaveBeenCalled()
    await wrapper.get('[data-testid="variable-edit-devices"]').trigger('click')
    api.catalog.mockRejectedValueOnce({ code: 30001 })
    await wrapper
      .findAll('button')
      .find((b) => b.text() === '读取变量设备目录')!
      .trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('目录读取失败')
    expect(wrapper.text()).not.toContain('当前页无可选择设备')
    wrapper.unmount()
  })
})

it('跨页引用使删除、类型及模型修改被禁用，仍可编辑默认与标题', async () => {
  const linked: DashboardSchemaV1 = {
    ...schema,
    pages: [
      {
        id: 'other',
        title: '其他页面',
        components: [
          {
            id: 'selector',
            kind: 'DEVICE_SELECTOR',
            componentVersion: '1.0.0',
            layout: { x: 0, y: 0, w: 4, h: 1 },
            props: { placeholder: '选择设备', pageSize: 20 },
            bindings: { directory: { source: 'DEVICE_DIRECTORY', variableKey: 'devices' } }
          }
        ]
      }
    ]
  }
  const wrapper = mount(DesignerVariables, {
    props: { schema: linked, projectId: 'project', disabled: false }
  })
  await wrapper.get('[data-testid="variable-edit-devices"]').trigger('click')
  expect(wrapper.get('[aria-label="变量类型"]').attributes('disabled')).toBeDefined()
  expect(wrapper.get('[aria-label="变量模型"]').attributes('disabled')).toBeDefined()
  expect(wrapper.get('[data-testid="variable-delete"]').attributes('disabled')).toBeDefined()
  expect(wrapper.text()).toContain('其他页面 / selector')
  await wrapper.get('[aria-label="变量标题"]').setValue('新名字')
  await wrapper.get('[data-testid="variable-save"]').trigger('click')
  expect(wrapper.emitted('upsert')?.[0]?.[0]).toMatchObject({ title: '新名字' })
  wrapper.unmount()
})
it('元数据设备换模拒绝后不能沿用旧列属性，模型读取期间切权限丢迟到结果', async () => {
  const wrapper = fixture()
  await wrapper.get('[data-testid="variable-edit-devices"]').trigger('click')
  await wrapper
    .findAll('button')
    .find((b) => b.text() === '读取变量设备目录')!
    .trigger('click')
  await flushPromises()
  api.metadata.mockResolvedValueOnce({
    model: { ...model, digest: 'b'.repeat(64) },
    properties: [{ key: 'leaked', name: '旧字段', dataType: 'NUMBER' }]
  })
  await wrapper.get('[aria-label="变量属性元数据设备"]').setValue(device)
  await flushPromises()
  expect(wrapper.text()).toContain('设备已换模')
  await wrapper.get('[aria-label="变量组件类型"]').setValue('TABLE')
  expect(wrapper.get('[aria-label="第1列属性"]').text()).not.toContain('旧字段')
  expect(wrapper.get('[data-testid="variable-component-add"]').attributes('disabled')).toBeDefined()
  wrapper.unmount()
})
