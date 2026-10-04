/**
 * OTA活动与批次页的纯展示与判定逻辑。
 *
 * 页面只做渲染；状态映射、动作资格、计划草稿校验与错误分类都放在这里，便于用 vitest 覆盖
 * 那些在本机无法端到端跑到的分支（活动创建要求固件已READY，而本机没有正式signer）。
 *
 * 口径原则：未知状态原样透传；动作资格只是"界面不呈现注定失败的操作"，服务端仍是唯一裁决者。
 *
 * @module features/ota/campaign-model
 */

/** 活动状态中文标签。 */
export const CAMPAIGN_STATUS_LABELS: Record<string, string> = {
  DRAFT: '草稿',
  SCHEDULED: '已排程',
  RUNNING: '运行中',
  PAUSED: '已暂停',
  CANCELLING: '取消中',
  CANCELLED: '已取消',
  COMPLETED: '已完成'
}

/** 活动状态 ElTag 类型。 */
export const CAMPAIGN_STATUS_TAGS: Record<string, 'info' | 'success' | 'warning' | 'danger'> = {
  DRAFT: 'info',
  SCHEDULED: 'info',
  RUNNING: 'success',
  PAUSED: 'warning',
  CANCELLING: 'warning',
  CANCELLED: 'info',
  COMPLETED: 'success'
}

/** 批次状态中文标签。 */
export const BATCH_STATUS_LABELS: Record<string, string> = {
  PENDING: '待放行',
  RUNNING: '进行中',
  PAUSED: '已暂停',
  DRAINING: '收敛中',
  SUCCEEDED: '已成功',
  FAILED: '已失败',
  CANCELLING: '取消中',
  CANCELLED: '已取消'
}

/** 批次状态 ElTag 类型。 */
export const BATCH_STATUS_TAGS: Record<string, 'info' | 'success' | 'warning' | 'danger'> = {
  PENDING: 'info',
  RUNNING: 'success',
  PAUSED: 'warning',
  DRAINING: 'warning',
  SUCCEEDED: 'success',
  FAILED: 'danger',
  CANCELLING: 'warning',
  CANCELLED: 'info'
}

/** 活动状态 → 中文标签；未知状态原样透传，缺状态返回中性占位符。 */
export const campaignStatusLabel = (status?: string | null): string =>
  status ? (CAMPAIGN_STATUS_LABELS[status] ?? status) : '—'

/** 活动状态 → ElTag 类型；未知或缺省一律 info。 */
export const campaignStatusTag = (
  status?: string | null
): 'info' | 'success' | 'warning' | 'danger' =>
  status ? (CAMPAIGN_STATUS_TAGS[status] ?? 'info') : 'info'

/** 批次状态 → 中文标签；未知状态原样透传。 */
export const batchStatusLabel = (status?: string | null): string =>
  status ? (BATCH_STATUS_LABELS[status] ?? status) : '—'

/** 批次状态 → ElTag 类型；未知或缺省一律 info。 */
export const batchStatusTag = (
  status?: string | null
): 'info' | 'success' | 'warning' | 'danger' =>
  status ? (BATCH_STATUS_TAGS[status] ?? 'info') : 'info'

/** 活动动作资格；每项都对应一个服务端判定。 */
export interface CampaignActions {
  /** 草稿可原子冻结目标与批次。 */
  canSchedule: boolean
  /** 已排程且到达冻结时间后可启动。 */
  canStart: boolean
  /** 运行中可暂停。 */
  canPause: boolean
  /** 暂停后可恢复。 */
  canResume: boolean
  /** 未终结可请求取消。 */
  canCancel: boolean
  /** 人工放行下一批（仅在等待人工放行时）。 */
  canAdvance: boolean
  /** 不可操作原因；可用时为空串。 */
  reason: string
}

/**
 * 计算活动动作资格。
 *
 * @param status 活动状态
 * @param awaitingManualApproval 执行快照是否显示等待人工放行
 * @param notBeforeReached 当前时间是否已到冻结的最早启动时间
 */
export function campaignActions(
  status?: string | null,
  awaitingManualApproval = false,
  notBeforeReached = true
): CampaignActions {
  const state = status ?? ''
  const draft = state === 'DRAFT'
  const scheduled = state === 'SCHEDULED'
  const running = state === 'RUNNING'
  const paused = state === 'PAUSED'
  const terminal = state === 'CANCELLED' || state === 'COMPLETED'
  return {
    canSchedule: draft,
    canStart: scheduled && notBeforeReached,
    canPause: running,
    canResume: paused,
    // 已进入CANCELLING时取消意图已登记，再呈现按钮只会误导；服务端仍按CAS幂等处理重复请求。
    canCancel: !terminal && state !== 'CANCELLING',
    canAdvance: running && awaitingManualApproval,
    reason: draft
      ? ''
      : scheduled
        ? notBeforeReached
          ? ''
          : '尚未到冻结的notBefore时间，服务端会以70041拒绝提前启动。'
        : running || paused
          ? ''
          : state === 'CANCELLING'
            ? '取消已请求，等待已派发作业收敛到安全终态。'
            : terminal
              ? '活动已进入终态，不能再排程、启动或取消。'
              : '当前状态不接受活动操作。'
  }
}

/** 活动相关错误的稳定提示；未知码返回空串，由调用方回落到服务端文案。 */
export function campaignErrorHint(code?: number | null): string {
  switch (code) {
    case 70034:
      return '活动不存在或不属于当前项目。'
    case 70035:
      return '当前角色无权管理OTA活动（需OWNER/ADMIN）。'
    case 70036:
      return '活动状态或修订已变化，请刷新后按最新修订重试。'
    case 70037:
      return '全部目标设备必须同属一个已发布设备类型，且目标仍存在。'
    case 70038:
      return '至少一个目标设备已有未完成的OTA作业，不能重复加入活动。'
    case 70039:
      return '当前角色无权执行该运行操作。'
    case 70040:
      return '活动运行状态冲突，请刷新执行事实后重试。'
    case 70041:
      return '尚未到冻结的notBefore时间。'
    case 70021:
    case 70022:
      return '固件还没有可用的已发布产物：活动只能引用已完成签名发布的固件（本机无正式signer时发布按ADR0119 fail-closed）。'
    case 70003:
    case 70004:
      return '固件当前状态不允许建立活动（需已就绪的发布产物）。'
    default:
      return ''
  }
}

/** 计划草稿校验结果。 */
export interface CampaignDraftCheck {
  ok: boolean
  reason?: string
}

/** 计划草稿的服务端约束（与OtaCampaignPlanBody逐项一致）。 */
export interface CampaignDraft {
  firmwareId: string
  deviceIds: string[]
  batchSize: number
  notBefore: string
}

/**
 * 上传前校验：只做控制台能诚实判定的部分（固件、目标、批次大小与时间格式）。
 *
 * <p>资格、类型一致性、设备互斥与发布产物是否可用都由服务端裁决；控制台不预判，
 * 也不把本地校验通过当成可以创建成功。
 *
 * @param draft 操作者填写的计划草稿
 */
export function checkCampaignDraft(draft: CampaignDraft): CampaignDraftCheck {
  if (!draft.firmwareId) return { ok: false, reason: '请选择要发布的固件。' }
  if (draft.deviceIds.length === 0) return { ok: false, reason: '至少选择一个目标设备。' }
  if (draft.deviceIds.length > 1000) return { ok: false, reason: '目标设备最多1000个。' }
  if (new Set(draft.deviceIds).size !== draft.deviceIds.length) {
    return { ok: false, reason: '目标设备列表存在重复项。' }
  }
  if (!Number.isInteger(draft.batchSize) || draft.batchSize < 1 || draft.batchSize > 1000) {
    return { ok: false, reason: '批次大小必须是1到1000之间的整数。' }
  }
  if (!/^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$/.test(draft.notBefore)) {
    return { ok: false, reason: '最早启动时间必须是UTC秒级格式，例如2026-09-15T12:00:00Z。' }
  }
  return { ok: true }
}

/**
 * 默认执行策略。
 *
 * <p>取保守值：单批并发1、失败率阈值100%、必须人工放行每一批——这些是"更慢但更安全"的一侧；
 * 操作者可在高级设置里改成活动需要的值，服务端仍按冻结快照复核上下界。
 */
export function defaultCampaignPolicy() {
  return {
    maxConcurrentDownloads: 1,
    maxDownloadBytesPerSecond: 1_048_576,
    downloadRetryLimit: 1,
    retryBackoffSeconds: 30,
    healthWindowSeconds: 300,
    pauseMinEvaluated: 1,
    pauseFailureCount: 1,
    pauseFailureRateBps: 10_000,
    batchMinSuccessRateBps: 10_000,
    requireManualBatchApproval: true,
    stageTimeoutSeconds: {
      DISPATCHED: 300,
      DOWNLOADING: 1800,
      VERIFYING: 600,
      INSTALLING: 1800,
      REBOOTING: 600,
      HEALTH_CHECKING: 600,
      CONFIRMING: 300,
      ROLLBACK_PENDING: 900,
      ROLLING_BACK: 900
    }
  }
}

/** 批次计数汇总；用于断言"计数不超过目标数"这类可由前端诚实检查的一致性。 */
export interface BatchTotals {
  target: number
  succeeded: number
  rolledBack: number
  skipped: number
  timedOut: number
  cancelled: number
  settled: number
  /** 已结算数是否超过冻结目标数；为真说明服务端事实自相矛盾。 */
  inconsistent: boolean
}

/**
 * 汇总单个批次的真实计数。
 *
 * @param batch 批次投影
 */
export function batchTotals(batch: {
  targetCount?: number
  succeededCount?: number
  rolledBackCount?: number
  skippedCount?: number
  timedOutCount?: number
  cancelledCount?: number
}): BatchTotals {
  const target = batch.targetCount ?? 0
  const succeeded = batch.succeededCount ?? 0
  const rolledBack = batch.rolledBackCount ?? 0
  const skipped = batch.skippedCount ?? 0
  const timedOut = batch.timedOutCount ?? 0
  const cancelled = batch.cancelledCount ?? 0
  const settled = succeeded + rolledBack + skipped + timedOut + cancelled
  return {
    target,
    succeeded,
    rolledBack,
    skipped,
    timedOut,
    cancelled,
    settled,
    inconsistent: settled > target
  }
}

/**
 * 把界面输入的本地时间转成服务端要求的UTC秒级字符串。
 *
 * @param local 本地时间
 */
export function toNotBeforeUtc(local: Date): string {
  return `${new Date(local.getTime()).toISOString().slice(0, 19)}Z`
}
