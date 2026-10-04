<script setup lang="ts">
import { computed } from 'vue'
import { historyGeometry, historyRange, historySegments, interactionStateLabel, type HistorySeriesView } from './history-presentation'
const props = defineProps<{ componentId: string; series: readonly HistorySeriesView[]; showLegend: boolean }>()
const granularityLabels: Record<string, string> = { RAW: '原始采样', ONE_MINUTE: '分钟聚合', ONE_HOUR: '小时聚合', ONE_DAY: '日聚合' }
const aggregationLabels: Record<string, string> = { AVG: '平均值', MIN: '最小值', MAX: '最大值', SUM: '合计', COUNT: '样本数' }
const ranges = computed(() => new Map(props.series.map(series => [series.id, series.history ? historyRange(series.history.points) : null])))
function segments(series: HistorySeriesView) {
  if (!series.history) return []
  const geometry = historyGeometry(series.history.points)
  const lookup = new Map(geometry.map(point => [point.point, point]))
  return historySegments(series.history.points, series.history.actualGranularity).map(points => ({
    key: `${points[0].thingModelVersionId ?? 'LEGACY'}/${points[0].modelVersion}`,
    points: points.map(point => lookup.get(point)!),
  }))
}
function count(value: unknown): string { return typeof value === 'object' && value && 'lexical' in value ? String(value.lexical) : String(value) }
</script>
<template>
  <div :data-testid="`history-chart-${componentId}`" :data-state="series.some(entry => entry.state === 'VALUE') ? 'VALUE' : series[0]?.state ?? 'UNSELECTED'">
    <section v-for="(entry, index) in props.series" :key="entry.id" :data-testid="`history-series-${entry.id}`" :data-state="entry.state">
      <h4 v-if="showLegend || props.series.length > 1">{{ entry.label }}</h4>
      <p v-if="entry.state !== 'VALUE'" role="status">{{ interactionStateLabel(entry.state) }}</p>
      <template v-else-if="entry.history">
        <p :data-testid="`history-granularity-${entry.id}`">实际粒度：{{ granularityLabels[entry.history.actualGranularity] }}{{ entry.history.actualGranularity === 'RAW' ? '（原始采样，不推测采样间隔）' : '' }}；聚合：{{ aggregationLabels[entry.history.aggregation] }}；历史单位未提供</p>
        <p v-if="ranges.get(entry.id)" :data-testid="`history-range-${entry.id}`">
          时间：{{ ranges.get(entry.id)?.from }} ～ {{ ranges.get(entry.id)?.to }}；
          数值范围：{{ ranges.get(entry.id)?.minimum }} ～ {{ ranges.get(entry.id)?.maximum }}
        </p>
        <p v-if="entry.history.points.length === 0">当前时段没有历史数据</p>
        <svg v-else viewBox="0 0 640 220" role="img" :aria-label="`${entry.label}历史，按模型版本分段`" class="history-svg">
          <path d="M24 12V196H620" fill="none" stroke="currentColor" />
          <g v-for="(segment, segmentIndex) in segments(entry)" :key="segmentIndex" data-history-segment :data-version-pair="segment.key">
            <polyline :points="segment.points.map(point => `${point.x},${point.y}`).join(' ')" fill="none" :stroke="['#176bd1', '#b64810', '#278048', '#9b45b5'][index % 4]" stroke-width="2" />
            <circle v-for="(point, pointIndex) in segment.points" :key="pointIndex" :cx="point.x" :cy="point.y" r="3" fill="currentColor"><title>{{ point.point.ts }}；{{ point.point.value.lexical }}；样本{{ count(point.point.sampleCount) }}；版本{{ segment.key }}</title></circle>
          </g>
        </svg>
        <details><summary>查看完整原始点与版本（{{ entry.history.points.length }}点）</summary>
          <ol><li v-for="(point, pointIndex) in entry.history.points" :key="pointIndex">{{ point.ts }} · {{ point.value.lexical }} · 样本{{ count(point.sampleCount) }} · {{ point.thingModelVersionId ?? 'LEGACY_UNVERSIONED' }}/{{ point.modelVersion }}</li></ol>
        </details>
      </template>
    </section>
  </div>
</template>
<style scoped>
.history-svg { display: block; width: 100%; height: auto; min-height: 120px; }
p, li, summary, h4 { overflow-wrap: anywhere; white-space: pre-wrap; }
p, h4 { margin: 6px 0; }
ol { padding-left: 24px; }
</style>
