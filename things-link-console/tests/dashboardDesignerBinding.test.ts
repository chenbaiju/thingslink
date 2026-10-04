import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import DesignerDeviceBinding from '../src/views/dashboard/designer/components/DesignerDeviceBinding.vue'

const model = {
  versionId: '22222222-2222-4222-8222-222222222222',
  digestAlgorithm: 'PG_JSONB_TEXT_V1_SHA256' as const,
  digest: 'a'.repeat(64),
  profile: 'TC_PROPERTY_COMPOSITE_V1' as const
}
function fixture() {
  return mount(DesignerDeviceBinding, {
    props: {
      deviceId: '11111111-1111-4111-8111-111111111111',
      model,
      properties: [
        { key: 'temperature', name: '温度', dataType: 'NUMBER' },
        { key: 'details', name: '详情', dataType: 'OBJECT' },
        { key: 'samples', name: '样本', dataType: 'LIST' }
      ]
    },
    global: {
      stubs: {
        ElForm: { template: '<form><slot /></form>' },
        ElFormItem: { template: '<div><slot /></div>' },
        ElAlert: true,
        ElInputNumber: {
          props: ['modelValue'],
          emits: ['update:modelValue'],
          template:
            '<input type="number" :value="modelValue" @input="$emit(\'update:modelValue\', Number($event.target.value))" />'
        },
        ElSwitch: {
          props: ['modelValue'],
          emits: ['update:modelValue'],
          template:
            '<input type="checkbox" :checked="modelValue" @change="$emit(\'update:modelValue\', $event.target.checked)" />'
        },
        ElSelect: {
          props: ['modelValue'],
          emits: ['update:modelValue'],
          template:
            '<select :value="modelValue" @change="$emit(\'update:modelValue\', $event.target.value)"><slot /></select>'
        },
        ElOption: {
          props: ['label', 'value'],
          template: '<option :value="value">{{ label }}</option>'
        },
        ElInput: {
          props: ['modelValue'],
          emits: ['update:modelValue'],
          template:
            '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />'
        },
        ElButton: {
          props: ['disabled'],
          template: '<button :disabled="disabled" type="button"><slot /></button>'
        }
      }
    }
  })
}
describe('设备绑定添加表单', () => {
  it('只呈现支持的顶层属性，完整透传服务端模型身份', async () => {
    const wrapper = fixture()
    expect(wrapper.text()).not.toContain('详情')
    expect(wrapper.find('button').attributes('disabled')).toBeDefined()
    await wrapper.findAll('select')[1]!.setValue('temperature')
    await wrapper.find('button').trigger('click')
    expect(wrapper.emitted('add')?.[0]?.[0]).toEqual({
      kind: 'VALUE_CARD',
      title: '设备属性',
      deviceId: wrapper.props('deviceId'),
      model,
      propertyKey: 'temperature',
      propertyMetadata: { key: 'temperature', name: '温度', dataType: 'NUMBER' },
      componentProps: { precision: 2, unitMode: 'MODEL' }
    })
    wrapper.unmount()
  })
  it('设备更换清理旧属性；状态无需属性，但元数据错误或忙碌禁止添加', async () => {
    const wrapper = fixture()
    await wrapper.findAll('select')[1]!.setValue('temperature')
    await wrapper.setProps({ deviceId: '33333333-3333-4333-8333-333333333333' })
    expect(wrapper.find('button').attributes('disabled')).toBeDefined()
    await wrapper.findAll('select')[0]!.setValue('STATUS')
    await wrapper.find('button').trigger('click')
    expect(wrapper.emitted('add')?.[0]?.[0]).not.toHaveProperty('propertyKey')
    for (const patch of [
      { error: '模型不可用' },
      { error: '', busy: true },
      { busy: false, model: null },
      { model, disabled: true }
    ]) {
      await wrapper.setProps(patch)
      expect(wrapper.find('button').attributes('disabled')).toBeDefined()
      await wrapper.find('button').trigger('click')
    }
    expect(wrapper.emitted('add')).toHaveLength(1)
    wrapper.unmount()
  })
})

it('复合属性按类型过滤，完整列表参数明确提交', async () => {
  const wrapper = fixture()
  await wrapper.get('[aria-label="绑定组件类型"]').setValue('JSON_VIEW')
  expect(wrapper.get('[aria-label="绑定顶层属性"]').text()).toContain('详情')
  expect(wrapper.get('[aria-label="绑定顶层属性"]').text()).not.toContain('温度')
  await wrapper.get('[aria-label="绑定顶层属性"]').setValue('details')
  await wrapper.get('[aria-label="绑定初始展开层数"]').setValue(2)
  await wrapper.get('button').trigger('click')
  expect(wrapper.emitted('add')?.[0]?.[0]).toMatchObject({
    kind: 'JSON_VIEW',
    propertyKey: 'details',
    componentProps: { initialExpandDepth: 2 }
  })
  await wrapper.get('[aria-label="绑定组件类型"]').setValue('TABLE')
  expect(wrapper.get('[aria-label="绑定顶层属性"]').text()).not.toContain('详情')
  await wrapper.get('[aria-label="绑定顶层属性"]').setValue('samples')
  await wrapper.get('[aria-label="绑定每页行数"]').setValue(2)
  await wrapper.get('button').trigger('click')
  expect(wrapper.emitted('add')?.[1]?.[0]).toMatchObject({
    kind: 'TABLE',
    propertyKey: 'samples',
    componentProps: { mode: 'LIST_VALUE', rowLimit: 2 }
  })
  wrapper.unmount()
})
it('无模型边界禁止仪表模型量程，显式量程在精度与顺序验证后才能添加', async () => {
  const wrapper = fixture()
  await wrapper.get('[aria-label="绑定组件类型"]').setValue('GAUGE')
  await wrapper.get('[aria-label="绑定顶层属性"]').setValue('temperature')
  expect(wrapper.get('button').attributes('disabled')).toBeDefined()
  await wrapper.get('[aria-label="绑定仪表量程"]').setValue('EXPLICIT')
  await wrapper.get('[aria-label="绑定仪表最小值"]').setValue('0')
  await wrapper.get('[aria-label="绑定仪表最大值"]').setValue('1.234567890123456')
  expect(wrapper.get('button').attributes('disabled')).toBeDefined()
  await wrapper.get('[aria-label="绑定仪表最大值"]').setValue('10')
  await wrapper.get('button').trigger('click')
  expect(wrapper.emitted('add')?.[0]?.[0]).toMatchObject({
    kind: 'GAUGE',
    componentProps: { scaleMode: 'EXPLICIT', min: 0, max: 10 }
  })
  wrapper.unmount()
})
