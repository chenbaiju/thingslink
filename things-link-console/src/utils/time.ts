/**
 * 时间格式化工具
 *
 * 后端一律返回 RFC3339 UTC（如 2026-08-11T02:31:46.979534+00），
 * 格式化只在前端做。`dayjs.utc(value)` 先把字符串钉为 UTC 时刻，再显式 `.local()`
 * 转为浏览器本地时区后格式化——不加 `.local()` 会输出 UTC 墙钟（D-043，A6-01）。
 *
 * @module utils/time
 * @author Things Link Team
 */

import dayjs from 'dayjs'
import utc from 'dayjs/plugin/utc'

dayjs.extend(utc)

/** 统一日期时间格式：YYYY-MM-DD HH:mm:ss */
export const DATE_TIME_FORMAT = 'YYYY-MM-DD HH:mm:ss'

/** 统一日期格式：YYYY-MM-DD */
export const DATE_FORMAT = 'YYYY-MM-DD'

/** 统一时间格式：HH:mm:ss */
export const TIME_FORMAT = 'HH:mm:ss'

/**
 * 格式化日期时间为 YYYY-MM-DD HH:mm:ss，自动转为浏览器本地时区。
 * @param value RFC3339 UTC 字符串或任意 dayjs 可解析的时间值
 * @param placeholder 值为空时返回的占位符，默认 '—'
 */
export const formatTime = (value?: string | null, placeholder = '—'): string => {
  if (!value) return placeholder
  const d = dayjs.utc(value).local()
  return d.isValid() ? d.format(DATE_TIME_FORMAT) : placeholder
}

/**
 * 格式化日期为 YYYY-MM-DD
 */
export const formatDate = (value?: string | null, placeholder = '—'): string => {
  if (!value) return placeholder
  const d = dayjs.utc(value).local()
  return d.isValid() ? d.format(DATE_FORMAT) : placeholder
}

/**
 * 格式化为相对时间（如"3分钟前""2小时前"）
 */
export const formatRelative = (value?: string | null, placeholder = '—'): string => {
  if (!value) return placeholder
  const d = dayjs.utc(value)
  if (!d.isValid()) return placeholder
  const now = dayjs()
  const diffSec = now.diff(d, 'second')
  if (diffSec < 60) return '刚刚'
  if (diffSec < 3600) return `${Math.floor(diffSec / 60)}分钟前`
  if (diffSec < 86400) return `${Math.floor(diffSec / 3600)}小时前`
  if (diffSec < 2592000) return `${Math.floor(diffSec / 86400)}天前`
  // 超过 30 天回落绝对时间时必须显式 .local()，否则仍输出 UTC 墙钟（D-043 第三条路径）。
  return d.local().format(DATE_TIME_FORMAT)
}
