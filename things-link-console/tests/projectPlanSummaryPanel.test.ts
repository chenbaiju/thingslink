import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import { defineComponent, h, type VNode, type VNodeChild } from 'vue'

import type { ProjectPlanSummaryResponse } from '@/api/quota'
import zh from '@/locales/langs/zh.json'
import PlanSummaryPanel from '@/views/project/settings/PlanSummaryPanel.vue'

/**
 * 「我的套餐」面板的组件级验收（S14-4c 展示面）。
 *
 * 展示模型（`plan-summary-model`）已由单元用例钉住，这里补的是**真实挂载后用户能看到什么**：
 * 有效额度段用运行时单位展示、扩容与调整段列出购买与人工调整（含原因、不含操作人）、
 * 没有运行时投影时给说明而不是零额度、没有扩容时给空态、以及 S14-6b 修复后的宽限/受限期仍可读。
 *
 * 用**真实 i18n**（zh 文案由仓库语言包提供）挂载，断言的是实际渲染文案与真实数据绑定；
 * Element Plus 的内部实现用轻量替身替换：jsdom 下 `ElTable` 不渲染任何数据行（布局测量恒为 0），
 * 若用真组件挂载，本用例会退化成「断言空表」。替身保留 `data` → 行 → 列 scoped slot 的数据流，
 * 因此被测的仍是本面板自己的列模板与分支，而不是 Element Plus 的表格实现。
 */

/** 表格替身：按 `data` 渲染行，并把每个列的 scoped slot 以 `{ row }` 求值；空数据渲染 empty 槽。 */
const ElTableStub = defineComponent({
  name: 'ElTable',
  props: { data: { type: Array, default: () => [] } },
  setup(props, { slots }) {
    return () => {
      const columns = (slots.default?.() ?? []) as VNode[]
      const rows = (props.data ?? []) as Record<string, unknown>[]
      if (rows.length === 0) {
        return h('div', { class: 'el-table-empty' }, slots.empty ? [slots.empty()] : [])
      }
      return h(
        'table',
        rows.map((row) =>
          h(
            'tr',
            columns.map((column) => {
              const cell = (column.children as { default?: (scope: unknown) => VNodeChild } | null)
                ?.default
              const content = typeof cell === 'function' ? cell({ row }) : null
              return h('td', {}, content ? [content] : [])
            })
          )
        )
      )
    }
  }
})

/** 列替身：行渲染由表格替身驱动，列自身不需要挂载。 */
const ElTableColumnStub = defineComponent({ name: 'ElTableColumn', render: () => null })

/** 卡片替身：保留 header 与默认槽。 */
const ElCardStub = defineComponent({
  name: 'ElCard',
  setup(_props, { slots }) {
    return () => h('div', [slots.header?.(), slots.default?.()])
  }
})

/** 标签替身：渲染默认槽。 */
const ElTagStub = defineComponent({
  name: 'ElTag',
  setup(_props, { slots }) {
    return () => h('span', slots.default?.())
  }
})

/** 提示替身：渲染 title 与默认槽。 */
const ElAlertStub = defineComponent({
  name: 'ElAlert',
  props: { title: { type: String, default: '' } },
  setup(props, { slots }) {
    return () => h('div', [props.title, slots.default?.()])
  }
})

/** 空态替身：渲染 description。 */
const ElEmptyStub = defineComponent({
  name: 'ElEmpty',
  props: { description: { type: String, default: '' } },
  setup(props) {
    return () => h('span', props.description)
  }
})

/** 带 i18n 与 Element Plus 替身的挂载包装。 */
function panel(summary?: ProjectPlanSummaryResponse | null) {
  return mount(PlanSummaryPanel, {
    props: { summary },
    global: {
      plugins: [createI18n({ legacy: false, locale: 'zh', messages: { zh } })],
      stubs: {
        ElCard: ElCardStub,
        ElTable: ElTableStub,
        ElTableColumn: ElTableColumnStub,
        ElTag: ElTagStub,
        ElAlert: ElAlertStub,
        ElEmpty: ElEmptyStub
      }
    }
  })
}

/** FREE 档摘要；默认带运行时有效维度与两条扩容/调整溯源行。 */
function summary(overrides: Partial<ProjectPlanSummaryResponse> = {}): ProjectPlanSummaryResponse {
  return {
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
      { code: 'DEVICES_MAX', value: 3, unit: 'COUNT', window: 'NONE' },
      { code: 'STORAGE_LIMIT', value: 100, unit: 'MB', window: 'NONE' }
    ],
    capabilities: [{ code: 'OTA', enabled: false }],
    effectiveQuotaDimensions: [
      { code: 'DEVICES_MAX', value: 6, unit: 'COUNT', window: 'NONE' },
      { code: 'STORAGE_LIMIT', value: 1073741824, unit: 'BYTE', window: 'NONE' }
    ],
    additions: [
      {
        source: 'PURCHASE',
        dimensionCode: 'DEVICES_MAX',
        amount: 2,
        unit: 'COUNT',
        window: 'NONE',
        startsAt: '2026-09-17T00:00:00Z',
        endsAt: '2027-09-17T00:00:00Z',
        status: 'ACTIVE',
        effectiveNow: true
      },
      {
        source: 'OPERATION_ADJUSTMENT',
        dimensionCode: 'DEVICES_MAX',
        amount: 1,
        unit: 'COUNT',
        window: 'NONE',
        startsAt: '2026-10-01T00:00:00Z',
        endsAt: '2026-11-01T00:00:00Z',
        status: 'PENDING',
        effectiveNow: false,
        reason: '工单 INC-2026-6B 补偿'
      }
    ],
    ...overrides
  }
}

describe('Console「我的套餐」面板（S14-4c 展示面）', () => {
  it('同时展示冻结额度与运行时有效额度，并列出扩容与人工调整溯源', () => {
    const wrapper = panel(summary())

    // 冻结额度（目录表示）仍然保留，且与运行时有效值分别渲染：有效值不得冒充「这个套餐就是 6 台」。
    const frozen = wrapper.get('[data-testid="plan-frozen-limits"]').text()
    // 目录表示必须带单位：只显示「100」无法区分 100 MB 与 100 GB。
    expect(frozen).toContain('3 COUNT')
    expect(frozen).toContain('100 MB')
    const effective = wrapper.get('[data-testid="plan-effective-limits"]').text()
    // 运行时单位：设备 6；对象存储按字节而不是目录快照的 MB。
    expect(effective).toContain('6 COUNT')
    expect(effective).toContain('1,073,741,824 BYTE')
    expect(wrapper.find('[data-testid="plan-effective-unavailable"]').exists()).toBe(false)

    // 溯源段：购买与人工调整各一行，人工调整显示原因，状态与「已计入」由服务端结论驱动。
    const additions = wrapper.get('[data-testid="plan-additions"]').text()
    expect(additions).toContain('客户购买')
    expect(additions).toContain('运营调整')
    expect(additions).toContain('2 COUNT')
    expect(additions).toContain('1 COUNT')
    expect(additions).toContain('工单 INC-2026-6B 补偿')
    expect(additions).toContain('生效中')
    expect(additions).toContain('待生效')
    expect(additions).toContain('已计入')
    expect(additions).toContain('未计入')
    // 人工调整只暴露原因，不暴露操作人账号：面板模型里根本没有该字段。
    expect(additions).not.toContain('operator')
  })

  it('没有运行时额度投影时给说明而不是零额度', () => {
    const wrapper = panel(summary({ effectiveQuotaDimensions: undefined }))

    expect(wrapper.find('[data-testid="plan-effective-limits"]').exists()).toBe(false)
    expect(wrapper.get('[data-testid="plan-effective-unavailable"]').text()).toContain(
      '运行时绑定不是可售套餐模板'
    )
    // 不得把缺省渲染成一组零值，也不得回退展示目录冻结值冒充运行时值。
    expect(wrapper.text()).not.toContain('1,073,741,824')
  })

  it('没有扩容与调整时展示空态而不是伪造行', () => {
    const wrapper = panel(summary({ additions: [] }))

    const additions = wrapper.get('[data-testid="plan-additions"]').text()
    expect(additions).not.toContain('客户购买')
    expect(additions).not.toContain('运营调整')
    expect(additions).toContain('—')
  })

  it('宽限期仍返回摘要（有效额度不消失）并提示运行时绑定与订阅不一致', () => {
    const wrapper = panel(
      summary({
        subscriptionStatus: 'GRACE',
        perpetual: false,
        startsAt: '2026-01-01T00:00:00Z',
        endsAt: '2026-09-01T00:00:00Z',
        effectiveMatchesSubscribed: false
      })
    )
    const text = wrapper.text()

    // S14-6b 修复的正是这条路径：只认 ACTIVE 的旧实现会让控制台在整段宽限期「没有套餐事实」。
    expect(text).toContain('宽限期')
    expect(text).toContain('服务期')
    expect(wrapper.get('[data-testid="plan-effective-limits"]').text()).toContain('6 COUNT')
    // 运行时绑定与订阅修订版不一致时必须显式提示，而不是让用户以为额度就是订阅档位的。
    expect(text).toContain('运行时实际绑定的档位与订阅锁定的修订版不一致')
  })

  it('受限免费期仍展示档位与有效额度，状态按受限标签渲染', () => {
    const wrapper = panel(
      summary({
        subscriptionStatus: 'RESTRICTED_FREE',
        perpetual: false,
        startsAt: '2026-01-01T00:00:00Z',
        endsAt: '2026-09-01T00:00:00Z',
        effectiveMatchesSubscribed: false
      })
    )
    const text = wrapper.text()

    expect(text).toContain('宽限结束降级')
    expect(wrapper.get('[data-testid="plan-effective-limits"]').text()).toContain('6 COUNT')
    expect(wrapper.get('[data-testid="plan-additions"]').text()).toContain('已计入')
  })

  it('摘要缺省时说明本页不提供套餐事实', () => {
    const wrapper = panel(null)

    expect(wrapper.get('[data-testid="plan-summary-unavailable"]').text()).toContain(
      '当前账号不是该项目归属租户的成员'
    )
    expect(wrapper.find('[data-testid="plan-frozen-limits"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="plan-effective-limits"]').exists()).toBe(false)
  })
  it('OTA目录声明不误报未交付，旧语义不被猜作权限', () => {
    const current = panel(
      summary({ capabilities: [{ code: 'OTA', enabled: false, enforcement: 'CATALOG_ONLY' }] })
    )
    expect(current.text()).toContain('OTA 升级 · 目录未包含')
    expect(current.text()).toContain('目录标记不代表当前账号权限')
    expect(current.text()).not.toContain('OTA 升级（未交付）')
    const legacy = panel(summary({ capabilities: [{ code: 'OTA', enabled: false }] }))
    expect(legacy.text()).toContain('OTA 升级 · 语义未确认')
  })
})
