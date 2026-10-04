/**
 * OTA设备作业页的纯展示与判定逻辑。
 *
 * 作业状态闭集必须与数据库约束逐项一致（含重试等待、失败与超时），否则控制台会把真实状态
 * 显示成未知字符串；未知状态一律原样透传，绝不折叠成某个更"安全"的既有状态。
 *
 * @module features/ota/job-model
 */

import {
  emptyOtaHistory,
  mergeOtaHistoryPage,
  type OtaHistoryPage,
  type OtaHistoryState
} from './firmware-model'

/** 作业状态中文标签；闭集取自`ota_device_job`的状态约束。 */
export const JOB_STATUS_LABELS: Record<string, string> = {
  PENDING: '待准入',
  SKIPPED_INELIGIBLE: '资格不符跳过',
  DISPATCHED: '已派发',
  DOWNLOADING: '下载中',
  VERIFYING: '验签中',
  INSTALLING: '刷写中',
  REBOOTING: '重启中',
  HEALTH_CHECKING: '健康确认中',
  CONFIRMING: '提交确认中',
  SUCCEEDED: '已成功',
  ROLLBACK_PENDING: '待回退',
  ROLLING_BACK: '回退中',
  ROLLED_BACK: '已回退',
  RECOVERY_REQUIRED: '待对账恢复',
  RETRY_WAIT: '重试等待',
  FAILED: '已失败',
  TIMED_OUT: '已超时',
  CANCELLED: '已取消'
}

/** 作业状态 ElTag 类型。 */
export const JOB_STATUS_TAGS: Record<string, 'info' | 'success' | 'warning' | 'danger'> = {
  PENDING: 'info',
  SKIPPED_INELIGIBLE: 'info',
  DISPATCHED: 'info',
  DOWNLOADING: 'warning',
  VERIFYING: 'warning',
  INSTALLING: 'warning',
  REBOOTING: 'warning',
  HEALTH_CHECKING: 'warning',
  CONFIRMING: 'warning',
  SUCCEEDED: 'success',
  ROLLBACK_PENDING: 'warning',
  ROLLING_BACK: 'warning',
  ROLLED_BACK: 'info',
  RECOVERY_REQUIRED: 'danger',
  RETRY_WAIT: 'warning',
  FAILED: 'danger',
  TIMED_OUT: 'danger',
  CANCELLED: 'info'
}

/** 与数据库约束同源的闭集，供契约与界面共同校验。 */
export const JOB_STATUSES = Object.keys(JOB_STATUS_LABELS)

/** 作业状态 → 中文标签；未知状态原样透传。 */
export const jobStatusLabel = (status?: string | null): string =>
  status ? (JOB_STATUS_LABELS[status] ?? status) : '—'

/** 作业状态 → ElTag 类型；未知或缺省一律 info。 */
export const jobStatusTag = (status?: string | null): 'info' | 'success' | 'warning' | 'danger' =>
  status ? (JOB_STATUS_TAGS[status] ?? 'info') : 'info'

/** 失败归因中文提示；未知归因原样透传，不做猜测。 */
export const JOB_FAILURE_HINTS: Record<string, string> = {
  DISPATCH_TRANSIENT_FAILURE: '派发暂时失败（可按冻结预算重试）',
  DOWNLOAD_TRANSIENT_FAILURE: '下载暂时失败（可按冻结预算重试）',
  NOTIFICATION_DELIVERY_EXHAUSTED: '通知投递已耗尽',
  RETRY_BUDGET_EXHAUSTED: '重试预算已耗尽（作业进入超时终态）',
  INSTALL_STOPPED: '安装前安全停止',
  HEALTH_WINDOW_EXPIRED: '健康窗口超时',
  VERIFY_MISMATCH: '验签或摘要不一致',
  INSTALL_FAILED: '刷写失败',
  BOOT_FAILED: '启动失败',
  RECOVERY_UNRESOLVED: '槽位或安全版本对账未收敛',
  PROJECT_CLEANUP: '项目清理关闭'
}

/** 失败归因 → 中文提示；未知归因原样透传，空值返回中性占位符。 */
export const jobFailureHint = (failureCode?: string | null): string =>
  failureCode ? (JOB_FAILURE_HINTS[failureCode] ?? failureCode) : '—'

/** 作业是否已进入终态。 */
export const isJobTerminal = (status?: string | null): boolean =>
  status === 'SUCCEEDED' ||
  status === 'FAILED' ||
  status === 'TIMED_OUT' ||
  status === 'CANCELLED' ||
  status === 'SKIPPED_INELIGIBLE' ||
  status === 'ROLLED_BACK'

/**
 * 计算作业已耗费的尝试次数展示文本。
 *
 * 平台语义：`attemptNo`从1开始，首次派发即1；`RECOVERY_REQUIRED`计入尝试但**不**消耗退避预算，
 * 因此这里只显示代次，不显示"第几次重试"这类会误导的字样。
 *
 * @param attemptNo 代次
 */
export const attemptLabel = (attemptNo?: number | null): string =>
  attemptNo === undefined || attemptNo === null ? '—' : `第 ${attemptNo} 次尝试`

/** 转移记录展示文本。 */
export function transitionSummary(transition: {
  fromStatus?: string | null
  toStatus?: string | null
  reason?: string | null
}): string {
  const from = transition.fromStatus ? jobStatusLabel(transition.fromStatus) : '准入'
  const to = jobStatusLabel(transition.toStatus)
  const reason = transition.reason ? `（${transition.reason}）` : ''
  return `${from} → ${to}${reason}`
}

/**
 * 判断作业是否需要运维关注。
 *
 * 只看"是否终结失败或等待人工/系统对账"，不用它替代服务端判定。
 *
 * @param status 作业状态
 */
export const needsAttention = (status?: string | null): boolean =>
  status === 'RECOVERY_REQUIRED' || status === 'FAILED' || status === 'TIMED_OUT'

/** 设备维度作业一页的读取结果形状；与固件历史集合共用同一游标合同。 */
export type OtaDeviceJobPage<T> = OtaHistoryPage<T>

/** 设备维度作业列表的本地分页状态；`cursor`只在`hasMore`为真时有意义。 */
export type OtaDeviceJobHistoryState<T> = OtaHistoryState<T>

/** 空设备作业历史状态。 */
export const emptyOtaDeviceJobHistory = <T>(): OtaDeviceJobHistoryState<T> => emptyOtaHistory<T>()

/**
 * 合并一页设备作业事实：续页追加在已有事实之后，首页替换。
 *
 * <p>游标只从服务端原样透传，控制台不解析也不自造；`hasMore`为假时立刻清空游标。
 * 键集合并合同与固件历史集合只有一处实现（{@link mergeOtaHistoryPage}），本页不再复制，
 * 避免两个集合的游标语义各自漂移。
 *
 * @param current 当前已加载状态
 * @param page 服务端返回的一页
 * @param append 是否为续页（true 时追加，false 时替换）
 */
export function mergeOtaDeviceJobPage<T>(
  current: OtaDeviceJobHistoryState<T>,
  page: OtaDeviceJobPage<T>,
  append: boolean
): OtaDeviceJobHistoryState<T> {
  return mergeOtaHistoryPage(current, page, append)
}
