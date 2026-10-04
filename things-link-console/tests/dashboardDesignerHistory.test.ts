import { mount, flushPromises } from '@vue/test-utils'
import { it, expect, vi, beforeEach } from 'vitest'
import type { DashboardSchemaV1 } from '@things-link/client-contracts/dashboard/v1'
import { emptyDashboard } from '../src/features/dashboard/designer-model'
const api = vi.hoisted(() => ({ catalog: vi.fn(), metadata: vi.fn(), close: vi.fn() }))
vi.mock('@/api/dashboard-binding', () => ({
  fetchDesignerDeviceCatalog: api.catalog,
  fetchBindingMetadata: api.metadata,
  createDesignerReadScope: () => ({ close: api.close })
}))
import DesignerHistory from '../src/views/dashboard/designer/components/DesignerHistory.vue'
const model = {
  key: 'm1',
  versionId: '22222222-2222-4222-8222-222222222222',
  digestAlgorithm: 'PG_JSONB_TEXT_V1_SHA256' as const,
  digest: 'a'.repeat(64),
  profile: 'TC_PROPERTY_COMPOSITE_V1' as const
}
const second = { ...model, key: 'm2', versionId: '33333333-3333-4333-8333-333333333333' }
const schema: DashboardSchemaV1 = {
  ...emptyDashboard(),
  models: [model, second],
  variables: [
    { key: 'one', type: 'DEVICE_SINGLE', title: '设备一', required: true, modelKey: 'm1' },
    { key: 'two', type: 'DEVICE_SINGLE', title: '设备二', required: true, modelKey: 'm2' },
    {
      key: 'time',
      type: 'TIME_RANGE',
      title: '观察时间',
      required: true,
      defaultPreset: 'LAST_1_HOUR',
      allowedPresets: ['LAST_1_HOUR', 'LAST_24_HOURS']
    }
  ]
}
const fixture = () =>
  mount(DesignerHistory, { props: { schema, projectId: 'project', disabled: false } })
beforeEach(() => {
  vi.clearAllMocks()
  api.catalog.mockImplementation(async (_p, version) => ({
    items: [
      {
        deviceId: 'device',
        name: '目录设备',
        deviceStatus: 'ONLINE',
        currentModelVersionId: version
      }
    ],
    nextCursor: null,
    hasMore: false
  }))
})
it('时间预设不自动纠正非法默认，只有显式选择允许默认才能提交', async () => {
  const wrapper = fixture()
  await wrapper.get('[aria-label="允许最近1小时"]').setValue(false)
  expect(wrapper.get('[data-testid="time-variable-save"]').attributes('disabled')).toBeDefined()
  await wrapper.get('[aria-label="默认时间预设"]').setValue('LAST_24_HOURS')
  await wrapper.get('[data-testid="time-variable-save"]').trigger('click')
  expect(wrapper.emitted('upsert')?.[0]?.[0]).toMatchObject({
    defaultPreset: 'LAST_24_HOURS',
    allowedPresets: ['LAST_24_HOURS', 'LAST_7_DAYS']
  })
  wrapper.unmount()
})
it('两个系列独立模型目录与NUMBER属性，完整输出系列顺序和配置', async () => {
  const wrapper = fixture()
  await wrapper
    .findAll('button')
    .find((b) => b.text() === '添加历史系列')!
    .trigger('click')
  for (const [index, variable, metadata] of [
    [1, 'one', model],
    [2, 'two', second]
  ] as const) {
    await wrapper.get(`[aria-label="历史系列${index}设备变量"]`).setValue(variable)
    await wrapper.get(`[aria-label="历史系列${index}时间变量"]`).setValue('time')
    await wrapper
      .findAll('button')
      .find((b) => b.text() === `读取系列${index}设备目录`)!
      .trigger('click')
    await flushPromises()
    api.metadata.mockResolvedValueOnce({
      model: metadata,
      properties: [
        { key: 'temperature', name: '温度', dataType: 'NUMBER' },
        { key: 'text', name: '文本', dataType: 'TEXT' }
      ]
    })
    await wrapper.get(`[aria-label="历史系列${index}元数据设备"]`).setValue('device')
    await flushPromises()
    expect(wrapper.get(`[aria-label="历史系列${index}属性"]`).text()).not.toContain('文本')
    await wrapper.get(`[aria-label="历史系列${index}属性"]`).setValue('temperature')
  }
  await wrapper.get('[aria-label="历史系列2聚合"]').setValue('MAX')
  await wrapper.get('[data-testid="history-component-add"]').trigger('click')
  expect(wrapper.emitted('add')?.[0]?.[0]).toMatchObject({
    series: [
      { deviceVariableKey: 'one', model: { versionId: model.versionId }, aggregation: 'AVG' },
      { deviceVariableKey: 'two', model: { versionId: second.versionId }, aggregation: 'MAX' }
    ]
  })
  expect(api.catalog.mock.calls.map((c) => c[1])).toEqual([model.versionId, second.versionId])
  wrapper.unmount()
})
it('移除/改变量后的迟到元数据不能恢复旧NUMBER属性', async () => {
  const wrapper = fixture()
  await wrapper.get('[aria-label="历史系列1设备变量"]').setValue('one')
  await wrapper
    .findAll('button')
    .find((b) => b.text() === '读取系列1设备目录')!
    .trigger('click')
  await flushPromises()
  let resolve!: (value: unknown) => void
  api.metadata.mockReturnValueOnce(
    new Promise((done) => {
      resolve = done
    })
  )
  await wrapper.get('[aria-label="历史系列1元数据设备"]').setValue('device')
  await wrapper.setProps({ disabled: true })
  await wrapper.setProps({ disabled: false })
  resolve({ model, properties: [{ key: 'leak', name: '旧字段', dataType: 'NUMBER' }] })
  await flushPromises()
  expect(wrapper.text()).not.toContain('旧字段')
  expect(api.close).toHaveBeenCalled()
  expect(wrapper.get('[data-testid="history-component-add"]').attributes('disabled')).toBeDefined()
  wrapper.unmount()
})
