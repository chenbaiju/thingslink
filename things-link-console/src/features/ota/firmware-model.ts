/**
 * OTA固件页的纯展示与判定逻辑。
 *
 * 页面只做渲染，状态映射、动作资格与上传前校验都放在这里，便于用 vitest 覆盖
 * 那些在无signer环境里无法端到端跑到的分支（例如VERIFYING/READY之后的退役与撤销）。
 *
 * 口径原则：未知状态原样透传而不是折叠成某个已知状态——把后端新增的合法状态显示成
 * 「草稿」会让运维以为可以上传，而服务端会拒绝。
 *
 * @module features/ota/firmware-model
 */

/** 固件状态中文标签。 */
export const FIRMWARE_STATUS_LABELS: Record<string, string> = {
  DRAFT: '草稿',
  VERIFYING: '验签中',
  READY: '已就绪',
  CANCELLED: '已取消',
  DEPRECATED: '已退役',
  REVOKED: '已撤销'
}

/** 固件状态 ElTag 类型。 */
export const FIRMWARE_STATUS_TAGS: Record<string, 'info' | 'success' | 'warning' | 'danger'> = {
  DRAFT: 'info',
  VERIFYING: 'warning',
  READY: 'success',
  CANCELLED: 'info',
  DEPRECATED: 'warning',
  REVOKED: 'danger'
}

/** 上传会话状态中文标签。 */
export const UPLOAD_STATUS_LABELS: Record<string, string> = {
  PENDING: '待接收',
  RECEIVING: '接收中',
  VERIFYING: '对象复验中',
  VERIFIED: '对象已复验',
  FAILED: '接收失败',
  CANCELLED: '已取消',
  CLEANED: '已清理'
}

/** 固件状态 → 中文标签；未知状态原样透传，缺状态返回中性占位符。 */
export const firmwareStatusLabel = (status?: string | null): string =>
  status ? (FIRMWARE_STATUS_LABELS[status] ?? status) : '—'

/** 固件状态 → ElTag 类型；未知或缺省一律 info。 */
export const firmwareStatusTag = (
  status?: string | null
): 'info' | 'success' | 'warning' | 'danger' =>
  status ? (FIRMWARE_STATUS_TAGS[status] ?? 'info') : 'info'

/** 上传会话状态 → 中文标签；未知状态原样透传。 */
export const uploadStatusLabel = (status?: string | null): string =>
  status ? (UPLOAD_STATUS_LABELS[status] ?? status) : '—'

/** 单次上传的固件长度上限，与上传会话`expectedLength`上界和契约一致。 */
export const OTA_MAX_ARTIFACT_BYTES = 67_108_864

/** 人类可读字节数；用于限定提示与表格展示。 */
export function formatBytes(bytes?: number | null): string {
  if (bytes === undefined || bytes === null || Number.isNaN(bytes)) return '—'
  if (bytes < 1024) return `${bytes} B`
  const units = ['KiB', 'MiB', 'GiB']
  let value = bytes / 1024
  let unit = 0
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024
    unit += 1
  }
  return `${value.toFixed(value >= 100 ? 0 : 1)} ${units[unit]}`
}

/** 上传前校验结果；`reason`只在`ok`为false时有意义。 */
export interface ArtifactCheck {
  ok: boolean
  reason?: string
}

/**
 * 上传前校验：只做控制台能诚实判定的部分（非空、长度上界）。
 *
 * 摘要是否满足服务端`expectedSha256`由服务端复验，控制台不声称自己已验证签名、
 * 硬件兼容或安全版本——那些属于受控signer与设备侧证据。
 *
 * @param size 固件字节数
 */
export function checkArtifactSize(size: number): ArtifactCheck {
  if (!Number.isFinite(size) || size <= 0) return { ok: false, reason: '请选择非空的固件文件。' }
  if (size > OTA_MAX_ARTIFACT_BYTES) {
    return {
      ok: false,
      reason: `固件长度 ${formatBytes(size)} 超过单次上界 ${formatBytes(OTA_MAX_ARTIFACT_BYTES)}。`
    }
  }
  return { ok: true }
}

/** 固件动作资格；每一项都对应一个服务端判定，界面只是避免呈现注定失败的操作。 */
export interface FirmwareActions {
  /** 可创建上传会话并上传对象。 */
  canUpload: boolean
  /** 可提交发布尝试（还需外部受控signer）。 */
  canPublish: boolean
  /** 可取消未完成的草稿。 */
  canCancel: boolean
  /** 可退役（仅READY）。 */
  canDeprecate: boolean
  /** 可撤销（仅READY/DEPRECATED）。 */
  canRevoke: boolean
  /** 不可用原因；可用时为空串。 */
  reason: string
}

/**
 * 计算固件动作资格。
 *
 * @param status 固件状态
 * @param hasUploadSession 是否已有未取消的上传会话
 */
export function firmwareActions(status?: string | null, hasUploadSession = false): FirmwareActions {
  const state = status ?? ''
  const upload = state === 'DRAFT'
  return {
    canUpload: upload,
    canPublish: state === 'DRAFT' && hasUploadSession,
    canCancel: state === 'DRAFT',
    canDeprecate: state === 'READY',
    canRevoke: state === 'READY' || state === 'DEPRECATED',
    reason:
      state === 'DRAFT'
        ? hasUploadSession
          ? ''
          : '需先完成一次对象上传与复验，才能提交发布尝试。'
        : state === 'VERIFYING'
          ? '发布尝试已建立，等待外部受控signer与对象采纳结果。'
          : state === 'READY'
            ? ''
            : state === 'CANCELLED' || state === 'DEPRECATED' || state === 'REVOKED'
              ? '该固件已进入终态，不能再次上传、发布或取消。'
              : '当前状态不接受固件操作。'
  }
}

/**
 * 判断发布失败是不是"环境/配置边界"而不是可以靠改表单解决的业务错误。
 *
 * <p>两种事实都按ADR0119 fail-closed处理且都不产生READY事实：
 * `70010`表示本项目还没有导入受控发布信任（离线根签名bundle未登记），
 * `70016`表示已有信任但没有受控signer适配器。控制台必须把它们显示成环境边界，
 * 既不能提示"重试即可成功"，也不能把它们与`70017`内容校验失败混为一谈。
 *
 * @param code 服务端错误码
 * @param status HTTP状态码
 */
export function publicationEnvironmentBoundary(
  code?: number | null,
  status?: number | null
): 'TRUST_NOT_CONFIGURED' | 'SIGNER_UNAVAILABLE' | null {
  if (code === 70010) return 'TRUST_NOT_CONFIGURED'
  if (code === 70016) return 'SIGNER_UNAVAILABLE'
  if (status === 503 && (code === undefined || code === null)) return 'SIGNER_UNAVAILABLE'
  return null
}

/** 上传结果未知时的稳定提示；与已拒绝区分，避免误导为"可以随便重传"。 */
export const UPLOAD_OUTCOME_UNKNOWN_HINT =
  '上传结果未知：请刷新读取会话权威状态，确认未复验后再用同一会话重传，不要新建会话。'

/**
 * `tc-ota-manifest/v1` 的必填字段。
 *
 * <p>清单是由发布流程在外部分发链上冻结并签名的工件，平台不代为编造：控制台只做
 * 形状校验后原样提交，签名、指纹与安全版本的真实性由受控signer与设备侧验证。
 */
export const OTA_MANIFEST_REQUIRED_FIELDS = [
  'contractVersion',
  'firmwareId',
  'firmwareVersion',
  'trustDomain',
  'deviceTypeId',
  'productKey',
  'hardware',
  'bootloaderMinimumVersion',
  'artifactSize',
  'artifactSha256',
  'compression',
  'delta',
  'securityVersion',
  'thingModelVersionId',
  'thingModelSchemaDigestAlgorithm',
  'thingModelSchemaDigest',
  'allowedSourceThingModelVersionIds',
  'requirements',
  'signatureProfile',
  'signingKeyFingerprint',
  'minimumTrustBundleVersion'
] as const

/** 清单文本解析结果。 */
export interface ManifestParseResult {
  ok: boolean
  manifest?: Record<string, unknown>
  reason?: string
}

/**
 * 解析并形状校验操作者粘贴的 manifest。
 *
 * <p>控制台不补齐缺省值、不推断安全字段：缺失字段必须由发布流程补全后重新提交，
 * 否则界面就在替外部签名合同做假设。
 *
 * @param text 操作者粘贴的JSON文本
 */
export function parseManifestInput(text: string): ManifestParseResult {
  const trimmed = text.trim()
  if (trimmed === '') return { ok: false, reason: '请粘贴发布流程冻结的manifest JSON。' }
  let parsed: unknown
  try {
    parsed = JSON.parse(trimmed)
  } catch {
    return { ok: false, reason: 'manifest不是合法JSON。' }
  }
  if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
    return { ok: false, reason: 'manifest必须是一个JSON对象。' }
  }
  const manifest = parsed as Record<string, unknown>
  if (manifest.contractVersion !== 'tc-ota-manifest/v1') {
    return { ok: false, reason: 'contractVersion必须是tc-ota-manifest/v1。' }
  }
  const missing = OTA_MANIFEST_REQUIRED_FIELDS.filter((field) => manifest[field] === undefined)
  if (missing.length > 0) {
    return { ok: false, reason: `manifest缺少必填字段：${missing.join('、')}。` }
  }
  return { ok: true, manifest }
}

/**
 * 历史集合一页的读取结果形状。
 *
 * <p>只声明固件页真正消费的三个字段，避免把服务端游标结构复制到控制台。
 */
export interface OtaHistoryPage<T> {
  items?: T[] | null
  nextCursor?: string | null
  hasMore?: boolean
}

/** 历史列表的本地分页状态；`cursor`只在`hasMore`为真时有意义。 */
export interface OtaHistoryState<T> {
  items: T[]
  cursor: string
  hasMore: boolean
}

/** 空历史状态。 */
export const emptyOtaHistory = <T>(): OtaHistoryState<T> => ({
  items: [],
  cursor: '',
  hasMore: false
})

/**
 * 合并一页历史事实：续页追加在已有事实之后，首页替换。
 *
 * <p>游标只从服务端原样透传，控制台不解析也不自造；`hasMore`为假时立刻清空游标，
 * 避免用一个已到末页的游标继续请求。
 *
 * @param current 当前已加载状态
 * @param page 服务端返回的一页
 * @param append 是否为续页（true 时追加，false 时替换）
 */
export function mergeOtaHistoryPage<T>(
  current: OtaHistoryState<T>,
  page: OtaHistoryPage<T>,
  append: boolean
): OtaHistoryState<T> {
  const incoming = page.items ?? []
  const items = append ? [...current.items, ...incoming] : incoming
  const hasMore = page.hasMore === true
  return { items, cursor: hasMore ? (page.nextCursor ?? '') : '', hasMore }
}
