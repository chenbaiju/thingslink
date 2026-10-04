import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { formatDate, formatRelative, formatTime } from '@/utils/time'

/**
 * D-043（A6-01）回归测试：三条绝对时间输出路径都必须把 RFC3339 UTC 转为浏览器本地时区。
 * 进程时区由 tests/setup.ts 钉死为 Asia/Shanghai（UTC+8），下面断言按该时区固定。
 */
describe('时间格式化（D-043，非 UTC 时区）', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-08-20T12:00:00Z'))
  })
  afterEach(() => {
    vi.useRealTimers()
  })

  it('formatTime 把 UTC 转为本地时区（UTC+8）', () => {
    expect(formatTime('2026-08-20T02:00:00Z')).toBe('2026-08-20 10:00:00')
  })

  it('formatDate 跨日时按本地日期显示', () => {
    expect(formatDate('2026-08-19T20:00:00Z')).toBe('2026-08-20')
  })

  it('formatRelative 近 1 小时显示相对时间', () => {
    expect(formatRelative('2026-08-20T11:00:00Z')).toBe('1小时前')
  })

  it('formatRelative 超过 30 天回落绝对时间且同样转本地时区', () => {
    expect(formatRelative('2026-06-01T00:00:00Z')).toBe('2026-06-01 08:00:00')
  })

  it('空值返回占位符，非法值返回占位符', () => {
    expect(formatTime(null)).toBe('—')
    expect(formatTime('')).toBe('—')
    expect(formatDate(undefined)).toBe('—')
    expect(formatRelative(null)).toBe('—')
    expect(formatTime('not-a-date')).toBe('—')
  })
})
