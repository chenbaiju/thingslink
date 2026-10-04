import { describe, expect, it } from 'vitest'
import { notificationStatusLabel, notificationStatusTag } from '@/utils/notificationStatus'

/** A6-08：通知状态词表必须与契约枚举一致（SUCCEEDED 而非 DELIVERED，含 SUPPRESSED_QUOTA）。 */
describe('通知投递状态映射（A6-08）', () => {
  it('契约枚举 SUCCEEDED 映射为「已送达」', () => {
    expect(notificationStatusLabel('SUCCEEDED')).toBe('已送达')
    expect(notificationStatusTag('SUCCEEDED')).toBe('success')
  })

  it('SUPPRESSED_QUOTA 有映射，不再回退英文原值', () => {
    expect(notificationStatusLabel('SUPPRESSED_QUOTA')).toBe('配额抑制')
    expect(notificationStatusTag('SUPPRESSED_QUOTA')).toBe('warning')
  })

  it('SKIPPED_AUTHORIZATION 映射为非错误终态', () => {
    expect(notificationStatusLabel('SKIPPED_AUTHORIZATION')).toBe('授权已失效')
    expect(notificationStatusTag('SKIPPED_AUTHORIZATION')).toBe('info')
  })

  it('缺状态返回中性占位', () => {
    expect(notificationStatusLabel(undefined)).toBe('—')
    expect(notificationStatusLabel('')).toBe('—')
    expect(notificationStatusTag(undefined)).toBeUndefined()
  })

  it('已废弃的 DELIVERED 按未知状态透传，不映射为已送达', () => {
    expect(notificationStatusLabel('DELIVERED')).toBe('DELIVERED')
  })
})
