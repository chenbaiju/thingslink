<template>
  <div class="overview-trend">
    <div
      ref="chartRef"
      class="overview-trend__plot"
      role="img"
      :aria-label="`${chart.title}历史曲线`"
    ></div>
    <div v-if="!hasValues" class="overview-trend__empty">所选时段尚无统计数据</div>
    <div class="overview-trend__legend" aria-label="曲线图例">
      <button
        v-for="(series, index) in chart.series"
        :key="series.key"
        type="button"
        :aria-pressed="!hidden.has(series.key ?? '')"
        :class="{ 'is-muted': hidden.has(series.key ?? '') }"
        @click="toggle(series.key ?? '')"
      >
        <i :style="{ backgroundColor: seriesColor(series.key, index) }"></i>
        {{ series.label }}
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
  import type { OverviewTrend } from '@/api/overview-trends'
  import { useChart } from '@/hooks/core/useChart'
  import type { EChartsOption } from '@/plugins/echarts'
  import { formatTrendValue } from '../trend-format'

  const props = defineProps<{ chart: OverviewTrend; times: string[] }>()
  const palette = [
    '#82c8f5',
    '#fa6b75',
    '#f5bb21',
    '#25c79a',
    '#4c91f6',
    '#bb333b',
    '#a35325',
    '#147b5b',
    '#9180ff',
    '#919aa8'
  ]
  const hidden = ref(new Set<string>())
  const seriesColor = (key: string | undefined, index: number) =>
    key === 'alarm.normal' ? '#67C23A' : palette[index % palette.length]
  const { chartRef, initChart, updateChart, isDark, getTooltipStyle } = useChart()
  const hasValues = computed(() =>
    props.chart.series?.some((s) => s.values?.some((v) => typeof v === 'number'))
  )
  const formatTime = (time: string) => {
    const date = new Date(time)
    return `${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')} ${String(date.getHours()).padStart(2, '0')}:00`
  }
  const option = (): EChartsOption => ({
    animation: false,
    color: palette,
    grid: { top: 18, right: 16, bottom: 12, left: 12, containLabel: true },
    tooltip: {
      ...getTooltipStyle(),
      trigger: 'axis',
      confine: true,
      renderMode: 'richText',
      valueFormatter: (value) =>
        formatTrendValue(typeof value === 'number' ? value : null, props.chart.unit)
    },
    xAxis: {
      type: 'category',
      boundaryGap: false,
      data: props.times.map(formatTime),
      axisLine: { lineStyle: { color: isDark.value ? '#3c4350' : '#dce4ee' } },
      axisTick: { show: false },
      axisLabel: { color: isDark.value ? '#a3afc0' : '#8796ac', fontSize: 11, hideOverlap: true }
    },
    yAxis: {
      type: 'value',
      min: 0,
      minInterval: 1,
      splitNumber: 5,
      axisLabel: {
        color: isDark.value ? '#a3afc0' : '#8796ac',
        fontSize: 11,
        formatter: (value: number) => formatTrendValue(value, props.chart.unit)
      },
      splitLine: { lineStyle: { color: isDark.value ? '#303744' : '#edf1f6' } }
    },
    series: (props.chart.series ?? []).map((s, index) => ({
      id: s.key,
      name: s.label,
      type: 'line',
      symbol: 'none',
      connectNulls: false,
      data: hidden.value.has(s.key ?? '') ? (s.values ?? []).map(() => null) : (s.values ?? []),
      lineStyle: { width: 2, color: seriesColor(s.key, index) },
      itemStyle: { color: seriesColor(s.key, index) },
      emphasis: { focus: 'series' }
    }))
  })
  function toggle(key: string) {
    const next = new Set(hidden.value)
    if (next.has(key)) next.delete(key)
    else next.add(key)
    hidden.value = next
  }
  const renderVisible = () => updateChart(option())
  onMounted(() => {
    chartRef.value?.addEventListener('chartVisible', renderVisible)
    initChart(option())
  })
  onBeforeUnmount(() => chartRef.value?.removeEventListener('chartVisible', renderVisible))
  watch([() => props.chart, () => props.times, hidden, isDark], () => updateChart(option()), {
    deep: true
  })
</script>

<style scoped lang="scss">
  .overview-trend {
    position: relative;
    min-width: 0;

    &__plot {
      width: 100%;
      height: 270px;
    }
    &__empty {
      position: absolute;
      top: 44%;
      left: 50%;
      padding: 6px 12px;
      font-size: 12px;
      color: var(--el-text-color-secondary);
      background: var(--el-bg-color);
      transform: translate(-50%, -50%);
    }
    &__legend {
      display: flex;
      flex-wrap: wrap;
      gap: 8px 16px;
      justify-content: center;
      padding: 16px 0 8px;
    }
    button {
      display: inline-flex;
      gap: 5px;
      align-items: center;
      padding: 0;
      font-size: 12px;
      color: var(--el-text-color-secondary);
      cursor: pointer;
      background: transparent;
      border: 0;
    }
    i {
      width: 9px;
      height: 9px;
      border-radius: 50%;
    }
    .is-muted {
      opacity: 0.4;
    }
    button:focus-visible {
      outline: 2px solid var(--el-color-primary);
      outline-offset: 4px;
    }
  }
</style>
