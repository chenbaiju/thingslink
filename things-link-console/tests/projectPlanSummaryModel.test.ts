import { describe, expect, it } from 'vitest'

import type { ProjectPlanSummaryResponse } from '@/api/quota'
import {
  PERPETUAL_PERIOD_TEXT,
  additionSourceLabel,
  additionStatusTag,
  buildProjectPlanSummaryModel,
  formatUtcMinute,
  servicePeriodText,
  subscriptionStatusTag
} from '@/features/plan/plan-summary-model'

/**
 * 「我的套餐」展示模型的单元测试（S14-2c）。
 *
 * 这里钉住四类最容易在界面上被误读的分支：摘要缺省不是零额度套餐、FREE 无终点服务期、
 * DISABLED 权益不携带数值额度、参考价与成交价的分离（摘要里根本没有成交价字段）。
 *
 * S14-4c 起同一文件还覆盖运行时有效额度与扩容/调整溯源：有效额度用运行时单位、
 * 缺省不是零额度，且溯源行不含操作人身份。
 */

/** FREE 长期有效的契约摘要；字段名与生成契约一致。 */
const freeSummary = (): ProjectPlanSummaryResponse => ({
  subscribedPlan: {
    code: 'FREE',
    name: '免费版',
    revision: 'product-revision-1',
    revisionNo: 1
  },
  effectivePlan: {
    code: 'FREE',
    name: '免费版',
    revision: 'product-revision-1',
    revisionNo: 1
  },
  effectiveMatchesSubscribed: true,
  subscriptionStatus: 'ACTIVE',
  startsAt: '2026-09-17T00:00:00Z',
  perpetual: true,
  billingPeriod: 'NONE',
  renewalMode: 'NONE',
  referencePriceCents: 0,
  referencePriceCurrency: 'CNY',
  quotaDimensions: [
    { code: 'PROJECTS_MAX', value: 1, unit: 'COUNT', window: 'NONE' },
    { code: 'DEVICES_MAX', value: 3, unit: 'COUNT', window: 'NONE' },
    { code: 'UPLINK_MESSAGE_DAILY', value: 700, unit: 'MESSAGE', window: 'UTC_DAY' },
    { code: 'DOWNLINK_MESSAGE_DAILY', value: 300, unit: 'MESSAGE', window: 'UTC_DAY' },
    { code: 'UNKNOWN_DIMENSION', value: 9, unit: 'COUNT', window: 'UNKNOWN_WINDOW' }
  ],
  capabilities: [
    { code: 'OTA', enabled: false },
    { code: 'REST_API_WRITE', enabled: true }
  ]
})

/**
 * 带运行时额度投影的摘要（S14-4c）：有效维度故意乱序，扩容行保持服务端顺序。
 *
 * `STORAGE_LIMIT` 用 BYTE 表达，用于钉住「有效额度是运行时单位、不是目录 MB/GB」。
 */
const runtimeSummary = (): ProjectPlanSummaryResponse => ({
  ...freeSummary(),
  effectiveQuotaDimensions: [
    { code: 'STORAGE_LIMIT', value: 10_737_418_240, unit: 'BYTE', window: 'NONE' },
    { code: 'DEVICES_MAX', value: 5, unit: 'COUNT', window: 'NONE' }
  ],
  additions: [
    {
      source: 'PURCHASE',
      dimensionCode: 'DEVICES_MAX',
      amount: 2,
      unit: 'COUNT',
      window: 'NONE',
      startsAt: '2026-09-17T00:00:00Z',
      endsAt: '2026-10-17T00:00:00Z',
      status: 'ACTIVE',
      effectiveNow: true
    },
    {
      source: 'OPERATION_ADJUSTMENT',
      dimensionCode: 'UPLINK_MESSAGE_DAILY',
      amount: 100,
      unit: 'MESSAGE',
      window: 'UTC_DAY',
      startsAt: '2026-10-01T00:00:00Z',
      endsAt: '2026-11-01T00:00:00Z',
      status: 'PENDING',
      effectiveNow: false,
      reason: '故障补偿'
    }
  ]
})

describe('「我的套餐」展示模型', () => {
  it('摘要缺省返回 null，不把「不提供套餐事实」渲染成零额度套餐', () => {
    expect(buildProjectPlanSummaryModel(undefined)).toBeNull()
    expect(buildProjectPlanSummaryModel(null)).toBeNull()
  })

  it('FREE 摘要显示 ACTIVE、无终点服务期与冻结额度', () => {
    const model = buildProjectPlanSummaryModel(freeSummary())
    expect(model).not.toBeNull()
    expect(model?.subscribedPlan).toEqual({
      code: 'FREE',
      name: '免费版',
      revision: 'product-revision-1',
      revisionNo: 1
    })
    expect(model?.effectiveMatchesSubscribed).toBe(true)
    expect(model?.subscriptionStatusLabel).toBe('生效中')
    expect(model?.subscriptionStatusTag).toBe('success')
    expect(model?.perpetual).toBe(true)
    expect(model?.endsAt).toBeNull()
    expect(model?.periodText).toBe(PERPETUAL_PERIOD_TEXT)
    expect(model?.billingPeriodLabel).toBe('长期有效')
    expect(model?.renewalModeLabel).toBe('不续费')
    expect(model?.referencePrice).toBe('￥0')

    const limits = Object.fromEntries((model?.limits ?? []).map((row) => [row.code, row]))
    expect(limits['PROJECTS_MAX']?.label).toBe('项目数上限')
    expect(limits['PROJECTS_MAX']?.value).toBe(1)
    expect(limits['DEVICES_MAX']?.value).toBe(3)
    expect(limits['UPLINK_MESSAGE_DAILY']?.value).toBe(700)
    expect(limits['UPLINK_MESSAGE_DAILY']?.windowLabel).toBe('每 UTC 日')
    expect(limits['DOWNLINK_MESSAGE_DAILY']?.value).toBe(300)
    // 未登记的编码与窗口原样透传，不猜测含义也不补 0。
    expect(limits['UNKNOWN_DIMENSION']?.label).toBe('UNKNOWN_DIMENSION')
    expect(limits['UNKNOWN_DIMENSION']?.windowLabel).toBe('UNKNOWN_WINDOW')
  })

  it('DISABLED 权益保留且不携带任何数值额度', () => {
    const model = buildProjectPlanSummaryModel(freeSummary())
    expect(model?.enabledCapabilities.map((row) => row.code)).toEqual(['REST_API_WRITE'])
    expect(model?.disabledCapabilities.map((row) => row.code)).toEqual(['OTA'])
    expect(Object.keys(model?.disabledCapabilities[0] ?? {})).toEqual([
      'code',
      'label',
      'enabled',
      'statusLabel'
    ])
    expect(model?.disabledCapabilities[0]?.label).toBe('OTA 升级')
  })

  it('有终点服务期显示起止时刻，且运行时绑定不一致时如实标记', () => {
    const summary = freeSummary()
    summary.effectivePlan = {
      code: 'STANDARD',
      name: '标准版',
      revision: 'product-revision-2',
      revisionNo: 2
    }
    summary.effectiveMatchesSubscribed = false
    summary.perpetual = false
    summary.startsAt = '2026-09-01T00:00:00Z'
    summary.endsAt = '2027-09-01T00:00:00Z'
    summary.billingPeriod = 'YEAR'
    summary.renewalMode = 'AUTO'
    const model = buildProjectPlanSummaryModel(summary)
    expect(model?.effectiveMatchesSubscribed).toBe(false)
    expect(model?.effectivePlan?.code).toBe('STANDARD')
    expect(model?.periodText).toBe('2026-09-01 00:00 UTC 至 2027-09-01 00:00 UTC')
    expect(model?.billingPeriodLabel).toBe('按年')
    expect(model?.renewalModeLabel).toBe('自动续费')
  })

  it('服务期与状态辅助函数对未知输入保持中性', () => {
    expect(formatUtcMinute(undefined)).toBe('—')
    expect(formatUtcMinute('not-a-date')).toBe('not-a-date')
    expect(servicePeriodText(false, undefined, undefined)).toBe('— 至 —')
    expect(subscriptionStatusTag('GRACE')).toBe('warning')
    expect(subscriptionStatusTag('EXPIRED')).toBe('danger')
    expect(subscriptionStatusTag(undefined)).toBe('info')
  })

  it('运行时有效额度按编码排序、用运行时单位，扩容行保持服务端顺序', () => {
    const model = buildProjectPlanSummaryModel(runtimeSummary())
    expect(model?.effectiveLimitsAvailable).toBe(true)
    // 排序按编码，而不是服务端给出的 STORAGE_LIMIT 在前。
    expect(model?.effectiveLimits.map((row) => row.code)).toEqual(['DEVICES_MAX', 'STORAGE_LIMIT'])
    const storage = model?.effectiveLimits.find((row) => row.code === 'STORAGE_LIMIT')
    // 运行时单位是 BYTE，绝不冒用目录快照的 MB/GB。
    expect(storage?.value).toBe(10_737_418_240)
    expect(storage?.unit).toBe('BYTE')
    expect(storage?.label).toBe('对象存储')
    expect(storage?.windowLabel).toBe('存量')
    // 冻结额度仍单独保留，两类数值不互相冒充。
    expect(model?.limits.find((row) => row.code === 'PROJECTS_MAX')?.value).toBe(1)

    expect(model?.additions.map((row) => row.source)).toEqual(['PURCHASE', 'OPERATION_ADJUSTMENT'])
    const [purchase, adjustment] = model?.additions ?? []
    expect(purchase?.sourceLabel).toBe('客户购买')
    expect(purchase?.dimensionLabel).toBe('设备数上限')
    expect(purchase?.amount).toBe(2)
    expect(purchase?.unit).toBe('COUNT')
    expect(purchase?.periodText).toBe('2026-09-17 00:00 UTC 至 2026-10-17 00:00 UTC')
    expect(purchase?.statusLabel).toBe('生效中')
    expect(purchase?.statusTag).toBe('success')
    expect(purchase?.effectiveNow).toBe(true)
    expect(adjustment?.sourceLabel).toBe('运营调整')
    expect(adjustment?.statusLabel).toBe('待生效')
    expect(adjustment?.statusTag).toBe('warning')
    expect(adjustment?.effectiveNow).toBe(false)
    expect(adjustment?.reason).toBe('故障补偿')
  })

  it('缺少 effectiveQuotaDimensions 表示没有运行时额度投影，而不是零额度', () => {
    const model = buildProjectPlanSummaryModel(freeSummary())
    expect(model?.effectiveLimitsAvailable).toBe(false)
    expect(model?.effectiveLimits).toEqual([])
    expect(model?.additions).toEqual([])
  })

  it('PURCHASE 无 reason 归一为 null；未知来源/状态原样透传且不泄露操作人', () => {
    const model = buildProjectPlanSummaryModel(runtimeSummary())
    const purchase = model?.additions[0]
    expect(purchase?.reason).toBeNull()

    const stray = buildProjectPlanSummaryModel({
      ...freeSummary(),
      additions: [
        {
          source: 'UNKNOWN_SOURCE',
          dimensionCode: 'UNKNOWN_DIMENSION',
          amount: 1,
          unit: 'COUNT',
          status: 'FROZEN',
          effectiveNow: false,
          reason: ''
        }
      ]
    })
    const row = stray?.additions[0]
    expect(row?.source).toBe('UNKNOWN_SOURCE')
    expect(row?.sourceLabel).toBe('UNKNOWN_SOURCE')
    expect(row?.dimensionLabel).toBe('UNKNOWN_DIMENSION')
    expect(row?.status).toBe('FROZEN')
    expect(row?.statusLabel).toBe('FROZEN')
    expect(row?.statusTag).toBe('info')
    // 空串 reason 必须是 null，不能渲染成空白。
    expect(row?.reason).toBeNull()

    // 契约与模型都不携带操作人身份：字段集合固定，序列化后也不出现 operator/account。
    expect(Object.keys(purchase ?? {}).sort()).toEqual(
      [
        'amount',
        'dimensionCode',
        'dimensionLabel',
        'effectiveNow',
        'periodText',
        'reason',
        'source',
        'sourceLabel',
        'status',
        'statusLabel',
        'statusTag',
        'unit'
      ].sort()
    )
    expect(JSON.stringify(model)).not.toMatch(/operator|account|adjuster/i)
  })

  it('扩容/调整标签辅助函数对未知输入保持中性', () => {
    expect(additionSourceLabel('PURCHASE')).toBe('客户购买')
    expect(additionSourceLabel('OPERATION_ADJUSTMENT')).toBe('运营调整')
    expect(additionSourceLabel('UNKNOWN_SOURCE')).toBe('UNKNOWN_SOURCE')
    expect(additionSourceLabel(undefined)).toBe('—')
    expect(additionStatusTag('ACTIVE')).toBe('success')
    expect(additionStatusTag('PENDING')).toBe('warning')
    expect(additionStatusTag('FROZEN')).toBe('info')
    expect(additionStatusTag(undefined)).toBe('info')
  })
})
