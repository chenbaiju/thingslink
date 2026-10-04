/**
 * 项目设置页的「我的套餐」展示模型（S14-2c）。
 *
 * 页面只做渲染；订阅状态/续费方式的中文口径、服务期文案、冻结额度与权益的分组都放在这里，
 * 便于用 vitest 钉住最容易在界面上被误读的分支：FREE 无终点服务期、DISABLED 权益、
 * 参考价不是成交价、运行时绑定与订阅修订版不一致。
 *
 * 口径原则：
 * - **缺省不是零。** `planSummary` 缺省表示「本读取面不提供套餐事实」（跨租户协作者或无订阅的
 *   存量租户），模型返回 `null`，页面显示说明而不是伪造一份零额度套餐。
 * - **DISABLED 权益不携带数值。** 未交付/未包含能力的行只有编码、标签与状态。
 * - **参考价不是成交价。** 摘要里根本没有成交价、订单或支付字段；参考价只作展示。
 * - **两类档位不互相冒充。** `subscribedPlan` 是订阅锁定的修订版，`effectivePlan` 是运行时
 *   实际绑定的档位；模型显式给出 `effectiveMatchesSubscribed`，不一致时页面必须提示。
 *
 * @module features/plan/plan-summary-model
 */

import type { PlanIdentityResponse, ProjectPlanSummaryResponse } from '@/api/quota'
import {
  type PlanCapabilityRow,
  billingPeriodLabel,
  capabilityLabel,
  capabilityStatusLabel,
  dimensionLabel,
  formatCents,
  windowLabel
} from '@/features/plan/catalog-model'

/** 订阅状态中文标签；未知状态原样透传。 */
export const SUBSCRIPTION_STATUS_LABELS: Record<string, string> = {
  ACTIVE: '生效中',
  GRACE: '宽限期',
  RESTRICTED_FREE: '宽限结束降级',
  EXPIRED: '已到期',
  CANCELLED: '已取消',
  SUPERSEDED: '已被升降级取代'
}

/** 订阅状态 → 中文标签；未知状态原样透传，缺状态返回中性占位符。 */
export const subscriptionStatusLabel = (status?: string | null): string =>
  status ? (SUBSCRIPTION_STATUS_LABELS[status] ?? status) : '—'

/** 订阅状态 → ElTag 类型；只有真正生效才是 success。 */
export const subscriptionStatusTag = (
  status?: string | null
): 'success' | 'warning' | 'danger' | 'info' => {
  if (status === 'ACTIVE') return 'success'
  if (status === 'GRACE') return 'warning'
  if (status === 'RESTRICTED_FREE' || status === 'EXPIRED') return 'danger'
  return 'info'
}

/** 续费方式中文标签。 */
export const RENEWAL_MODE_LABELS: Record<string, string> = {
  NONE: '不续费',
  AUTO: '自动续费',
  MANUAL: '人工续费'
}

/** 续费方式 → 中文标签；未知状态原样透传。 */
export const renewalModeLabel = (mode?: string | null): string =>
  mode ? (RENEWAL_MODE_LABELS[mode] ?? mode) : '—'

/** 扩容/调整来源中文标签；未知来源原样透传。 */
export const ADDITION_SOURCE_LABELS: Record<string, string> = {
  PURCHASE: '客户购买',
  OPERATION_ADJUSTMENT: '运营调整'
}

/** 扩容/调整来源 → 中文标签；未知来源原样透传，缺来源返回中性占位符。 */
export const additionSourceLabel = (source?: string | null): string =>
  source ? (ADDITION_SOURCE_LABELS[source] ?? source) : '—'

/** 扩容/调整生命周期状态中文标签；未知状态原样透传。 */
export const ADDITION_STATUS_LABELS: Record<string, string> = {
  ACTIVE: '生效中',
  PENDING: '待生效'
}

/** 扩容/调整状态 → 中文标签；未知状态原样透传，缺状态返回中性占位符。 */
export const additionStatusLabel = (status?: string | null): string =>
  status ? (ADDITION_STATUS_LABELS[status] ?? status) : '—'

/** 扩容/调整状态 → ElTag 类型；只有真正生效才是 success。 */
export const additionStatusTag = (status?: string | null): 'success' | 'warning' | 'info' => {
  if (status === 'ACTIVE') return 'success'
  if (status === 'PENDING') return 'warning'
  return 'info'
}

/** 一个档位身份的中文展示行。 */
export interface PlanIdentityModel {
  /** 稳定套餐编码。 */
  readonly code: string
  /** 展示名称。 */
  readonly name: string
  /** 产品修订版标识。 */
  readonly revision: string
  /** 修订序号。 */
  readonly revisionNo: number
}

/** 一条冻结额度展示行。 */
export interface PlanLimitRow {
  /** 冻结维度编码。 */
  readonly code: string
  /** 中文标签（未知编码为原编码）。 */
  readonly label: string
  /** 冻结数值，恒为确定值。 */
  readonly value: number
  /** 数值单位。 */
  readonly unit: string
  /** 计量窗口原值。 */
  readonly window: string
  /** 计量窗口中文标签（未知窗口为原值）。 */
  readonly windowLabel: string
}

/** 一项功能权益展示行；`enabled=false` 时**不携带任何数值额度**。 */
export type { PlanCapabilityRow } from '@/features/plan/catalog-model'

/**
 * 一条扩容/人工调整溯源展示行（S14-4c）。
 *
 * 只呈现额度为什么比冻结值高：来源、维度、追加量、自身有效期与此刻是否已计入。
 * 契约刻意不下发操作人账号，因此本行**没有任何操作人身份字段**，页面也无法展示。
 */
export interface PlanAdditionRow {
  /** 来源原值（`PURCHASE` / `OPERATION_ADJUSTMENT`）；缺失或未知原样透传。 */
  readonly source: string
  /** 来源中文标签（未知来源为原值，缺失为中性占位符）。 */
  readonly sourceLabel: string
  /** 维度编码。 */
  readonly dimensionCode: string
  /** 维度中文标签（未知编码为原编码）。 */
  readonly dimensionLabel: string
  /** 追加额度数值（运行时单位）。 */
  readonly amount: number
  /** 追加额度的单位（运行时单位，例如对象存储为 BYTE）。 */
  readonly unit: string
  /** 自身有效期中文文案（UTC）；端点缺失时为中性占位符。 */
  readonly periodText: string
  /** 生命周期状态原值；缺失或未知原样透传。 */
  readonly status: string
  /** 生命周期状态中文标签。 */
  readonly statusLabel: string
  /** 生命周期状态标签色。 */
  readonly statusTag: 'success' | 'warning' | 'info'
  /** 服务端计算的「此刻是否已计入有效权益」。 */
  readonly effectiveNow: boolean
  /** 人工调整原因；购买包不携带时为 `null`（页面显示占位符而不是空串）。 */
  readonly reason: string | null
}

/** 「我的套餐」整体展示模型。 */
export interface ProjectPlanSummaryModel {
  /** 订阅锁定的档位。 */
  readonly subscribedPlan: PlanIdentityModel
  /** 运行时实际绑定的档位；绑定不是套餐模板时为 `null`。 */
  readonly effectivePlan: PlanIdentityModel | null
  /** 运行时绑定是否与订阅修订版一致。 */
  readonly effectiveMatchesSubscribed: boolean
  /** 订阅状态原值。 */
  readonly subscriptionStatus: string
  /** 订阅状态中文标签。 */
  readonly subscriptionStatusLabel: string
  /** 订阅状态标签色。 */
  readonly subscriptionStatusTag: 'success' | 'warning' | 'danger' | 'info'
  /** 服务期起始时刻原文；缺失时为 `null`。 */
  readonly startsAt: string | null
  /** 服务期结束时刻原文；无终点时为 `null`。 */
  readonly endsAt: string | null
  /** 服务期是否没有终点。 */
  readonly perpetual: boolean
  /** 服务期中文文案：无终点时为「长期有效（无终止时间）」。 */
  readonly periodText: string
  /** 计费周期中文标签。 */
  readonly billingPeriodLabel: string
  /** 续费方式中文标签。 */
  readonly renewalModeLabel: string
  /** 参考价展示；未记录时为 `null`（不显示为 0）。 */
  readonly referencePrice: string | null
  /** 冻结额度，按编码排序。 */
  readonly limits: PlanLimitRow[]
  /**
   * 运行时真正生效的额度，按编码排序（S14-4c）。
   *
   * 这些数值用**运行时单位**表达，已经计入当前生效的扩容与调整，**不得**当作 `limits` 里的
   * 目录冻结值展示（例如对象存储在这里是 BYTE，而目录快照是 MB/GB）；空数组表示本读取面
   * 没有运行时额度投影，也不能渲染成一组零额度。
   */
  readonly effectiveLimits: PlanLimitRow[]
  /** 是否存在运行时额度投影：仅当契约给出非空 `effectiveQuotaDimensions` 时为 `true`。 */
  readonly effectiveLimitsAvailable: boolean
  /**
   * 扩容与人工调整溯源行，保持服务端顺序（S14-4c）。
   *
   * 这些行只说明额度为何高于冻结值；契约不含操作人账号，模型也**不携带任何操作人身份字段**。
   */
  readonly additions: PlanAdditionRow[]
  /** 已启用权益，按编码排序。 */
  readonly enabledCapabilities: PlanCapabilityRow[]
  /** 未启用权益（未交付/未包含），按编码排序，且不携带数值额度。 */
  readonly disabledCapabilities: PlanCapabilityRow[]
}

/** 服务期无终点时的文案；FREE 长期有效档必须显示它，不能让缺省看起来像加载失败。 */
export const PERPETUAL_PERIOD_TEXT = '长期有效（无终止时间）'

/**
 * RFC3339 时刻 → 稳定的 UTC 文案（`YYYY-MM-DD HH:mm UTC`）。
 *
 * 直接用 `Date#toISOString`，不受浏览器时区影响；非法输入原样透传而不是显示成当前时间。
 *
 * @param value RFC3339 时刻
 */
export const formatUtcMinute = (value?: string | null): string => {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return `${date.toISOString().slice(0, 16).replace('T', ' ')} UTC`
}

/** 按编码排序的只读副本；编码缺失时保留空串，排序保持稳定。 */
const sortedByCode = <T extends { code?: string | null }>(rows: readonly T[]): T[] =>
  [...rows].sort((left, right) => (left.code ?? '').localeCompare(right.code ?? ''))

/**
 * 档位身份契约 → 展示模型。
 *
 * @param identity 契约身份投影
 */
const toIdentity = (identity: PlanIdentityResponse): PlanIdentityModel => ({
  code: identity.code ?? '',
  name: identity.name ?? '',
  revision: identity.revision ?? '',
  revisionNo: identity.revisionNo ?? 0
})

/**
 * 服务期文案：无终点时给出明确说明，否则拼接起止 UTC 时刻。
 *
 * @param perpetual 是否无终点
 * @param startsAt 起始时刻
 * @param endsAt 结束时刻
 */
export const servicePeriodText = (
  perpetual: boolean,
  startsAt?: string | null,
  endsAt?: string | null
): string =>
  perpetual ? PERPETUAL_PERIOD_TEXT : `${formatUtcMinute(startsAt)} 至 ${formatUtcMinute(endsAt)}`

/**
 * 把契约摘要转换为页面模型。
 *
 * 摘要缺省时返回 `null`：调用方必须区分「本读取面不提供套餐事实」与「套餐额度为零」，
 * 不能凭空补一份默认套餐。
 *
 * 运行时额度（S14-4c）另有两个口径：`effectiveLimits` 是**运行时单位**下真正生效的额度，
 * 已含当前生效的扩容与调整，不能与 `limits` 的目录冻结值混用；`effectiveQuotaDimensions`
 * 缺省时 `effectiveLimitsAvailable` 为 `false`、`effectiveLimits` 为空数组，页面必须显示
 * 「没有运行时额度投影」而不是零额度。`additions` 只映射服务端给出的溯源字段，不含操作人账号，
 * 缺失的来源/状态原样透传，缺失或空串的 `reason` 归一为 `null`。
 *
 * @param summary 项目配额响应里的套餐摘要；跨租户协作者或无订阅租户时为 `undefined`/`null`
 */
export function buildProjectPlanSummaryModel(
  summary?: ProjectPlanSummaryResponse | null
): ProjectPlanSummaryModel | null {
  if (!summary || !summary.subscribedPlan) return null
  const perpetual = summary.perpetual === true
  const hasReference =
    summary.referencePriceCents !== undefined && summary.referencePriceCents !== null
  const limits = sortedByCode(summary.quotaDimensions ?? []).map<PlanLimitRow>((dimension) => ({
    code: dimension.code ?? '',
    label: dimensionLabel(dimension.code ?? ''),
    value: dimension.value ?? 0,
    unit: dimension.unit ?? '',
    window: dimension.window ?? '',
    windowLabel: windowLabel(dimension.window)
  }))
  // 非空判定先于映射：空数组代表「没有运行时额度投影」，不能退化成一组 0。
  const effectiveDimensions = summary.effectiveQuotaDimensions ?? []
  const effectiveLimits = sortedByCode(effectiveDimensions).map<PlanLimitRow>((dimension) => ({
    code: dimension.code ?? '',
    label: dimensionLabel(dimension.code ?? ''),
    value: dimension.value ?? 0,
    unit: dimension.unit ?? '',
    window: dimension.window ?? '',
    windowLabel: windowLabel(dimension.window)
  }))
  const additions = (summary.additions ?? []).map<PlanAdditionRow>((addition) => ({
    source: addition.source ?? '',
    sourceLabel: additionSourceLabel(addition.source),
    dimensionCode: addition.dimensionCode ?? '',
    dimensionLabel: dimensionLabel(addition.dimensionCode ?? ''),
    amount: addition.amount ?? 0,
    unit: addition.unit ?? '',
    periodText: `${formatUtcMinute(addition.startsAt)} 至 ${formatUtcMinute(addition.endsAt)}`,
    status: addition.status ?? '',
    statusLabel: additionStatusLabel(addition.status),
    statusTag: additionStatusTag(addition.status),
    effectiveNow: addition.effectiveNow === true,
    // 购买包不带 reason；空串也归一为 null，避免页面把「无原因」渲染成空白。
    reason: addition.reason ? addition.reason : null
  }))
  const capabilities = sortedByCode(summary.capabilities ?? []).map<PlanCapabilityRow>(
    (capability) => ({
      code: capability.code ?? '',
      label: capabilityLabel(capability.code ?? ''),
      enabled: capability.enabled === true,
      statusLabel: capabilityStatusLabel(capability.enabled === true, capability.enforcement)
    })
  )
  return {
    subscribedPlan: toIdentity(summary.subscribedPlan),
    effectivePlan: summary.effectivePlan ? toIdentity(summary.effectivePlan) : null,
    effectiveMatchesSubscribed: summary.effectiveMatchesSubscribed === true,
    subscriptionStatus: summary.subscriptionStatus ?? '',
    subscriptionStatusLabel: subscriptionStatusLabel(summary.subscriptionStatus),
    subscriptionStatusTag: subscriptionStatusTag(summary.subscriptionStatus),
    startsAt: summary.startsAt ?? null,
    endsAt: summary.endsAt ?? null,
    perpetual,
    periodText: servicePeriodText(perpetual, summary.startsAt, summary.endsAt),
    billingPeriodLabel: billingPeriodLabel(summary.billingPeriod),
    renewalModeLabel: renewalModeLabel(summary.renewalMode),
    referencePrice: hasReference
      ? formatCents(summary.referencePriceCents, summary.referencePriceCurrency)
      : null,
    limits,
    effectiveLimits,
    effectiveLimitsAvailable: effectiveDimensions.length > 0,
    additions,
    enabledCapabilities: capabilities.filter((capability) => capability.enabled),
    disabledCapabilities: capabilities.filter((capability) => !capability.enabled)
  }
}
