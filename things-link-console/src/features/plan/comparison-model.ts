import type { PlanTierModel, PlanQuotaRow } from './catalog-model'
import { windowLabel } from './catalog-model'

export interface PlanComparisonCell {
  kind: 'value' | 'included' | 'excluded' | 'unknown' | 'missing'
  text: string
  detail: string
}

export interface PlanComparisonRow {
  key: string
  label: string
  section: boolean
  cells: PlanComparisonCell[]
}

const units: Record<string, string> = {
  COUNT: '',
  DAY: '天',
  MONTH: '个月',
  MESSAGE: '条',
  REQUEST: '次',
  CONNECTION: '个连接',
  EXECUTION: '次',
  DELIVERY: '次',
  MILLISECOND: '毫秒',
  BYTE: '字节',
  MB: 'MB',
  GB: 'GB'
}
const groups = [
  {
    label: '项目与设备',
    codes: [
      'PROJECTS_MAX',
      'DEVICES_MAX',
      'END_USERS_MAX',
      'EXTERNAL_COLLABORATOR_SEATS',
      'DASHBOARDS_MAX',
      'HISTORY_WINDOW'
    ]
  },
  {
    label: '消息与接口',
    codes: [
      'UPLINK_MESSAGE_DAILY',
      'DOWNLINK_MESSAGE_DAILY',
      'REST_API_WRITE_DAILY',
      'REST_API_RATE_PER_MINUTE',
      'WEBSOCKET_CONNECTION_CONCURRENT'
    ]
  },
  {
    label: '规则、通知与存储',
    codes: [
      'SCRIPT_RULE_CONCURRENCY',
      'SCRIPT_RULE_EXECUTION_DAILY',
      'SCRIPT_RULE_CPU_MILLIS_DAILY',
      'NOTIFICATION_DELIVERY_DAILY',
      'STORAGE_LIMIT'
    ]
  }
]
const missing = (): PlanComparisonCell => ({
  kind: 'missing',
  text: '未提供',
  detail: '套餐目录未提供此项数据'
})
const quotaCell = (quota?: PlanQuotaRow): PlanComparisonCell =>
  quota
    ? {
        kind: 'value',
        text: `${new Intl.NumberFormat('zh-CN').format(quota.value)}${units[quota.unit] !== undefined ? (units[quota.unit] ? ' ' + units[quota.unit] : '') : ' ' + quota.unit}`,
        detail: `计量窗口：${windowLabel(quota.window)}`
      }
    : missing()

/** 用全部套餐的维度并集对齐列；缺失数据与明确未包含权益分别展示。 */
export function buildPlanComparisonRows(tiers: readonly PlanTierModel[]): PlanComparisonRow[] {
  const rows: PlanComparisonRow[] = []
  const quotas = new Map(
    tiers.flatMap((tier) => tier.quotas.map((quota) => [quota.code, quota] as const))
  )
  const appendQuotaGroup = (label: string, codes: string[]) => {
    const available = codes.filter((code) => quotas.has(code))
    if (!available.length) return
    rows.push({ key: `section:${label}`, label, section: true, cells: [] })
    for (const code of available) {
      rows.push({
        key: `quota:${code}`,
        label: quotas.get(code)!.label,
        section: false,
        cells: tiers.map((tier) => quotaCell(tier.quotas.find((quota) => quota.code === code)))
      })
      quotas.delete(code)
    }
  }
  for (const group of groups) appendQuotaGroup(group.label, group.codes)
  appendQuotaGroup('其他额度', [...quotas.keys()].sort())

  const capabilities = new Map(
    tiers.flatMap((tier) =>
      [...tier.enabledCapabilities, ...tier.disabledCapabilities].map(
        (capability) => [capability.code, capability] as const
      )
    )
  )
  if (capabilities.size) {
    rows.push({ key: 'section:capabilities', label: '功能权益', section: true, cells: [] })
    for (const code of [...capabilities.keys()].sort()) {
      rows.push({
        key: `capability:${code}`,
        label: capabilities.get(code)!.label,
        section: false,
        cells: tiers.map((tier) => {
          const capability = [...tier.enabledCapabilities, ...tier.disabledCapabilities].find(
            (item) => item.code === code
          )
          if (!capability) return missing()
          if (capability.statusLabel === '语义未确认')
            return { kind: 'unknown', text: '待确认', detail: '接口未明确此项权益的适用语义' }
          return {
            kind: capability.enabled ? 'included' : 'excluded',
            text: capability.enabled ? '✓' : '—',
            detail: capability.statusLabel
          }
        })
      })
    }
  }
  return rows
}
