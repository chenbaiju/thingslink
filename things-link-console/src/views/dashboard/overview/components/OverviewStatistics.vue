<template>
  <ElCard shadow="never" class="overview-statistics">
    <header class="overview-statistics__header">
      <div class="overview-statistics__title">
        <h3>使用统计</h3>
        <ElTag v-if="snapshot?.source === 'SIMULATED'" size="small" type="info">模拟统计</ElTag>
      </div>
      <div class="overview-statistics__controls">
        <ElRadioGroup v-model="days" size="small" aria-label="统计时间范围">
          <ElRadioButton v-for="range in ranges" :key="range.days" :value="range.days">{{
            range.label
          }}</ElRadioButton>
        </ElRadioGroup>
        <ElButton size="small" :loading="loading" @click="refresh">刷新</ElButton>
      </div>
    </header>
    <p v-if="snapshot" class="overview-statistics__caption console-description">
      {{ windowLabel }} · {{ snapshot.stepHours }} 小时 / 点 · 数量与流量累计，设备状态取时段末值
    </p>
    <p v-if="error" role="alert" class="overview-statistics__error">{{ error }}</p>
    <ElSkeleton v-if="loading && !snapshot" animated :rows="8" />
    <section
      v-for="chart in snapshot?.charts"
      :key="chart.key"
      class="overview-statistics__chart"
      :aria-label="chart.title"
    >
      <header>
        <h4>{{ chart.title }}</h4>
        <div class="overview-statistics__actions">
          <ElButton
            size="small"
            :disabled="loading || !hasValues(chart)"
            @click="exportChart(chart)"
            >导出</ElButton
          >
          <ElButton size="small" :loading="loading" @click="refresh">刷新</ElButton>
        </div>
      </header>
      <OverviewTrendChart :chart="chart" :times="times" />
    </section>
  </ElCard>
</template>

<script setup lang="ts">
  import {
    fetchOverviewTrends,
    type OverviewTrends,
    type OverviewTrend,
    type TrendDays
  } from '@/api/overview-trends'
  import { currentIdentityEpoch } from '@/utils/http/identity-scope'
  import { bucketTimes, trendCsv } from '../trend-format'
  import OverviewTrendChart from './OverviewTrendChart.vue'

  const props = defineProps<{ projectId: string; identity: string }>()
  const days = ref<TrendDays>(1)
  const ranges: { days: TrendDays; label: string }[] = [
    { days: 1, label: '24小时' },
    { days: 3, label: '3天' },
    { days: 7, label: '7天' },
    { days: 15, label: '15天' },
    { days: 30, label: '30天' }
  ]
  const snapshot = ref<OverviewTrends>()
  const loading = ref(false)
  const error = ref('')
  let generation = 0
  const times = computed(() => (snapshot.value ? bucketTimes(snapshot.value) : []))
  const windowLabel = computed(() => {
    const s = snapshot.value
    if (!s?.from || !s.to) return ''
    const formatter = new Intl.DateTimeFormat('zh-CN', {
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      hour12: false,
      timeZoneName: 'short'
    })
    return `${formatter.format(new Date(s.from))} — ${formatter.format(new Date(s.to))}`
  })
  const hasValues = (chart: OverviewTrend) =>
    chart.series?.some((s) => s.values?.some((v) => typeof v === 'number'))
  async function refresh() {
    const ticket = ++generation
    const epoch = currentIdentityEpoch()
    const project = props.projectId
    if (!project) {
      loading.value = false
      return
    }
    loading.value = true
    error.value = ''
    try {
      const result = await fetchOverviewTrends(project, days.value)
      if (
        ticket === generation &&
        epoch === currentIdentityEpoch() &&
        project === props.projectId
      ) {
        if (result.projectId !== project) throw new Error('统计响应项目不匹配')
        snapshot.value = result
      }
    } catch {
      if (ticket === generation && epoch === currentIdentityEpoch())
        error.value = snapshot.value
          ? '统计读取失败，当前保留上次结果，请重试。'
          : '统计读取失败，请重试。'
    } finally {
      if (ticket === generation) loading.value = false
    }
  }
  function exportChart(chart: OverviewTrend) {
    if (!snapshot.value || loading.value || !hasValues(chart)) return
    const blob = new Blob([trendCsv(snapshot.value, chart)], { type: 'text/csv;charset=utf-8' })
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `${chart.title}-${days.value}天-${snapshot.value.source}.csv`
    link.click()
    setTimeout(() => URL.revokeObjectURL(url), 0)
  }
  watch(
    () => [props.projectId, props.identity, days.value],
    () => {
      snapshot.value = undefined
      void refresh()
    },
    { immediate: true, flush: 'sync' }
  )
  onBeforeUnmount(() => {
    generation++
  })
</script>

<style scoped lang="scss">
  .overview-statistics {
    margin-top: 10px;
    :deep(.el-card__body) {
      padding: 20px;
    }
    &__header,
    &__controls,
    &__title,
    &__actions,
    &__chart > header {
      display: flex;
      gap: 10px;
      align-items: center;
    }
    &__header,
    &__chart > header {
      justify-content: space-between;
    }
    &__header {
      flex-wrap: wrap;
      gap: 14px;
      margin-bottom: 12px;
    }
    &__controls {
      flex-wrap: wrap;
      :deep(.el-radio-button__inner) {
        display: flex;
        align-items: center;
        height: 36px;
      }
    }
    h3 {
      margin: 0;
      font-size: 16px;
      font-weight: 600;
    }
    h4 {
      margin: 0;
      font-size: 14px;
      font-weight: 500;
    }
    &__caption {
      margin: 0 0 18px;
    }
    &__error {
      margin: 12px 0;
      font-size: 12px;
      color: var(--el-color-danger);
    }
    &__chart {
      min-width: 0;
      padding-top: 10px;
      margin-bottom: 24px;
    }
    &__chart:last-child {
      margin-bottom: 0;
    }
    &__chart > header {
      margin-bottom: 12px;
    }
    &__actions :deep(.el-button + .el-button) {
      margin-left: 0;
    }
    @media (width <= 600px) {
      :deep(.el-card__body) {
        padding: 12px;
      }
      &__controls {
        gap: 8px;
      }
    }
  }
</style>
