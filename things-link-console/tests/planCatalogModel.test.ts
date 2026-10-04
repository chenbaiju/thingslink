import { describe, expect, it } from 'vitest'
import type { PlanResponse } from '@/api/plan'
import {
  buildPlanCatalogModel,
  buildPlanTierModel,
  formatCents,
  isSaleable,
  capabilityStatusLabel
} from '@/features/plan/catalog-model'

/** 构造一个最小可用的契约档位；测试只覆盖关心的字段，其余保持缺省。 */
const tier = (overrides: Partial<PlanResponse>): PlanResponse => ({
  code: 'FREE',
  revision: 'product-revision-1',
  revisionNo: 1,
  name: '免费版',
  saleStatus: 'ON_SALE',
  billingPeriod: 'NONE',
  currency: 'CNY',
  priceCents: 0,
  referencePriceCents: 0,
  referencePriceCurrency: 'CNY',
  quotaDimensions: [
    { code: 'PROJECTS_MAX', value: 1, unit: 'COUNT', window: 'NONE' },
    { code: 'UPLINK_MESSAGE_DAILY', value: 700, unit: 'MESSAGE', window: 'UTC_DAY' }
  ],
  entitlements: [
    { code: 'OBJECT_STORAGE', enabled: true },
    { code: 'OTA', enabled: false }
  ],
  ...overrides
})

describe('套餐目录展示模型', () => {
  it('金额按分格式化，缺价显示占位符而不是 ￥0', () => {
    expect(formatCents(0, 'CNY')).toBe('￥0')
    expect(formatCents(298000, 'CNY')).toBe('￥2,980')
    expect(formatCents(1198000, 'CNY')).toBe('￥11,980')
    expect(formatCents(298050, 'CNY')).toBe('￥2,980.50')
    expect(formatCents(-12345, 'CNY')).toBe('-￥123.45')
    expect(formatCents(undefined, 'CNY')).toBe('—')
    expect(formatCents(null, 'CNY')).toBe('—')
  })

  it('参考价不是成交价：未开售档位带参考价仍不可售，主展示价标记为参考', () => {
    const standard = tier({
      code: 'STANDARD',
      name: '标准版',
      saleStatus: 'NOT_FOR_SALE',
      billingPeriod: 'YEAR',
      priceCents: undefined,
      referencePriceCents: 298000
    })
    expect(isSaleable(standard)).toBe(false)
    const model = buildPlanTierModel(standard)
    expect(model.saleable).toBe(false)
    expect(model.salePrice).toBeNull()
    expect(model.referencePrice).toBe('￥2,980')
    expect(model.referencePriceCurrency).toBe('CNY')
    expect(model.priceIsReference).toBe(true)
    expect(model.displayPrice).toBe('￥2,980')
    expect(model.saleStatusLabel).toBe('未开售')
    expect(model.saleStatusTag).toBe('info')
  })

  it('四档模型：只有 FREE 可售，参考价逐档显示，销售状态区分标签色', () => {
    const model = buildPlanCatalogModel([
      tier({ code: 'FREE', name: '免费版', referencePriceCents: 0 }),
      tier({
        code: 'STANDARD',
        name: '标准版',
        saleStatus: 'NOT_FOR_SALE',
        billingPeriod: 'YEAR',
        priceCents: undefined,
        referencePriceCents: 298000
      }),
      tier({
        code: 'ENTERPRISE',
        name: '企业版',
        saleStatus: 'NOT_FOR_SALE',
        billingPeriod: 'YEAR',
        priceCents: undefined,
        referencePriceCents: 598000
      }),
      tier({
        code: 'PROFESSIONAL',
        name: '专业版',
        saleStatus: 'NOT_FOR_SALE',
        billingPeriod: 'YEAR',
        priceCents: undefined,
        referencePriceCents: 1198000
      })
    ])

    expect(model.saleableCount).toBe(1)
    expect(model.tiers.map((item) => item.code)).toEqual([
      'FREE',
      'STANDARD',
      'ENTERPRISE',
      'PROFESSIONAL'
    ])
    expect(model.tiers.map((item) => item.referencePrice)).toEqual([
      '￥0',
      '￥2,980',
      '￥5,980',
      '￥11,980'
    ])
    expect(model.tiers.map((item) => item.saleStatusTag)).toEqual([
      'success',
      'info',
      'info',
      'info'
    ])
    expect(model.tiers[0].salePrice).toBe('￥0')
    expect(model.tiers.slice(1).every((item) => item.salePrice === null)).toBe(true)
    expect(model.tiers[0].priceIsReference).toBe(false)
    expect(model.tiers[3].billingPeriodLabel).toBe('按年')
  })

  it('DISABLED 权益单独分组且不携带任何数值额度，未知编码原样透传', () => {
    const model = buildPlanTierModel(
      tier({
        quotaDimensions: [
          { code: 'PROJECTS_MAX', value: 1, unit: 'COUNT', window: 'NONE' },
          { code: 'FUTURE_DIMENSION', value: 42, unit: 'COUNT', window: 'NONE' }
        ],
        entitlements: [
          { code: 'OBJECT_STORAGE', enabled: true },
          { code: 'OTA', enabled: false },
          { code: 'FUTURE_CAPABILITY', enabled: false }
        ]
      })
    )

    expect(model.enabledCapabilities.map((item) => item.code)).toEqual(['OBJECT_STORAGE'])
    expect(model.disabledCapabilities.map((item) => item.code)).toEqual([
      'FUTURE_CAPABILITY',
      'OTA'
    ])
    // 未交付能力不得被编造额度：权益行没有 value/quota 字段。
    for (const capability of model.disabledCapabilities) {
      expect(Object.keys(capability).sort()).toEqual(['code', 'enabled', 'label', 'statusLabel'])
      expect(capability).not.toHaveProperty('value')
      expect(capability).not.toHaveProperty('quota')
    }
    expect(model.disabledCapabilities[1].label).toBe('OTA 升级')
    // 未知维度原样透传编码与数值，不折叠也不补 0。
    expect(model.quotas.map((row) => row.code)).toEqual(['FUTURE_DIMENSION', 'PROJECTS_MAX'])
    expect(model.quotas[0].label).toBe('FUTURE_DIMENSION')
    expect(model.quotas[0].value).toBe(42)
    expect(model.quotas[1].label).toBe('项目数上限')
  })
  it('明确目录语义且不猜测旧响应或未知将来语义的权限', () => {
    expect(capabilityStatusLabel(true, 'CATALOG_ONLY')).toBe('目录包含')
    expect(capabilityStatusLabel(false, 'CATALOG_ONLY')).toBe('目录未包含')
    for (const mode of [undefined, null, '', 'FUTURE_MODE']) {
      expect(capabilityStatusLabel(true, mode)).toBe('语义未确认')
      expect(capabilityStatusLabel(false, mode)).toBe('语义未确认')
    }
    const model = buildPlanTierModel(
      tier({ entitlements: [{ code: 'OTA', enabled: false, enforcement: 'CATALOG_ONLY' }] })
    )
    expect(model.disabledCapabilities[0]).toMatchObject({
      label: 'OTA 升级',
      statusLabel: '目录未包含'
    })
  })
})
