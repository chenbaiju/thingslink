import type { OtaRollbackPreflightResponse } from '@/api/ota'
import { otaMetadataUuid } from './metadata-model'

const fields = [
  'queryId',
  'reportId',
  'observedDisposition',
  'observedReason',
  'currentDisposition',
  'currentReason',
  'observedAt',
  'queryExpiresAt',
  'checkedAt',
  'operationRevision',
  'sourceSlot',
  'targetSlot',
  'executionAuthorized',
  'requiresAtomicCommitFence'
]
  .sort()
  .join(',')
const disposition = (value: unknown) =>
  ['PREPARABLE', 'INELIGIBLE', 'UNKNOWN'].includes(String(value))
const reason = (value: unknown) => typeof value === 'string' && /^[A-Z][A-Z0-9_]{0,95}$/.test(value)
function utc(value: unknown): value is string {
  return (
    typeof value === 'string' &&
    /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/.test(value) &&
    Number.isFinite(Date.parse(value)) &&
    new Date(value).toISOString().slice(0, 19) === value.slice(0, 19)
  )
}
/** 已验证的四位年UTC按九位小数规范化，时序比较不能截断观察与重验时间的纳秒。 */
function utcNanosecondKey(value: string): string {
  const fraction = value.slice(19, -1)
  return value.slice(0, 19) + (fraction.startsWith('.') ? fraction.slice(1) : '').padEnd(9, '0')
}
/** 只接受当前公开DTO闭集；能力常量不可缺失、默认或被翻转。 */
export function isOtaRollbackPreflight(value: unknown): value is OtaRollbackPreflightResponse {
  if (
    !value ||
    typeof value !== 'object' ||
    Array.isArray(value) ||
    Object.keys(value).sort().join(',') !== fields
  )
    return false
  const v = value as Record<string, unknown>
  return (
    otaMetadataUuid(v.queryId) &&
    otaMetadataUuid(v.reportId) &&
    typeof v.observedDisposition === 'string' &&
    disposition(v.observedDisposition) &&
    reason(v.observedReason) &&
    typeof v.currentDisposition === 'string' &&
    disposition(v.currentDisposition) &&
    reason(v.currentReason) &&
    utc(v.observedAt) &&
    utc(v.checkedAt) &&
    utc(v.queryExpiresAt) &&
    utcNanosecondKey(v.checkedAt) >= utcNanosecondKey(v.observedAt) &&
    typeof v.operationRevision === 'string' &&
    /^(0|[1-9][0-9]{0,15})$/.test(v.operationRevision) &&
    BigInt(v.operationRevision) <= 9007199254740991n &&
    (v.sourceSlot === 'A' || v.sourceSlot === 'B') &&
    (v.targetSlot === 'A' || v.targetSlot === 'B') &&
    v.sourceSlot !== v.targetSlot &&
    v.executionAuthorized === false &&
    v.requiresAtomicCommitFence === true
  )
}
export const rollbackPreflightDispositionLabel = (
  value: OtaRollbackPreflightResponse['currentDisposition']
) => ({ PREPARABLE: '可准备', INELIGIBLE: '不可准备', UNKNOWN: '未知' })[value]
const reasons: Record<string, string> = {
  ATOMIC_FENCE_STILL_REQUIRED: '仍需原子提交互斥，尚无执行权',
  QUERY_NOT_CURRENT_OR_FRESH: '原查询或报告已非当前且新鲜的观察',
  CURRENT_IDENTITY_CHANGED: '当前设备身份已变化',
  CONTROLLED_BASELINE_CHANGED_OR_MISSING: '受控基线已变化或缺失',
  CURRENT_MODEL_CHANGED: '当前物模型已变化',
  SOURCE_AB_CAPABILITY_MISSING: '原来源缺少双槽能力',
  HARDWARE_MISMATCH: '硬件声明不匹配',
  BOOTLOADER_OUTSIDE_CONTROLLED_PROFILE: '引导程序超出受控配置',
  COMMITTED_COUNTER_CHANGED: '已提交安全计数已变化',
  KNOWN_SECURITY_FLOOR_CONFLICT: '已知安全下限冲突',
  SOURCE_SLOT_TUPLE_MISMATCH: '原来源槽元组不匹配',
  SOURCE_SLOT_UNCERTAIN: '原来源槽完整性或健康状态未知',
  SOURCE_SLOT_NOT_BOOTABLE_AND_HEALTHY: '原来源槽不满足可启动及健康要求',
  WRITER_STATE_UNKNOWN: '写入方状态未知',
  WRITER_NOT_QUIESCENT: '写入方尚未静止',
  ATOMIC_PROFILE_MISMATCH: '原子操作配置不匹配',
  JOURNAL_STATE_UNKNOWN: '设备日志状态未知',
  JOURNAL_HAS_ACTIVE_OPERATION: '设备日志存在活动操作',
  COMMIT_JOURNAL_PERMIT_MISMATCH: '提交日志与许可不匹配',
  COMMIT_OPERATION_UNKNOWN: '提交操作状态未知',
  COMMIT_OPERATION_NOT_SAFE_TO_PREPARE: '提交操作不满足安全准备条件'
}
/** 未知稳定原因仍保留原码，不能推断成某个已知安全分类。 */
export const rollbackPreflightReasonLabel = (value: string) =>
  reasons[value] ?? '服务端返回新的稳定原因，请核对协议'
/** 浏览器到原截止或服务端当次重验指出时效已失；不自行重分类服务端快照。 */
export const rollbackPreflightStale = (value: OtaRollbackPreflightResponse, now = Date.now()) =>
  value.currentReason === 'QUERY_NOT_CURRENT_OR_FRESH' ||
  Date.parse(value.queryExpiresAt) <= Math.max(now, Date.parse(value.checkedAt))
