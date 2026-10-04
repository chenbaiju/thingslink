import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import ArtLineChart from '@/components/core/charts/art-line-chart/index.vue'
import type { LineChartProps } from '@/types/component/chart'

// 图表依赖在 jsdom 下无法真实初始化，mock 掉 echarts 与图表 hook；
// useChartComponent 的 isEmpty 用真实的 checkEmpty 计算，因此本测试仍会执行组件内的
// 空序列判定逻辑，覆盖「首个点为 null 时崩溃」的回归（A6-06）。
const { initChartSpy } = vi.hoisted(() => ({ initChartSpy: vi.fn() }))

vi.mock('@/plugins/echarts', () => ({
  graphic: { LinearGradient: vi.fn(() => ({})) }
}))

vi.mock('@/utils/ui', () => ({
  getCssVar: () => '#409EFF',
  hexToRgba: () => ({ rgba: 'rgba(64,158,255,0.2)' })
}))

vi.mock('@/hooks/core/useChart', async () => {
  const { computed, ref } = await import('vue')
  return {
    useChartOps: () => ({ chartHeight: '260px', colors: ['#409EFF'] }),
    useChartComponent: ({ checkEmpty }: { checkEmpty: () => boolean }) => ({
      chartRef: ref(null),
      initChart: initChartSpy,
      getAxisLineStyle: () => ({}),
      getAxisLabelStyle: () => ({}),
      getAxisTickStyle: () => ({}),
      getSplitLineStyle: () => ({}),
      getTooltipStyle: () => ({}),
      getLegendStyle: () => ({}),
      getGridWithLegend: () => ({}),
      isEmpty: computed(() => checkEmpty())
    })
  }
})

// 用自定义指令桩把 v-loading 的值落到 DOM 属性，便于断言加载态。
const loadingDirective = {
  mounted(el: Element, binding: { value: boolean }) {
    el.setAttribute('data-loading', String(binding.value))
  },
  updated(el: Element, binding: { value: boolean }) {
    el.setAttribute('data-loading', String(binding.value))
  }
}

describe('折线图组件状态与 null 安全（A6-06）', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    initChartSpy.mockClear()
  })
  afterEach(() => {
    vi.useRealTimers()
  })

  const mountChart = (data: LineChartProps['data'], props: Record<string, unknown> = {}) =>
    mount(ArtLineChart, {
      props: { data, xAxisData: ['a', 'b'], ...props },
      global: { directives: { loading: loadingDirective } }
    })

  it('[null] 不崩溃，且视为空（不初始化图表）', async () => {
    const wrapper = mountChart([null])
    await wrapper.vm.$nextTick()
    expect(wrapper.html()).toContain('relative')
    expect(initChartSpy).not.toHaveBeenCalled()
    wrapper.unmount()
  })

  it('[null, 1] 不崩溃，且非空（会初始化图表）', async () => {
    const wrapper = mountChart([null, 1])
    await wrapper.vm.$nextTick()
    expect(wrapper.html()).toContain('relative')
    expect(initChartSpy).toHaveBeenCalled()
    wrapper.unmount()
  })

  it('[0, null] 不崩溃，且含真实读数 0 视为有数据（会初始化图表）', async () => {
    const wrapper = mountChart([0, null])
    await wrapper.vm.$nextTick()
    expect(initChartSpy).toHaveBeenCalled()
    wrapper.unmount()
  })

  it('[0, 0] 含真实读数 0，视为有数据（会初始化图表）', async () => {
    const wrapper = mountChart([0, 0])
    await wrapper.vm.$nextTick()
    expect(initChartSpy).toHaveBeenCalled()
    wrapper.unmount()
  })

  it('真正的多序列输入不崩溃', async () => {
    const wrapper = mountChart([
      { name: 'a', data: [1, 2] },
      { name: 'b', data: [3, 4] }
    ])
    await wrapper.vm.$nextTick()
    expect(wrapper.html()).toContain('relative')
    wrapper.unmount()
  })

  it('loading 透传到 v-loading 指令（加载态）', async () => {
    const wrapper = mountChart([1, 2], { loading: true })
    await wrapper.vm.$nextTick()
    expect(wrapper.find('[data-loading="true"]').exists()).toBe(true)
    wrapper.unmount()
  })
})
