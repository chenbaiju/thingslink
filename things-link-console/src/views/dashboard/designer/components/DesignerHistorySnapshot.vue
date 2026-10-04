<template>
  <section aria-label="版本化历史快照">
    <p>时间均为UTC；历史单位未提供，不套用当前模型单位。</p>
    <article v-for="series in history.series" :key="series.id" :data-history-series="series.id">
      <h4>{{ series.label }}</h4>
      <p>{{ series.text }}</p>
      <template v-if="series.result?.status === 'READY'">
        <p
          >请求粒度：{{ series.result.requestedGranularity }}；实际粒度：<strong>{{
            series.result.actualGranularity
          }}</strong
          >；聚合：{{ series.result.aggregation }}</p
        >
        <p v-if="series.result.actualGranularity === 'RAW'">原始采样，未推定缺失周期。</p>
        <p v-else>聚合结果可能稍后更新。</p>
        <p v-if="range(series)"
          >范围：{{ range(series)?.from }} 至 {{ range(series)?.to }}；最小
          {{ range(series)?.minimum }}，最大 {{ range(series)?.maximum }}</p
        >
        <svg
          v-if="series.result.points.length"
          viewBox="0 0 640 220"
          role="img"
          :aria-label="`${series.label}历史趋势，版本变化或缺桶断开`"
        >
          <g v-for="(segment, index) in segments(series)" :key="index">
            <polyline
              :points="segment.map((point) => `${point.x},${point.y}`).join(' ')"
              fill="none"
              stroke="currentColor"
            />
            <circle
              v-for="(entry, pointIndex) in segment"
              :key="pointIndex"
              :cx="entry.x"
              :cy="entry.y"
              r="2"
            >
              <title>
                {{ entry.point.ts }}：{{ entry.point.value.lexical }}；版本{{
                  entry.point.modelVersion
                }}
              </title>
            </circle>
          </g>
        </svg>
        <p v-if="history.showLegend">{{ series.label }} · {{ versions(series) }}</p>
        <details v-if="series.result.points.length">
          <summary>查看真实历史点（{{ series.result.points.length }}）</summary>
          <div class="history-scroll"
            ><table>
              <thead
                ><tr
                  ><th>时间UTC</th><th>值</th><th>样本数</th><th>版本</th><th>模型版本ID</th></tr
                ></thead
              >
              <tbody
                ><tr v-for="(point, index) in pointPage(series)" :key="index"
                  ><td>{{ point.ts }}</td
                  ><td>{{ point.value.lexical }}</td
                  ><td>{{ point.sampleCount }}</td
                  ><td>{{ point.modelVersion }}</td
                  ><td>{{ point.thingModelVersionId ?? '旧版本来源未知' }}</td></tr
                ></tbody
              >
            </table></div
          >
          <div class="console-actions">
            <button
              :disabled="(pages[series.id] ?? 0) === 0"
              @click="pages[series.id] = (pages[series.id] ?? 0) - 1"
              >上一页历史点</button
            >
            <button
              :disabled="((pages[series.id] ?? 0) + 1) * 50 >= series.result.points.length"
              @click="pages[series.id] = (pages[series.id] ?? 0) + 1"
              >下一页历史点</button
            >
          </div>
        </details>
      </template>
    </article>
  </section>
</template>
<script setup lang="ts">
  import { ref, watch } from 'vue'
  import {
    historySegments,
    historyGeometry,
    historyRange
  } from '@things-link/client-contracts/dashboard/v1'
  import type { PreviewRow, PreviewHistorySeries } from '@/features/dashboard/device-preview'
  const props = defineProps<{ history: NonNullable<PreviewRow['history']> }>()
  const pages = ref<Record<string, number>>({})
  watch(
    () => props.history,
    () => {
      pages.value = {}
    }
  )
  function range(series: PreviewHistorySeries) {
    return historyRange(series.result?.points ?? [])
  }
  function versions(series: PreviewHistorySeries) {
    return [
      ...new Set(
        series.result?.points.map(
          (point) => `${point.modelVersion}（${point.thingModelVersionId ?? '旧版本来源未知'}）`
        )
      )
    ].join('、')
  }
  function pointPage(series: PreviewHistorySeries) {
    const from = (pages.value[series.id] ?? 0) * 50
    return series.result?.points.slice(from, from + 50) ?? []
  }
  function segments(series: PreviewHistorySeries) {
    const points = series.result?.points ?? []
    const geometry = new Map(historyGeometry(points).map((entry) => [entry.point, entry]))
    return historySegments(points, series.result?.actualGranularity ?? 'RAW').map((segment) =>
      segment.map((point) => geometry.get(point)!)
    )
  }
</script>
<style scoped>
  svg {
    width: 100%;
    max-width: 640px;
  }
  .history-scroll {
    max-width: 100%;
    overflow-x: auto;
  }
  table {
    width: 100%;
    border-collapse: collapse;
  }
  th,
  td {
    padding: 4px;
    text-align: left;
    overflow-wrap: anywhere;
  }
</style>
