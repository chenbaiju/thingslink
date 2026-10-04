import { mount } from '@vue/test-utils'
import { it, expect } from 'vitest'
import type { DashboardSchemaV1 } from '@things-link/client-contracts/dashboard/v1'
import { emptyDashboard } from '../src/features/dashboard/designer-model'
import DesignerAlarm from '../src/views/dashboard/designer/components/DesignerAlarm.vue'
const schema: DashboardSchemaV1 = {
  ...emptyDashboard(),
  variables: [
    { key: 'single', type: 'DEVICE_SINGLE', title: '单设备', modelKey: 'model', required: true },
    {
      key: 'multi',
      type: 'DEVICE_MULTI',
      title: '多设备',
      modelKey: 'model',
      required: true,
      maxItems: 20,
      defaultDeviceIds: []
    },
    {
      key: 'time',
      type: 'TIME_RANGE',
      title: '时间',
      required: true,
      defaultPreset: 'LAST_1_HOUR',
      allowedPresets: ['LAST_1_HOUR']
    }
  ]
}
it('仅设备变量可选，三组过滤均非空后提交全部配置', async () => {
  const wrapper = mount(DesignerAlarm, { props: { schema, disabled: false } })
  expect(wrapper.get('[aria-label="告警设备变量"]').text()).not.toContain('时间')
  await wrapper.get('[aria-label="告警设备变量"]').setValue('multi')
  await wrapper.get('[aria-label="告警每页数量"]').setValue(2)
  for (const label of ['条件 ACTIVE', '确认 UNACKNOWLEDGED']) {
    await wrapper.get(`[aria-label="${label}"]`).setValue(false)
    expect(wrapper.get('[data-testid="alarm-component-add"]').attributes('disabled')).toBeDefined()
    await wrapper.get(`[aria-label="${label}"]`).setValue(true)
  }
  for (const level of ['CRITICAL', 'MAJOR', 'MINOR', 'WARNING', 'INFO'])
    await wrapper.get(`[aria-label="等级 ${level}"]`).setValue(false)
  expect(wrapper.get('[data-testid="alarm-component-add"]').attributes('disabled')).toBeDefined()
  await wrapper.get('[aria-label="等级 MAJOR"]').setValue(true)
  await wrapper.get('[aria-label="条件 PENDING"]').setValue(true)
  await wrapper.get('[aria-label="显示恢复时间"]').setValue(false)
  await wrapper.get('[data-testid="alarm-component-add"]').trigger('click')
  expect(wrapper.emitted('add')?.[0]?.[0]).toEqual({
    title: '告警列表',
    deviceVariableKey: 'multi',
    conditionStates: ['ACTIVE', 'PENDING'],
    ackStates: ['UNACKNOWLEDGED'],
    severities: ['MAJOR'],
    pageSize: 2,
    showClearedAt: false
  })
  wrapper.unmount()
})
it('选中告警完整回填与替换，失权或非法页数不能提交', async () => {
  const selected = {
    id: 'alarm',
    kind: 'ALARM_LIST' as const,
    componentVersion: '1.0.0' as const,
    layout: { x: 0, y: 0, w: 6, h: 2 },
    props: { title: '已恢复告警', pageSize: 1, showClearedAt: false },
    bindings: {
      alarms: {
        source: 'ALARM_LIST' as const,
        devices: { variableKey: 'single' },
        conditionStates: ['CLEARED' as const],
        ackStates: ['ACKNOWLEDGED' as const],
        severities: ['INFO' as const]
      }
    }
  }
  const wrapper = mount(DesignerAlarm, { props: { schema, selected, disabled: false } })
  expect(wrapper.get<HTMLInputElement>('[aria-label="告警标题"]').element.value).toBe('已恢复告警')
  expect(wrapper.get<HTMLInputElement>('[aria-label="条件 CLEARED"]').element.checked).toBe(true)
  await wrapper.get('[aria-label="告警设备变量"]').setValue('multi')
  await wrapper.get('[data-testid="alarm-component-rebind"]').trigger('click')
  expect(wrapper.emitted('rebind')?.[0]?.[0]).toMatchObject({
    deviceVariableKey: 'multi',
    conditionStates: ['CLEARED'],
    ackStates: ['ACKNOWLEDGED'],
    severities: ['INFO'],
    showClearedAt: false
  })
  await wrapper.get('[aria-label="告警每页数量"]').setValue(51)
  expect(wrapper.get('[data-testid="alarm-component-rebind"]').attributes('disabled')).toBeDefined()
  await wrapper.get('[aria-label="告警每页数量"]').setValue(1)
  await wrapper.setProps({ disabled: true })
  await wrapper.get('[data-testid="alarm-component-rebind"]').trigger('click')
  expect(wrapper.emitted('rebind')).toHaveLength(1)
  wrapper.unmount()
})
