import { describe, expect, it } from 'vitest'
import {
  auditActionOptions,
  detailsSummary,
  otaActionLabel,
  otaTargetLabel,
  OTA_ACTION_LABELS
} from '@/features/ota/audit-model'

describe('OTA审计展示', () => {
  it('已登记动作翻译成中文，未知动作原样透传而不是折叠成既有含义', () => {
    expect(otaActionLabel('ota.firmware.created')).toBe('创建固件草稿')
    expect(otaActionLabel('ota.upload.verified')).toBe('对象复验通过')
    expect(otaActionLabel('ota.something.brand.new')).toBe('ota.something.brand.new')
    expect(otaActionLabel(null)).toBe('—')
    expect(otaActionLabel(undefined)).toBe('—')
  })

  it('目标类型同样原样透传未知值', () => {
    expect(otaTargetLabel('ota_firmware')).toBe('固件')
    expect(otaTargetLabel('ota_device_job')).toBe('设备作业')
    expect(otaTargetLabel('some_new_target')).toBe('some_new_target')
    expect(otaTargetLabel(null)).toBe('—')
  })

  it('明细摘要只做键值拼接并截断，不丢字段也不展开超长值', () => {
    expect(detailsSummary({ firmwareId: 'f1', status: 'DRAFT' })).toBe(
      'firmwareId=f1 · status=DRAFT'
    )
    expect(detailsSummary({})).toBe('—')
    expect(detailsSummary(null)).toBe('—')
    const long = detailsSummary({ blob: 'x'.repeat(500) }, 40)
    expect(long.length).toBe(41)
    expect(long.endsWith('…')).toBe(true)
    // null/undefined 值渲染成空串而不是字面量 "null"。
    expect(detailsSummary({ a: null, b: undefined })).toBe('a= · b=')
  })

  it('过滤下拉项来自已登记映射表且顺序稳定', () => {
    const options = auditActionOptions()
    expect(options.length).toBeGreaterThan(0)
    expect(
      options.every(
        (option) =>
          option.value.startsWith('ota.firmware.') || option.value.startsWith('ota.upload.')
      )
    ).toBe(true)
    const values = options.map((option) => option.value)
    expect([...values].sort()).toEqual(values)
    // 每个选项的标签必须来自映射表，不能是空串。
    expect(options.every((option) => option.label !== '' && option.label !== option.value)).toBe(
      true
    )
    expect(Object.keys(OTA_ACTION_LABELS).length).toBeGreaterThan(options.length)
  })
})
