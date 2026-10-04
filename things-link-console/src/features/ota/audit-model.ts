/**
 * OTA审计时间线的纯展示逻辑。
 *
 * 审计动作编码是稳定英文常量，中文只在这里翻译；**未知动作必须原样透传**，
 * 否则新增动作会被显示成某个既有含义，排障时就会看错事实。
 *
 * @module features/ota/audit-model
 */

/** OTA动作编码 → 中文标签；只登记当前已交付的动作，未知编码原样透传。 */
export const OTA_ACTION_LABELS: Record<string, string> = {
  'ota.firmware.created': '创建固件草稿',
  'ota.firmware.cancelled': '取消固件草稿',
  'ota.firmware.deprecated': '退役固件',
  'ota.firmware.revoked': '撤销固件',
  'ota.upload.created': '创建上传会话',
  'ota.upload.receiving': '开始接收对象',
  'ota.upload.writing': '写入对象',
  'ota.upload.receipt': '对象写入回执',
  'ota.upload.verified': '对象复验通过',
  'ota.upload.failed': '对象接收或复验失败',
  'ota.upload.cancelled': '登记上传取消',
  'ota.upload.cleaned': '回收上传对象',
  'ota.upload.cleanup.requested': '请求回收上传对象',
  'ota.upload.recovery.claimed': '领取上传恢复',
  'ota.trust.bundle.imported': '导入发布信任包',
  'ota.publication.created': '建立发布尝试',
  'ota.publication.claimed': '领取发布尝试',
  'ota.publication.signed': '外部签名完成',
  'ota.publication.committed': '提交发布',
  'ota.publication.failed': '发布尝试失败',
  'ota.release.download.issued': '签发管理端下载地址',
  'ota.type.baseline.registered': '登记类型基线',
  'ota.campaign.created': '创建活动草稿',
  'ota.campaign.scheduled': '冻结目标并排程',
  'ota.campaign.admission': '运行准入',
  'ota.campaign.started': '启动活动',
  'ota.campaign.paused': '暂停活动',
  'ota.campaign.resumed': '恢复活动',
  'ota.campaign.batch.advanced': '放行下一批',
  'ota.campaign.batch.security-paused': '安全暂停批次',
  'ota.campaign.cancellation_requested': '请求取消活动',
  'ota.campaign.cancelled': '活动取消完成',
  'ota.campaign.completed': '活动完成',
  'ota.job.progress': '设备进度',
  'ota.job.health': '健康确认',
  'ota.job.commit': '安全版本提交',
  'ota.job.timeout': '作业超时',
  'ota.retry.dispatchRetry': '重试派发新尝试',
  'ota.retry.exhaustRetry': '重试预算耗尽',
  'ota.retry.cancelRetryWait': '停止先赢取消重试等待',
  'ota.rollback.preflight.query': '回退预检查询',
  'ota.rollback.preflight.result': '回退预检结果',
  'ota.rollback.operation': '回退操作',
  'ota.rollback.result': '回退结果',
  'ota.rollback.delivery': '回退投递',
  'ota.rollback.status.query': '回退状态查询',
  'ota.install_stop.created': '创建安装前停止操作',
  'ota.install_stop.delivery': '停止投递',
  'ota.install_stop.result': '停止受理结果',
  'ota.install_stop.deferred': '停止候选退避',
  'ota.install_stop.status.query': '停止状态查询',
  'ota.download_request.accepted': '受理下载申请',
  'ota.download_request.security_pause': '下载申请安全暂停',
  'ota.download.authorization': '签发下载授权',
  'ota.download.observed': '采纳下载观察',
  'ota.reconciliation.query': '建立提交对账查询',
  'ota.reconciliation.result': '提交对账结果',
  'ota.commit_permit.delivery': '提交许可投递',
  'ota.commit_permit.observed': '提交许可观察',
  'ota.notification.delivery': '通知投递',
  'ota.notification.observed': '通知观察'
}

/** 目标类型 → 中文标签；未知类型原样透传。 */
export const OTA_TARGET_LABELS: Record<string, string> = {
  ota_firmware: '固件',
  ota_upload: '上传会话',
  ota_campaign: '活动',
  ota_device_job: '设备作业',
  ota_type_baseline: '类型基线',
  ota_download_request: '下载申请',
  ota_download_authorization: '下载授权',
  ota_download_transport: '下载传输',
  ota_reconciliation_query: '提交对账查询',
  ota_rollback_preflight_query: '回退预检查询',
  project: '项目'
}

/** 动作编码 → 中文标签；未知编码原样透传。 */
export const otaActionLabel = (action?: string | null): string =>
  action ? (OTA_ACTION_LABELS[action] ?? action) : '—'

/** 目标类型 → 中文标签；未知类型原样透传。 */
export const otaTargetLabel = (targetType?: string | null): string =>
  targetType ? (OTA_TARGET_LABELS[targetType] ?? targetType) : '—'

/**
 * 把审计明细压成一行摘要。
 *
 * 只列键值对，值过长时截断；明细本身可能很长（例如计划摘要），表格里不需要全量。
 *
 * @param details 审计明细
 * @param maxLength 单行最大长度
 */
export function detailsSummary(details?: Record<string, unknown> | null, maxLength = 120): string {
  if (!details) return '—'
  const entries = Object.entries(details)
  if (entries.length === 0) return '—'
  const text = entries
    .map(([key, value]) => `${key}=${value === null || value === undefined ? '' : String(value)}`)
    .join(' · ')
  return text.length > maxLength ? `${text.slice(0, maxLength)}…` : text
}

/** 审计行上可用的过滤项；值为动作编码，空串表示"全部"。 */
export interface AuditActionOption {
  value: string
  label: string
}

/**
 * 构造过滤下拉项。
 *
 * 选项来自已登记的中文标签表而不是写死列表，新增动作时只需改映射表；
 * 排序按编码，保证下拉顺序稳定。
 *
 * @param prefixes 只保留这些前缀的动作（默认只看固件与上传）
 */
export function auditActionOptions(
  prefixes: string[] = ['ota.firmware.', 'ota.upload.']
): AuditActionOption[] {
  return Object.entries(OTA_ACTION_LABELS)
    .filter(([action]) => prefixes.some((prefix) => action.startsWith(prefix)))
    .map(([value, label]) => ({ value, label }))
    .sort((left, right) => left.value.localeCompare(right.value))
}
