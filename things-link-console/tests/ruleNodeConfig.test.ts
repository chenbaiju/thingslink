import { describe, expect, it } from 'vitest'
import { canEditConfig, fieldValue, remapNodeFields } from '@/views/rule/components/node-config'

describe('规则节点编辑保持配置类型与历史完整性', () => {
  it.each([
    ['notification-action', { channel: 'EMAIL', recipient: 'local@example.com' }],
    ['email-action', { recipient: 'local@example.com', subject: '提醒', body: '正文' }],
    ['webhook-action', { url: 'https://example.com/hook', body: '{"ok":true}' }],
    ['device-command-action', { commandKey: 'restart', input: { wait: 3 } }],
    ['device-property-set-action', { properties: { on: true, values: [1, '2', null] } }],
    ['alarm-create-action', { alarmRuleId: '00000000-0000-0000-0000-000000000001' }],
    ['alarm-clear-action', { alarmRuleId: '00000000-0000-0000-0000-000000000001' }]
  ])('JSON结构回读不丢%s的字段', (_type, config) => {
    expect(fieldValue(JSON.stringify(config), { type: 'object' })).toEqual(config)
    expect(
      canEditConfig(config, {
        properties: Object.fromEntries(Object.keys(config).map((key) => [key, {}]))
      })
    ).toBe(true)
  })
  it('原生类型不字符串化，未知历史字段阻止有损保存', () => {
    expect(fieldValue('true', {})).toBe(true)
    expect(fieldValue('12.5', {})).toBe(12.5)
    expect(fieldValue('null', {})).toBeNull()
    expect(fieldValue('12.5', { type: 'string' })).toBe('12.5')
    expect(() => fieldValue('{broken', {})).toThrow()
    expect(
      canEditConfig(
        { recipient: 'x', future: 1 },
        { properties: { recipient: { type: 'string' } } }
      )
    ).toBe(false)
    expect(canEditConfig([], { properties: {} })).toBe(false)
  })
})

it('节点调序和删除不能清除其他节点尚未修正的非法输入', () => {
  const fields = { '0:params': '{broken', '1:properties': '[unfinished' }
  expect(remapNodeFields(fields, [1, 0])).toEqual({
    '1:params': '{broken',
    '0:properties': '[unfinished'
  })
  expect(remapNodeFields(fields, [1])).toEqual({ '0:properties': '[unfinished' })
  expect(remapNodeFields(fields, [0, 1])).toEqual(fields)
})
