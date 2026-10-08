import { describe, expect, it } from 'vitest'
import { buildPlanCatalogModel } from '@/features/plan/catalog-model'
import { buildPlanComparisonRows } from '@/features/plan/comparison-model'

describe('套餐横向对比数据', () => {
  it('按维度并集对齐套餐，区分真实零额度与缺失数据，保留未知维度', () => {
    const tiers = buildPlanCatalogModel([
      {
        code: 'FREE',
        quotaDimensions: [{ code: 'DEVICES_MAX', value: 0, unit: 'COUNT', window: 'NONE' }]
      },
      {
        code: 'STANDARD',
        quotaDimensions: [
          { code: 'FUTURE', value: 42, unit: 'GB', window: 'NONE' },
          { code: 'DEVICES_MAX', value: 100, unit: 'COUNT', window: 'NONE' }
        ]
      }
    ]).tiers
    const rows = buildPlanComparisonRows(tiers)
    expect(
      rows.find((row) => row.key === 'quota:DEVICES_MAX')?.cells.map((cell) => cell.text)
    ).toEqual(['0', '100'])
    const unknown = rows.find((row) => row.key === 'quota:FUTURE')!
    expect(unknown.label).toBe('FUTURE')
    expect(unknown.cells.map((cell) => cell.text)).toEqual(['未提供', '42 GB'])
  })
  it('只有已明确目录语义的包含权益显示勾选，未包含与缺失权益区分展示', () => {
    const tiers = buildPlanCatalogModel([
      {
        code: 'FREE',
        entitlements: [
          { code: 'OTA', enabled: false, enforcement: 'CATALOG_ONLY' },
          { code: 'OBJECT_STORAGE', enabled: true, enforcement: 'CATALOG_ONLY' }
        ]
      },
      {
        code: 'STANDARD',
        entitlements: [{ code: 'OTA', enabled: true, enforcement: 'CATALOG_ONLY' }]
      }
    ]).tiers
    const rows = buildPlanComparisonRows(tiers)
    expect(
      rows.find((row) => row.key === 'capability:OTA')?.cells.map((cell) => cell.kind)
    ).toEqual(['excluded', 'included'])
    expect(
      rows.find((row) => row.key === 'capability:OBJECT_STORAGE')?.cells.map((cell) => cell.kind)
    ).toEqual(['included', 'missing'])
  })
  it('语义未确认的权益不能被勾选为可用，不凭空补充数值额度', () => {
    const tiers = buildPlanCatalogModel([
      { code: 'FREE', entitlements: [{ code: 'FUTURE', enabled: true }] }
    ]).tiers
    const rows = buildPlanComparisonRows(tiers)
    expect(rows.filter((row) => !row.section)).toEqual([
      {
        key: 'capability:FUTURE',
        label: 'FUTURE',
        section: false,
        cells: [{ kind: 'unknown', text: '待确认', detail: '接口未明确此项权益的适用语义' }]
      }
    ])
  })
  it('额度使用中文单位，保留计量窗口，缺失项不推断为零', () => {
    const tiers = buildPlanCatalogModel([
      {
        code: 'FREE',
        quotaDimensions: [
          { code: 'UPLINK_MESSAGE_DAILY', value: 150000, unit: 'MESSAGE', window: 'UTC_DAY' },
          { code: 'HISTORY_WINDOW', value: 7, unit: 'DAY', window: 'ROLLING' }
        ]
      }
    ]).tiers
    const rows = buildPlanComparisonRows(tiers)
    expect(rows.find((row) => row.key === 'quota:UPLINK_MESSAGE_DAILY')?.cells[0]).toEqual({
      kind: 'value',
      text: '150,000 条',
      detail: '计量窗口：每 UTC 日'
    })
    expect(rows.find((row) => row.key === 'quota:HISTORY_WINDOW')?.cells[0].text).toBe('7 天')
  })
})
