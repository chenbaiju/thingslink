/**
 * 设备状态展示映射。
 *
 * 缺状态（后端异常缺字段）不得被当作「未激活」展示，否则会掩盖未知状态（A6-07）；
 * 列表与详情共用同一套映射，保持回退口径一致。
 *
 * @module utils/deviceStatus
 */

/** 设备状态中文标签。 */
export const DEVICE_STATUS_LABELS: Record<string, string> = {
  INACTIVE: '未激活',
  ONLINE: '在线',
  OFFLINE: '离线'
}

/** 设备状态 ElTag 类型。 */
export const DEVICE_STATUS_TAGS: Record<string, 'info' | 'success' | 'danger'> = {
  INACTIVE: 'info',
  ONLINE: 'success',
  OFFLINE: 'danger'
}

/**
 * 设备状态 → 中文标签；未知状态原样透传，缺状态返回中性占位符。
 * @param status 设备状态
 */
export const deviceStatusLabel = (status?: string | null): string =>
  status ? (DEVICE_STATUS_LABELS[status] ?? status) : '—'

/**
 * 设备状态 → ElTag 类型；未知或缺省一律 info。
 * @param status 设备状态
 */
export const deviceStatusTag = (status?: string | null): 'info' | 'success' | 'danger' =>
  status ? (DEVICE_STATUS_TAGS[status] ?? 'info') : 'info'
