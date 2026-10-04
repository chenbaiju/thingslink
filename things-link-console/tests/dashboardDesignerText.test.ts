import { mount } from '@vue/test-utils'
import { it, expect } from 'vitest'
import type { DashboardSchemaV1 } from '@things-link/client-contracts/dashboard/v1'
import { emptyDashboard } from '../src/features/dashboard/designer-model'
import DesignerText from '../src/views/dashboard/designer/components/DesignerText.vue'
const schema: DashboardSchemaV1 = {
  ...emptyDashboard(),
  variables: [
    {
      key: 'mode',
      type: 'TEXT_ENUM',
      title: '模式',
      required: true,
      options: [
        { value: 'a', label: '甲' },
        { value: 'b', label: '乙' }
      ],
      defaultValue: 'b'
    }
  ]
}
it('选项删除不偷偷更改默认，Unicode按码点计数并原文提交标签', async () => {
  const wrapper = mount(DesignerText, { props: { schema, disabled: false } })
  await wrapper
    .findAll('button')
    .find((b) => b.text() === '编辑文本变量 模式')!
    .trigger('click')
  await wrapper
    .findAll('button')
    .find((b) => b.text() === '删除第2项')!
    .trigger('click')
  expect(wrapper.get('[data-testid="text-variable-save"]').attributes('disabled')).toBeDefined()
  expect(wrapper.text()).toContain('原默认选项已移除')
  await wrapper.get('[aria-label="文本默认选项"]').setValue('')
  await wrapper.get('[aria-label="第1项标签"]').setValue('😀'.repeat(80))
  expect(wrapper.get('[data-testid="text-variable-save"]').attributes('disabled')).toBeUndefined()
  await wrapper.get('[aria-label="第1项标签"]').setValue('😀'.repeat(81))
  expect(wrapper.get('[data-testid="text-variable-save"]').attributes('disabled')).toBeDefined()
  await wrapper.get('[aria-label="第1项标签"]').setValue(' <img src=x> ')
  await wrapper.get('[data-testid="text-variable-save"]').trigger('click')
  const input = wrapper.emitted('upsert')?.[0]?.[0]
  expect(input).toMatchObject({ key: 'mode', options: [{ value: 'a', label: ' <img src=x> ' }] })
  expect(input).not.toHaveProperty('defaultValue')
  wrapper.unmount()
})
it('动态TEXT回填隐藏静态输入，双向替换意图无双内容源', async () => {
  const selected = {
    id: 'text',
    kind: 'TEXT' as const,
    componentVersion: '1.0.0' as const,
    layout: { x: 0, y: 0, w: 4, h: 1 },
    props: { align: 'CENTER' as const, size: 'LARGE' as const, tone: 'PRIMARY' as const },
    bindings: { text: { source: 'ENUM_TEXT' as const, variableKey: 'mode' } }
  }
  const wrapper = mount(DesignerText, { props: { schema, selected, disabled: false } })
  expect(wrapper.find('[aria-label="静态文本内容"]').exists()).toBe(false)
  await wrapper.get('[aria-label="文本大小"]').setValue('SMALL')
  await wrapper.get('[data-testid="text-component-rebind"]').trigger('click')
  expect(wrapper.emitted('rebind')?.[0]?.[0]).toEqual({
    mode: 'DYNAMIC',
    variableKey: 'mode',
    align: 'CENTER',
    size: 'SMALL',
    tone: 'PRIMARY'
  })
  await wrapper.get('[aria-label="文本模式"]').setValue('STATIC')
  await wrapper.get('[aria-label="静态文本内容"]').setValue('恢复静态')
  await wrapper.get('[data-testid="text-component-rebind"]').trigger('click')
  expect(wrapper.emitted('rebind')?.[1]?.[0]).toEqual({
    mode: 'STATIC',
    content: '恢复静态',
    align: 'CENTER',
    size: 'SMALL',
    tone: 'PRIMARY'
  })
  await wrapper.setProps({ disabled: true })
  await wrapper.get('[data-testid="text-component-rebind"]').trigger('click')
  expect(wrapper.emitted('rebind')).toHaveLength(2)
  wrapper.unmount()
})
