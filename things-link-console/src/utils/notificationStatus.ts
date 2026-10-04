import type { components } from '@/types/api/schema'

/**
 * 通知投递状态展示映射，键类型直接派生自 OpenAPI 契约的
 * `AlarmNotificationDeliveryResponse.status`，而非 `Record<string, ...>`：
 * 后端新增/改名/删除状态枚举值时，本映射缺键或多键都会**编译失败**，杜绝 A6-08 的词表漂移复发。
 *
 * 修复 A6-08：契约枚举是 `SUCCEEDED` 而非前端手写的 `DELIVERED`，且缺 `SUPPRESSED_QUOTA`。
 *
 * @module utils/notificationStatus
 */

type NotificationDeliveryStatus = NonNullable<
  components['schemas']['AlarmNotificationDeliveryResponse']['status']
>

/** 通知投递状态中文标签。 */
export const NOTIFICATION_STATUS_LABELS: Record<NotificationDeliveryStatus, string> = {
  QUEUED: '排队中',
  SENDING: '发送中',
  SUCCEEDED: '已送达',
  RETRY_SCHEDULED: '待重试',
  DEAD_LETTER: '永久失败',
  SUPPRESSED_QUOTA: '配额抑制',
  TEMPLATE_INVALID: '模板非法',
  SKIPPED_AUTHORIZATION: '授权已失效'
}

/** 通知投递状态 ElTag 类型。 */
export const NOTIFICATION_STATUS_TAGS: Record<
  NotificationDeliveryStatus,
  'info' | 'warning' | 'success' | 'danger'
> = {
  QUEUED: 'info',
  SENDING: 'warning',
  SUCCEEDED: 'success',
  RETRY_SCHEDULED: 'warning',
  DEAD_LETTER: 'danger',
  SUPPRESSED_QUOTA: 'warning',
  TEMPLATE_INVALID: 'danger',
  SKIPPED_AUTHORIZATION: 'info'
}

/**
 * 通知投递状态 → 中文标签；未知状态原样透传，缺状态返回中性占位符。
 * @param value 契约状态枚举值
 */
export const notificationStatusLabel = (value?: string): string =>
  value ? (NOTIFICATION_STATUS_LABELS[value as NotificationDeliveryStatus] ?? value) : '—'

/**
 * 通知投递状态 → ElTag 类型；未知或缺省返回 undefined，交给 ElTag 默认类型。
 * @param value 契约状态枚举值
 */
export const notificationStatusTag = (
  value?: string
): 'info' | 'warning' | 'success' | 'danger' | undefined =>
  value ? NOTIFICATION_STATUS_TAGS[value as NotificationDeliveryStatus] : undefined
