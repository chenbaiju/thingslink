import request from '@/utils/http'
import type { components } from '@/types/api/schema'
import { useUserStore } from '@/store/modules/user'

/**
 * OTA固件管理API。
 *
 * 类型全部来自生成的权威契约（`pnpm api:generate`），不手写DTO；写操作带项目公共
 * `Idempotency-Key`，由调用方用 `IdempotentSubmission` 生成并在结果未知时复用同一个键。
 *
 * 上传正文是唯一的非JSON请求：`PUT .../uploads/{sessionId}/content` 声明
 * `application/octet-stream`，因此走原始 `fetch`，不复用会把正文当JSON序列化的请求封装。
 * 该端点不需要幂等键——上传会话本身已经是幂等边界，重复上传同一会话由服务端按修订判定。
 */
export type OtaFirmwareResponse = components['schemas']['OtaFirmwareResponse']
export type OtaFirmwareCreateRequest = components['schemas']['OtaFirmwareCreateRequest']
export type OtaFirmwareCancelRequest = components['schemas']['OtaFirmwareCancelRequest']
export type OtaFirmwareLifecycleResponse = components['schemas']['OtaFirmwareLifecycleResponse']
export type OtaFirmwareLifecycleChange = components['schemas']['OtaFirmwareLifecycleChange']
export type OtaUploadResponse = components['schemas']['OtaUploadResponse']
export type OtaUploadCreateRequest = components['schemas']['OtaUploadCreateRequest']
export type OtaUploadCancelRequest = components['schemas']['OtaUploadCancelRequest']
export type OtaPublicationCreateRequest = components['schemas']['OtaPublicationCreateRequest']
export type OtaPublicationResponse = components['schemas']['OtaPublicationResponse']
export type OtaReleaseDownloadResponse = components['schemas']['OtaReleaseDownloadResponse']
export type ThingModelVersionResponse = components['schemas']['ThingModelVersionResponse']
export type OtaCampaignSummaryResponse = components['schemas']['OtaCampaignSummaryResponse']
export type OtaCampaignBatchResponse = components['schemas']['OtaCampaignBatchResponse']
export type OtaCampaignResponse = components['schemas']['OtaCampaignResponse']
export type OtaCampaignExecutionResponse = components['schemas']['OtaCampaignExecutionResponse']
export type OtaCampaignPlanBody = components['schemas']['OtaCampaignPlanBody']
export type OtaDeviceEligibilityResponse = components['schemas']['OtaDeviceEligibilityResponse']

/** 服务端允许的最大固件长度，与上传会话的 `expectedLength` 上界一致。 */
export const OTA_MAX_ARTIFACT_BYTES = 67_108_864

const firmwares = (projectId: string) =>
  `/api/v1/projects/${encodeURIComponent(projectId)}/ota/firmwares` as const
const firmware = (projectId: string, firmwareId: string) =>
  `${firmwares(projectId)}/${encodeURIComponent(firmwareId)}` as const

/** 游标分页读取固件；服务端每页上限100。 */
export function fetchOtaFirmwares(projectId: string, cursor?: string, limit = 50) {
  return request.get<components['schemas']['CursorPageOtaFirmwareResponse']>({
    url: firmwares(projectId),
    params: { limit, ...(cursor === undefined || cursor === '' ? {} : { cursor }) },
    showErrorMessage: false
  })
}

/** 读取单个固件。 */
export function fetchOtaFirmware(projectId: string, firmwareId: string) {
  return request.get<OtaFirmwareResponse>({
    url: firmware(projectId, firmwareId),
    showErrorMessage: false
  })
}

/** 创建或按领域映射恢复同一未取消草稿。 */
export function createOtaFirmware(
  projectId: string,
  body: OtaFirmwareCreateRequest,
  idempotencyKey: string
) {
  return request.post<OtaFirmwareResponse>({
    url: firmwares(projectId),
    params: body,
    headers: { 'Idempotency-Key': idempotencyKey }
  })
}

/** 取消未上传完成的固件草稿。 */
export function cancelOtaFirmware(
  projectId: string,
  firmwareId: string,
  body: OtaFirmwareCancelRequest,
  idempotencyKey: string
) {
  return request.post<OtaFirmwareResponse>({
    url: `${firmware(projectId, firmwareId)}/cancel`,
    params: body,
    headers: { 'Idempotency-Key': idempotencyKey }
  })
}

/** 读取退役/撤销生命周期记录。 */
export function fetchOtaFirmwareLifecycle(projectId: string, firmwareId: string) {
  return request.get<OtaFirmwareLifecycleResponse>({
    url: `${firmware(projectId, firmwareId)}/lifecycle`,
    showErrorMessage: false
  })
}

/** READY单向退役。 */
export function deprecateOtaFirmware(
  projectId: string,
  firmwareId: string,
  body: OtaFirmwareLifecycleChange,
  idempotencyKey: string
) {
  return request.post<OtaFirmwareLifecycleResponse>({
    url: `${firmware(projectId, firmwareId)}/deprecations`,
    params: body,
    headers: { 'Idempotency-Key': idempotencyKey }
  })
}

/** READY/DEPRECATED单向撤销。 */
export function revokeOtaFirmware(
  projectId: string,
  firmwareId: string,
  body: OtaFirmwareLifecycleChange,
  idempotencyKey: string
) {
  return request.post<OtaFirmwareLifecycleResponse>({
    url: `${firmware(projectId, firmwareId)}/revocations`,
    params: body,
    headers: { 'Idempotency-Key': idempotencyKey }
  })
}

/** 创建或恢复上传会话；同一固件同时只有一个未取消会话。 */
export function createOtaUpload(
  projectId: string,
  firmwareId: string,
  body: OtaUploadCreateRequest,
  idempotencyKey: string
) {
  return request.post<OtaUploadResponse>({
    url: `${firmware(projectId, firmwareId)}/uploads`,
    params: body,
    headers: { 'Idempotency-Key': idempotencyKey }
  })
}

/** 读取上传会话当前状态（领取只读事实，不推进状态）。 */
export function fetchOtaUpload(projectId: string, firmwareId: string, sessionId: string) {
  return request.get<OtaUploadResponse>({
    url: `${firmware(projectId, firmwareId)}/uploads/${encodeURIComponent(sessionId)}`,
    showErrorMessage: false
  })
}

/** 登记上传取消；不声明物理对象已回收。 */
export function cancelOtaUpload(
  projectId: string,
  firmwareId: string,
  sessionId: string,
  body: OtaUploadCancelRequest,
  idempotencyKey: string
) {
  return request.post<OtaUploadResponse>({
    url: `${firmware(projectId, firmwareId)}/uploads/${encodeURIComponent(sessionId)}/cancel`,
    params: body,
    headers: { 'Idempotency-Key': idempotencyKey }
  })
}

/** 提交发布尝试；无受控signer时服务端503拒绝且不产生READY事实。 */
export function createOtaPublication(
  projectId: string,
  firmwareId: string,
  body: OtaPublicationCreateRequest,
  idempotencyKey: string
) {
  return request.post<OtaPublicationResponse>({
    url: `${firmware(projectId, firmwareId)}/publications`,
    params: body,
    headers: { 'Idempotency-Key': idempotencyKey }
  })
}

/** 读取发布尝试结果。 */
export function fetchOtaPublication(projectId: string, firmwareId: string, publicationId: string) {
  return request.get<OtaPublicationResponse>({
    url: `${firmware(projectId, firmwareId)}/publications/${encodeURIComponent(publicationId)}`,
    showErrorMessage: false
  })
}

/** 游标分页读取固件的历史上传会话（最新在前）；服务端每页上限100。 */
export function fetchOtaUploadHistory(
  projectId: string,
  firmwareId: string,
  options: { cursor?: string; limit?: number } = {}
) {
  const cursor = options.cursor ?? ''
  return request.get<components['schemas']['CursorPageOtaUploadResponse']>({
    url: `${firmware(projectId, firmwareId)}/uploads`,
    params: { limit: options.limit ?? 50, ...(cursor === '' ? {} : { cursor }) },
    showErrorMessage: false
  })
}

/** 游标分页读取固件的历史发布尝试（最新在前）；服务端每页上限100。 */
export function fetchOtaPublicationHistory(
  projectId: string,
  firmwareId: string,
  options: { cursor?: string; limit?: number } = {}
) {
  const cursor = options.cursor ?? ''
  return request.get<components['schemas']['CursorPageOtaPublicationResponse']>({
    url: `${firmware(projectId, firmwareId)}/publications`,
    params: { limit: options.limit ?? 50, ...(cursor === '' ? {} : { cursor }) },
    showErrorMessage: false
  })
}

/** 申领固定版本短时下载地址；只用于管理端核验，不作为设备下载入口。 */
export function createOtaReleaseDownload(
  projectId: string,
  firmwareId: string,
  idempotencyKey: string
) {
  return request.post<OtaReleaseDownloadResponse>({
    url: `${firmware(projectId, firmwareId)}/release/downloads`,
    headers: { 'Idempotency-Key': idempotencyKey }
  })
}

/** 读取设备类型最新的已发布物模型版本；未发布时服务端以30052拒绝。 */
export function fetchLatestThingModelVersion(projectId: string, deviceTypeId: string) {
  return request.get<ThingModelVersionResponse>({
    url: `/api/v1/projects/${encodeURIComponent(projectId)}/device-types/${encodeURIComponent(deviceTypeId)}/thing-model-versions/latest`,
    showErrorMessage: false
  })
}

/* ---------- 类型基线版本历史（S13-4e-7） ---------- */

/**
 * 类型基线版本历史条目：只有版本、规范摘要与登记时刻。
 *
 * 服务端刻意不复用管理读取的 `OtaTypeBaselineResponse`：后者内嵌完整受控声明，
 * 含只由启动配置接受的根指纹、产品标识、信任域与能力上限。历史集合走 `ota:read`
 * （任意项目成员可读），因此响应面必须更窄；需要完整声明时走管理读取入口。
 */
export type OtaTypeBaselineVersionResponse = components['schemas']['OtaTypeBaselineVersionResponse']

/** 类型基线地址前缀；父类型不存在或跨项目是404/70031。 */
const otaDeviceTypeBaseline = (projectId: string, deviceTypeId: string) =>
  `/api/v1/projects/${encodeURIComponent(projectId)}/ota/device-types/${encodeURIComponent(deviceTypeId)}/baseline` as const

/**
 * 游标分页读取类型基线的不可变版本历史（最新在前）。
 *
 * 服务端按 `created_at` 与 `baseline_version` 倒序：`ota_type_baseline_version` 没有独立
 * `id` 列，同类型内严格单调的版本号就是它的真实并列键；每页上限100。空历史是200空页，
 * 父类型不存在或跨项目是404（服务端70031）。
 */
export function fetchOtaBaselineVersions(
  projectId: string,
  deviceTypeId: string,
  options: { cursor?: string; limit?: number } = {}
) {
  const cursor = options.cursor ?? ''
  return request.get<components['schemas']['CursorPageOtaTypeBaselineVersionResponse']>({
    url: `${otaDeviceTypeBaseline(projectId, deviceTypeId)}/versions`,
    params: { limit: options.limit ?? 50, ...(cursor === '' ? {} : { cursor }) },
    showErrorMessage: false
  })
}

/* ---------- 信任域与发布键公开元数据（S13-4e-8） ---------- */

/**
 * 信任域公开摘要：域身份、包指针与当前ACTIVE发布键摘要。
 *
 * 服务端新建该DTO而不是复用 `OtaTrustResponse`：后者只有离线根字段，没有当前发布键的
 * 版本与指纹，而这两项正是渲染密钥表的最小事实。域级字段名与既有单域读取逐字一致。
 * 响应永不含规范包字节、签名字节、`policyHash` 或租户/项目标识。
 */
export type OtaTrustDomainSummaryResponse = components['schemas']['OtaTrustDomainSummaryResponse']

/**
 * 发布键公开元数据：只有 keyVersion、state、signatureProfile、fingerprint 与有效期窗口。
 *
 * 服务端刻意不返回 SPKI、规范字节或任何可改变键状态的字段；本片也没有 rotate/retire/import
 * 入口——密钥状态变更属于独立安全切片，控制台只读不写。
 * `notBefore`/`notAfter` 是UTC epoch秒（与 `tc-ota-trust-bundle/v1` 冻结语义一致）。
 */
export type OtaTrustKeyResponse = components['schemas']['OtaTrustKeyResponse']

/** 项目信任域集合前缀；无域的项目是200空页。 */
const otaTrustDomains = (projectId: string) =>
  `/api/v1/projects/${encodeURIComponent(projectId)}/ota/trust-domains` as const

/** 单个信任域地址前缀；未知或跨项目域是404/70013。 */
const otaTrustDomain = (projectId: string, trustDomain: string) =>
  `${otaTrustDomains(projectId)}/${encodeURIComponent(trustDomain)}` as const

/**
 * 游标分页读取本项目全部信任域摘要（按域名升序）。
 *
 * 服务端按 `trust_domain`（全局主键，项目内唯一）做键集分页，每页上限100；
 * 无域的项目返回200空页。响应只含公开摘要，不含规范包、签名或私钥材料。
 */
export function fetchOtaTrustDomains(
  projectId: string,
  options: { cursor?: string; limit?: number } = {}
) {
  const cursor = options.cursor ?? ''
  return request.get<components['schemas']['CursorPageOtaTrustDomainSummaryResponse']>({
    url: otaTrustDomains(projectId),
    params: { limit: options.limit ?? 50, ...(cursor === '' ? {} : { cursor }) },
    showErrorMessage: false
  })
}

/**
 * 游标分页读取指定信任域当前包的逐键公开元数据（按 keyVersion 升序）。
 *
 * 键冻结在不可变包字节里（每包最多64键），服务端在应用层分页；游标绑定
 * project/domain/bundleVersion/keyVersion，包轮换或跨域复用会被服务端以400/10001拒绝，
 * 调用方据此从首页重新加载而不是拼接两代键。未知或跨项目域是404/70013。
 */
export function fetchOtaTrustKeys(
  projectId: string,
  trustDomain: string,
  options: { cursor?: string; limit?: number } = {}
) {
  const cursor = options.cursor ?? ''
  return request.get<components['schemas']['CursorPageOtaTrustKeyResponse']>({
    url: `${otaTrustDomain(projectId, trustDomain)}/keys`,
    params: { limit: options.limit ?? 50, ...(cursor === '' ? {} : { cursor }) },
    showErrorMessage: false
  })
}

/** 非JSON正文上传的错误分类；`outcomeUnknown` 为真时不得自动重试新键。 */
export class OtaUploadError extends Error {
  constructor(
    message: string,
    readonly code: number | undefined,
    readonly status: number | undefined,
    readonly outcomeUnknown: boolean
  ) {
    super(message)
  }
}

/** 计算浏览器端SHA-256小写十六进制，与服务端 `expectedSha256` 口径一致。 */
export async function sha256Hex(content: ArrayBuffer): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', content)
  return Array.from(new Uint8Array(digest))
    .map((byte) => byte.toString(16).padStart(2, '0'))
    .join('')
}

/**
 * 以八位字节流上传固件内容。
 *
 * <p>响应在服务端是"上传对象复验状态"：200携带会话快照，失败携带统一错误体。
 * 网络断开、超时与被拒绝是三种不同的事实——只有明确的4xx/5xx响应才分类为已拒绝，
 * 其余一律按结果未知处理，交由调用方用同一会话读取权威状态，而不是自动重发。
 *
 * @param projectId 项目标识
 * @param firmwareId 固件标识
 * @param sessionId 上传会话标识
 * @param content 固件字节
 * @param timeoutMillis 单次上传上限，超时后按结果未知处理
 * @returns 服务端复验后的会话快照
 */
export async function uploadOtaContent(
  projectId: string,
  firmwareId: string,
  sessionId: string,
  content: ArrayBuffer,
  timeoutMillis = 300_000
): Promise<OtaUploadResponse> {
  const token = useUserStore().accessToken
  if (!token) throw new OtaUploadError('请先登录。', 401, 401, false)
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), timeoutMillis)
  const url =
    `${import.meta.env.VITE_API_URL.replace(/\/$/, '')}` +
    `${firmware(projectId, firmwareId)}/uploads/${encodeURIComponent(sessionId)}/content`
  try {
    const response = await fetch(url, {
      method: 'PUT',
      headers: {
        Authorization: `Bearer ${token}`,
        'Content-Type': 'application/octet-stream',
        Accept: 'application/json'
      },
      body: content,
      credentials: 'omit',
      cache: 'no-store',
      redirect: 'error',
      signal: controller.signal
    })
    const text = await response.text()
    let payload: unknown
    try {
      payload = text === '' ? undefined : JSON.parse(text)
    } catch {
      payload = undefined
    }
    if (!response.ok) {
      const error = payload as { code?: unknown; message?: unknown } | undefined
      throw new OtaUploadError(
        typeof error?.message === 'string'
          ? error.message
          : `固件内容未被接受（HTTP ${response.status}）。`,
        typeof error?.code === 'number' ? error.code : undefined,
        response.status,
        response.status >= 500
      )
    }
    if (payload === undefined) {
      throw new OtaUploadError(
        '上传响应无法解析，请读取会话权威状态。',
        undefined,
        response.status,
        true
      )
    }
    return payload as OtaUploadResponse
  } catch (error) {
    if (error instanceof OtaUploadError) throw error
    throw new OtaUploadError(
      '上传结果未知，请读取会话权威状态后决定是否重传。',
      undefined,
      undefined,
      true
    )
  } finally {
    clearTimeout(timer)
  }
}

/* ---------- 灰度活动与批次（S13-4b） ---------- */

const campaigns = (projectId: string) =>
  `/api/v1/projects/${encodeURIComponent(projectId)}/ota/campaigns` as const
const campaign = (projectId: string, campaignId: string) =>
  `${campaigns(projectId)}/${encodeURIComponent(campaignId)}` as const

/** 游标分页读取活动摘要；服务端每页上限100，只返回白名单投影。 */
export function fetchOtaCampaigns(projectId: string, cursor?: string, limit = 50) {
  return request.get<components['schemas']['CursorPageOtaCampaignSummaryResponse']>({
    url: campaigns(projectId),
    params: { limit, ...(cursor === undefined || cursor === '' ? {} : { cursor }) },
    showErrorMessage: false
  })
}

/** 读取活动详情（含冻结计划与目标作业快照）。 */
export function fetchOtaCampaign(projectId: string, campaignId: string) {
  return request.get<OtaCampaignResponse>({
    url: campaign(projectId, campaignId),
    showErrorMessage: false
  })
}

/** 读取活动执行事实（含批次进度与运行取消）。 */
export function fetchOtaCampaignExecution(projectId: string, campaignId: string) {
  return request.get<OtaCampaignExecutionResponse>({
    url: `${campaign(projectId, campaignId)}/execution`,
    showErrorMessage: false
  })
}

/** 读取活动当前批次的作业状态与稳定批次归属。 */
export function fetchOtaCampaignBatches(projectId: string, campaignId: string) {
  return request.get<OtaCampaignBatchResponse[]>({
    url: `${campaign(projectId, campaignId)}/batches`,
    showErrorMessage: false
  })
}

/** 创建活动草稿；计划快照在服务端冻结，控制台不解释终态。 */
export function createOtaCampaign(
  projectId: string,
  plan: OtaCampaignPlanBody,
  idempotencyKey: string
) {
  return request.post<OtaCampaignResponse>({
    url: campaigns(projectId),
    params: plan,
    headers: { 'Idempotency-Key': idempotencyKey }
  })
}

/** 原子冻结目标与批次（CAS）。 */
export function scheduleOtaCampaign(
  projectId: string,
  campaignId: string,
  expectedRevision: string,
  idempotencyKey: string
) {
  return request.post<OtaCampaignResponse>({
    url: `${campaign(projectId, campaignId)}/scheduling`,
    params: { expectedRevision },
    headers: { 'Idempotency-Key': idempotencyKey }
  })
}

/** 到达冻结排程时间后启动第一批。 */
export function startOtaCampaign(
  projectId: string,
  campaignId: string,
  expectedRevision: string,
  idempotencyKey: string
) {
  return request.post<OtaCampaignExecutionResponse>({
    url: `${campaign(projectId, campaignId)}/starting`,
    params: { expectedRevision },
    headers: { 'Idempotency-Key': idempotencyKey }
  })
}

/** 暂停：关闭新准入，不撤回已受理通知也不中断刷写。 */
export function pauseOtaCampaign(
  projectId: string,
  campaignId: string,
  expectedRevision: string,
  reason: string,
  idempotencyKey: string
) {
  return request.post<OtaCampaignExecutionResponse>({
    url: `${campaign(projectId, campaignId)}/pauses`,
    params: { expectedRevision, reason },
    headers: { 'Idempotency-Key': idempotencyKey }
  })
}

/** 恢复：服务端重新核验安全条件，人工原因不能覆盖安全暂停。 */
export function resumeOtaCampaign(
  projectId: string,
  campaignId: string,
  expectedRevision: string,
  reason: string,
  idempotencyKey: string
) {
  return request.post<OtaCampaignExecutionResponse>({
    url: `${campaign(projectId, campaignId)}/resumptions`,
    params: { expectedRevision, reason },
    headers: { 'Idempotency-Key': idempotencyKey }
  })
}

/** 取消：未派发立即终结，已派发收敛到安全终态（不撤销已成功升级）。 */
export function cancelOtaCampaign(
  projectId: string,
  campaignId: string,
  expectedRevision: string,
  reason: string,
  idempotencyKey: string
) {
  return request.post<OtaCampaignResponse>({
    url: `${campaign(projectId, campaignId)}/cancellation`,
    params: { expectedRevision, reason },
    headers: { 'Idempotency-Key': idempotencyKey }
  })
}

/** 人工放行下一批：不改目标也不改冻结策略。 */
export function advanceOtaCampaignBatch(
  projectId: string,
  campaignId: string,
  expectedRevision: string,
  expectedBatchNumber: string,
  reason: string,
  idempotencyKey: string
) {
  return request.post<OtaCampaignExecutionResponse>({
    url: `${campaign(projectId, campaignId)}/batch-advancements`,
    params: { expectedRevision, expectedBatchNumber, reason },
    headers: { 'Idempotency-Key': idempotencyKey }
  })
}

/** 读取指定设备对指定固件的当前资格快照（不是可复用的下载令牌）。 */
export function fetchOtaDeviceEligibility(projectId: string, deviceId: string, firmwareId: string) {
  return request.get<OtaDeviceEligibilityResponse>({
    url: `/api/v1/projects/${encodeURIComponent(projectId)}/ota/devices/${encodeURIComponent(deviceId)}/eligibility`,
    params: { firmwareId },
    showErrorMessage: false
  })
}

/* ---------- OTA审计时间线（S13-4c） ---------- */

/** 审计读取响应；details已是结构化对象，控制台不再解析原始JSON。 */
export type OtaAuditResponse = components['schemas']['OtaAuditResponse']

/**
 * 游标分页读取本项目OTA审计时间线。
 *
 * 服务端固定按`ota.`前缀收窄，因此本接口看不到其他模块的审计事实；
 * `action`可选，用于按精确动作编码过滤。
 */
export function fetchOtaAudits(
  projectId: string,
  options: { cursor?: string; limit?: number; action?: string } = {}
) {
  const cursor = options.cursor ?? ''
  return request.get<components['schemas']['CursorPageOtaAuditResponse']>({
    url: `/api/v1/projects/${encodeURIComponent(projectId)}/ota/audits`,
    params: {
      limit: options.limit ?? 50,
      ...(cursor === '' ? {} : { cursor }),
      ...(options.action === undefined || options.action === '' ? {} : { action: options.action })
    },
    showErrorMessage: false
  })
}

/* ---------- 设备作业（S13-4c-2） ---------- */

/** 作业摘要；attempt与失败归因是控制台定位问题的主要线索。 */
export type OtaDeviceJobSummaryResponse = components['schemas']['OtaDeviceJobSummaryResponse']
/** 作业详情：摘要字段加真实转移时间线。 */
export type OtaDeviceJobDetailResponse = components['schemas']['OtaDeviceJobDetailResponse']

/** 游标分页读取活动下的设备作业；服务端按作业ID倒序。 */
export function fetchOtaCampaignJobs(
  projectId: string,
  campaignId: string,
  options: { cursor?: string; limit?: number } = {}
) {
  const cursor = options.cursor ?? ''
  return request.get<components['schemas']['CursorPageOtaDeviceJobSummaryResponse']>({
    url: `${campaign(projectId, campaignId)}/jobs`,
    params: { limit: options.limit ?? 100, ...(cursor === '' ? {} : { cursor }) },
    showErrorMessage: false
  })
}

/** 读取单个作业及其转移时间线。 */
export function fetchOtaCampaignJob(projectId: string, campaignId: string, jobId: string) {
  return request.get<OtaDeviceJobDetailResponse>({
    url: `${campaign(projectId, campaignId)}/jobs/${encodeURIComponent(jobId)}`,
    showErrorMessage: false
  })
}

/** 设备维度地址前缀；父设备不存在或跨项目是404/30020。 */
const otaDevice = (projectId: string, deviceId: string) =>
  `/api/v1/projects/${encodeURIComponent(projectId)}/ota/devices/${encodeURIComponent(deviceId)}` as const

/**
 * 游标分页读取某设备的全部OTA作业（最新在前）。
 *
 * 与活动维度作业端点复用同一 `OtaDeviceJobSummaryResponse` 白名单；服务端按作业
 * UUIDv7身份倒序，每页上限100。设备维度只回答「这台设备经历过哪些作业」，
 * 不替代活动维度的批次与执行事实。
 */
export function fetchOtaDeviceJobs(
  projectId: string,
  deviceId: string,
  options: { cursor?: string; limit?: number } = {}
) {
  const cursor = options.cursor ?? ''
  return request.get<components['schemas']['CursorPageOtaDeviceJobSummaryResponse']>({
    url: `${otaDevice(projectId, deviceId)}/jobs`,
    params: { limit: options.limit ?? 100, ...(cursor === '' ? {} : { cursor }) },
    showErrorMessage: false
  })
}
