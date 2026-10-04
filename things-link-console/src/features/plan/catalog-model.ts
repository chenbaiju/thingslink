/**
 * 套餐目录页的纯展示模型（S14-1c）。
 *
 * 页面只做渲染，销售状态映射、参考价与成交价的分离、额度与权益的呈现都放在这里，
 * 便于用 vitest 覆盖「未开售但有参考价」「DISABLED 权益不携带任何数值额度」这两类
 * 最容易在界面上被误读的分支。
 *
 * 口径原则：
 * - **参考价不是成交价。** 只有 `saleStatus === 'ON_SALE'` 且带 `priceCents` 才算可售；
 *   参考价只在无可售价格时作为「参考」展示，不得渲染成可购买的价格。
 * - **未知编码原样透传。** 后端新增维度/能力时，控制台显示原始编码而不是折叠成某个
 *   已知标签，也不凭空补 0。
 * - **DISABLED 权益不携带数值。** 目录未包含的能力的行只有编码与标签，模型不为其编造额度。
 *
 * @module features/plan/catalog-model
 */

import type { PlanResponse } from '@/api/plan'

/** 销售状态中文标签；未知状态原样透传。 */
export const SALE_STATUS_LABELS: Record<string, string> = {
  ON_SALE: '在售',
  NOT_FOR_SALE: '未开售'
}

/** 销售状态 → 中文标签；未知状态原样透传，缺状态返回中性占位符。 */
export const saleStatusLabel = (status?: string | null): string =>
  status ? (SALE_STATUS_LABELS[status] ?? status) : '—'

/** 销售状态 → ElTag 类型；只有真正在售才是 success。 */
export const saleStatusTag = (status?: string | null): 'success' | 'info' =>
  status === 'ON_SALE' ? 'success' : 'info'

/** 计费周期中文标签。 */
export const BILLING_PERIOD_LABELS: Record<string, string> = {
  NONE: '长期有效',
  MONTH: '按月',
  YEAR: '按年'
}

/** 计费周期 → 中文标签；未知状态原样透传。 */
export const billingPeriodLabel = (period?: string | null): string =>
  period ? (BILLING_PERIOD_LABELS[period] ?? period) : '—'

/** 冻结维度中文标签；未登记的编码原样显示，不猜测含义。 */
export const DIMENSION_LABELS: Record<string, string> = {
  PROJECTS_MAX: '项目数上限',
  DEVICES_MAX: '设备数上限',
  END_USERS_MAX: '终端用户数上限',
  DASHBOARDS_MAX: '看板数上限',
  EXTERNAL_COLLABORATOR_SEATS: '外部协作者席位',
  HISTORY_WINDOW: '历史数据窗口',
  UPLINK_MESSAGE_DAILY: '上行消息/日',
  DOWNLINK_MESSAGE_DAILY: '下行消息/日',
  REST_API_WRITE_DAILY: 'REST 写请求/日',
  REST_API_RATE_PER_MINUTE: 'REST 速率/分钟',
  WEBSOCKET_CONNECTION_CONCURRENT: 'WebSocket 并发连接',
  SCRIPT_RULE_CONCURRENCY: '脚本/规则并发',
  SCRIPT_RULE_EXECUTION_DAILY: '脚本/规则执行/日',
  SCRIPT_RULE_CPU_MILLIS_DAILY: '脚本/规则 CPU 毫秒/日',
  NOTIFICATION_DELIVERY_DAILY: '通知投递/日',
  STORAGE_LIMIT: '对象存储'
}

/** 维度编码 → 中文标签；未知编码原样透传。 */
export const dimensionLabel = (code: string): string => DIMENSION_LABELS[code] ?? code

/** 计量窗口中文标签。 */
export const WINDOW_LABELS: Record<string, string> = {
  NONE: '存量',
  UTC_DAY: '每 UTC 日',
  MINUTE: '每分钟',
  CONCURRENT: '并发',
  ROLLING: '滚动窗口'
}

/** 计量窗口 → 中文标签；未知窗口原样透传。 */
export const windowLabel = (window?: string | null): string =>
  window ? (WINDOW_LABELS[window] ?? window) : '—'

/** 能力中文标签；未登记的编码原样显示。 */
export const CAPABILITY_LABELS: Record<string, string> = {
  REST_API_WRITE: 'REST 写接口',
  REST_API_RATE_LIMIT: 'REST 速率限制',
  WEBSOCKET_CONNECTION: 'WebSocket 连接',
  SCRIPT_RULE_CONCURRENCY: '脚本/规则并发',
  SCRIPT_RULE_EXECUTION: '脚本/规则执行',
  SCRIPT_RULE_CPU: '脚本/规则 CPU',
  NOTIFICATION_DELIVERY: '通知投递',
  OBJECT_STORAGE: '对象存储',
  SMS_CHANNEL: '短信渠道（未交付）',
  OTA: 'OTA 升级',
  OPEN_API: '开放 API（未交付）',
  APP_SUBSCRIPTION: '应用订阅（未交付）'
}

/** capability code → 中文标签；未知编码原样透传。 */
export const capabilityLabel = (code: string): string => CAPABILITY_LABELS[code] ?? code

/** 旧响应或未知未来语义不推断成运行门禁。 */
export const capabilityStatusLabel = (enabled: boolean, enforcement?: string | null): string =>
  enforcement === 'CATALOG_ONLY' ? (enabled ? '目录包含' : '目录未包含') : '语义未确认'

/** 金额格式化分组器；固定 en-US 分组，避免运行环境区域设置影响展示。 */
const AMOUNT_GROUPER = new Intl.NumberFormat('en-US')

/**
 * 人民币分 → 可读金额。
 *
 * 整数元不补小数（298000 → `￥2,980`），有分才补两位（298050 → `￥2,980.50`）；
 * `undefined`/`null` 返回中性占位符而不是 `￥0`，避免把「没有价」显示成「免费」。
 *
 * @param cents 金额（分）
 * @param currency ISO 4217 币种；仅 CNY 使用 ￥ 前缀，其它币种保留代码前缀
 */
export function formatCents(cents?: number | null, currency?: string | null): string {
  if (cents === undefined || cents === null || !Number.isFinite(cents)) return '—'
  const sign = cents < 0 ? '-' : ''
  const absolute = Math.abs(Math.trunc(cents))
  const yuan = Math.floor(absolute / 100)
  const fen = absolute % 100
  const decimals = fen === 0 ? '' : `.${String(fen).padStart(2, '0')}`
  const prefix = currency === 'CNY' ? '￥' : currency ? `${currency} ` : ''
  return `${sign}${prefix}${AMOUNT_GROUPER.format(yuan)}${decimals}`
}

/** 一个套餐的冻结数值维度展示行。 */
export interface PlanQuotaRow {
  /** 冻结维度编码。 */
  readonly code: string
  /** 中文标签（未知编码为原编码）。 */
  readonly label: string
  /** 冻结数值，恒为确定值。 */
  readonly value: number
  /** 单位。 */
  readonly unit: string
  /** 计量窗口。 */
  readonly window: string
}

/** 一项功能权益展示行；`enabled=false` 时**不携带任何数值额度**。 */
export interface PlanCapabilityRow {
  /** 冻结 capability code。 */
  readonly code: string
  /** 中文标签（未知编码为原编码）。 */
  readonly label: string
  /** 不可变目录包含声明，不作为权限判断。 */
  readonly enabled: boolean
  /** 明确目录或未知语义，不猜测运行授权。 */
  readonly statusLabel: string
}

/** 单个套餐档位的展示模型。 */
export interface PlanTierModel {
  /** 稳定套餐编码。 */
  readonly code: string
  /** 展示名称。 */
  readonly name: string
  /** 产品修订版标识。 */
  readonly revision: string
  /** 修订序号。 */
  readonly revisionNo: number
  /** 销售状态原值。 */
  readonly saleStatus: string
  /** 销售状态中文标签。 */
  readonly saleStatusLabel: string
  /** 销售状态标签色。 */
  readonly saleStatusTag: 'success' | 'info'
  /** 计费周期中文标签。 */
  readonly billingPeriodLabel: string
  /** 币种。 */
  readonly currency: string
  /** 是否真的可售：只有在售且有成交价才算，参考价永远不使其可售。 */
  readonly saleable: boolean
  /** 成交价展示；未定价时为 `null`（不显示为 0）。 */
  readonly salePrice: string | null
  /** 参考价展示；未记录时为 `null`。 */
  readonly referencePrice: string | null
  /** 参考价币种；未记录时为 `null`。 */
  readonly referencePriceCurrency: string | null
  /** 定价页主展示价：可售用成交价，否则用参考价。 */
  readonly displayPrice: string
  /** 主展示价是否为参考价（不是成交价）。 */
  readonly priceIsReference: boolean
  /** 冻结数值维度，按编码排序。 */
  readonly quotas: readonly PlanQuotaRow[]
  /** 目录包含声明，按编码排序。 */
  readonly enabledCapabilities: readonly PlanCapabilityRow[]
  /** 目录未包含声明，按编码排序，且不携带数值额度。 */
  readonly disabledCapabilities: readonly PlanCapabilityRow[]
}

/** 目录页整体模型。 */
export interface PlanCatalogModel {
  /** 四档展示模型，保持服务端返回的展示顺序。 */
  readonly tiers: readonly PlanTierModel[]
  /** 真正可售的档位数量；参考价不参与计数。 */
  readonly saleableCount: number
}

/** 按编码排序的只读副本；编码缺失时保留空串，排序保持稳定。 */
const sortedByCode = <T extends { code?: string | null }>(rows: readonly T[]): T[] =>
  [...rows].sort((left, right) => (left.code ?? '').localeCompare(right.code ?? ''))

/**
 * 判断一个档位是否真的可售。
 *
 * 三个条件缺一不可：销售状态为 `ON_SALE`、有成交价、并且成交价是有效数字。
 * 参考价（`referencePriceCents`）不参与判定。
 *
 * @param plan 契约返回的档位
 */
export const isSaleable = (plan: PlanResponse): boolean =>
  plan.saleStatus === 'ON_SALE' &&
  plan.priceCents !== undefined &&
  plan.priceCents !== null &&
  Number.isFinite(plan.priceCents)

/**
 * 把契约档位转换为展示模型。
 *
 * @param plan 契约返回的档位
 */
export function buildPlanTierModel(plan: PlanResponse): PlanTierModel {
  const currency = plan.currency ?? null
  const saleable = isSaleable(plan)
  const salePrice = saleable ? formatCents(plan.priceCents, currency) : null
  const referencePrice = formatCents(
    plan.referencePriceCents,
    plan.referencePriceCurrency ?? currency
  )
  const hasReference = plan.referencePriceCents !== undefined && plan.referencePriceCents !== null
  const displayPrice = salePrice ?? (hasReference ? referencePrice : '—')
  const quotas = sortedByCode(plan.quotaDimensions ?? []).map<PlanQuotaRow>((dimension) => ({
    code: dimension.code ?? '',
    label: dimensionLabel(dimension.code ?? ''),
    value: dimension.value ?? 0,
    unit: dimension.unit ?? '',
    window: dimension.window ?? ''
  }))
  const capabilities = sortedByCode(plan.entitlements ?? []).map<PlanCapabilityRow>(
    (entitlement) => ({
      code: entitlement.code ?? '',
      label: capabilityLabel(entitlement.code ?? ''),
      enabled: entitlement.enabled === true,
      statusLabel: capabilityStatusLabel(entitlement.enabled === true, entitlement.enforcement)
    })
  )
  return {
    code: plan.code ?? '',
    name: plan.name ?? '',
    revision: plan.revision ?? '',
    revisionNo: plan.revisionNo ?? 0,
    saleStatus: plan.saleStatus ?? '',
    saleStatusLabel: saleStatusLabel(plan.saleStatus),
    saleStatusTag: saleStatusTag(plan.saleStatus),
    billingPeriodLabel: billingPeriodLabel(plan.billingPeriod),
    currency: currency ?? '',
    saleable,
    salePrice,
    referencePrice: hasReference ? referencePrice : null,
    referencePriceCurrency: hasReference ? (plan.referencePriceCurrency ?? currency) : null,
    displayPrice,
    priceIsReference: salePrice === null && hasReference,
    quotas,
    enabledCapabilities: capabilities.filter((capability) => capability.enabled),
    disabledCapabilities: capabilities.filter((capability) => !capability.enabled)
  }
}

/**
 * 把目录契约转换为页面模型。
 *
 * @param plans 契约返回的档位列表
 */
export function buildPlanCatalogModel(plans: readonly PlanResponse[]): PlanCatalogModel {
  const tiers = plans.map(buildPlanTierModel)
  return {
    tiers,
    saleableCount: tiers.filter((tier) => tier.saleable).length
  }
}
