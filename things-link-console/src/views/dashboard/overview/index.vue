<!--
  项目概要。

  S5-3 的统计结果必须由后端聚合并以短 TTL Redis 快照派生；浏览器不自行汇总设备或
  消息列表，否则会把分页数据误当全量事实。页面只消费 OpenAPI 生成的响应类型，避免
  浏览器模型与后端统计口径分别演进。
-->
<template>
  <div class="console-page project-overview" v-loading="loading">
    <ElEmpty v-if="!projectId && !loading" description="请先进入一个项目后查看概要" />

    <template v-else>
      <section class="project-overview__cards" aria-label="设备数量">
        <ElCard
          v-for="item in deviceCountCards"
          :key="item.label"
          shadow="never"
          class="overview-card overview-card--count"
        >
          <ElSkeleton animated :loading="loading">
            <template #template>
              <ElSkeletonItem variant="text" class="overview-card__label-skeleton" />
              <ElSkeletonItem variant="h1" class="overview-card__value-skeleton" />
              <ElSkeletonItem variant="text" class="overview-card__hint-skeleton" />
            </template>
            <template #default>
              <p class="overview-card__label console-description">{{ item.label }}</p>
              <strong class="overview-card__value">{{ item.value }}</strong>
              <p class="overview-card__hint console-description">{{ item.hint }}</p>
              <span
                class="overview-card__icon"
                :class="`overview-card__icon--${item.tone}`"
                aria-hidden="true"
              >
                <ArtSvgIcon :icon="item.icon" />
              </span>
            </template>
          </ElSkeleton>
        </ElCard>
      </section>

      <section class="project-overview__cards project-overview__cards--rates" aria-label="设备比例">
        <ElCard
          v-for="item in rateCards"
          :key="item.key"
          shadow="never"
          class="overview-card overview-card--rate"
        >
          <ElSkeleton animated :loading="loading">
            <template #template>
              <ElSkeletonItem variant="text" class="overview-card__label-skeleton" />
              <ElSkeletonItem variant="circle" class="overview-card__chart-skeleton" />
              <ElSkeletonItem variant="text" class="overview-card__hint-skeleton" />
            </template>
            <template #default>
              <p class="overview-card__label console-description">{{ item.label }}</p>
              <ArtRingChart
                class="overview-card__chart"
                :data="item.chartData"
                :center-text="item.rateText"
                :colors="item.colors"
                :show-tooltip="item.key === 'alarm'"
                :show-legend="item.key === 'alarm'"
                :is-empty="!item.available"
                height="176px"
              />
              <p class="overview-card__hint console-description">{{ item.hint }}</p>
            </template>
          </ElSkeleton>
        </ElCard>
      </section>

      <section
        class="project-overview__cards project-overview__cards--traffic"
        aria-label="消息流量概览"
      >
        <ElCard v-for="item in trafficCards" :key="item.label" shadow="never" class="overview-card">
          <ElSkeleton animated :loading="loading">
            <template #template>
              <ElSkeletonItem variant="text" class="overview-card__label-skeleton" />
              <ElSkeletonItem variant="h1" class="overview-card__value-skeleton" />
              <ElSkeletonItem variant="text" class="overview-card__hint-skeleton" />
            </template>
            <template #default>
              <p class="overview-card__label console-description">{{ item.label }}</p>
              <strong class="overview-card__value">{{ item.value }}</strong>
              <p class="overview-card__hint console-description">{{ item.hint }}</p>
            </template>
          </ElSkeleton>
        </ElCard>
      </section>

      <ElCard shadow="never" class="project-overview__meta">
        <ElSkeleton animated :loading="loading">
          <template #template>
            <div class="overview-meta__skeleton">
              <ElSkeletonItem v-for="item in 2" :key="item" variant="text" />
            </div>
          </template>
          <template #default>
            <div class="overview-meta__item">
              <span>统计窗口</span>
              <strong>{{ windowText }}</strong>
            </div>
            <div class="overview-meta__item">
              <span>生成时间</span>
              <strong>{{ generatedAtText }}</strong>
            </div>
          </template>
        </ElSkeleton>
      </ElCard>
    </template>
  </div>
</template>

<script setup lang="ts">
  import { fetchProjectOverview, type OverviewResponse } from '@/api/overview'
  import { useUserStore } from '@/store/modules/user'
  import { formatTime } from '@/utils/time'
  import { alarmDistribution } from '@/utils/alarm-distribution'
  import { HttpError } from '@/utils/http/error'

  defineOptions({ name: 'DashboardOverview' })

  const userStore = useUserStore()
  const projectId = computed(() => userStore.info.currentProjectId ?? '')
  const loading = ref(false)
  const overview = ref<OverviewResponse>()

  /** 只有服务端明确返回 0 才显示 0；缺失字段永远显示破折号，不把“不知道”伪造成零。 */
  const formatNumber = (value?: number) =>
    typeof value === 'number' ? new Intl.NumberFormat('zh-CN').format(value) : '—'

  /** 概要契约中的比率为 [0, 1]；展示层才转成百分数，避免把 0.5 误显示成 0.5%。 */
  const formatRate = (value?: number) =>
    typeof value === 'number' ? `${(value * 100).toFixed(1)}%` : '—'

  /** 流量保留二进制单位，原始字节数仍由后端事实源聚合。 */
  const formatBytes = (value?: number) => {
    if (typeof value !== 'number') return '—'
    const units = ['B', 'KiB', 'MiB', 'GiB', 'TiB']
    let size = value
    let unit = 0
    while (size >= 1024 && unit < units.length - 1) {
      size /= 1024
      unit += 1
    }
    return `${size.toLocaleString('zh-CN', { maximumFractionDigits: 1 })} ${units[unit]}`
  }

  /** 比率与剩余比例直接使用概要 API 字段，不在浏览器重新统计设备或告警事实。 */
  const rateChartData = (label: string, rate?: number) =>
    typeof rate === 'number'
      ? [
          { name: label, value: rate },
          { name: '其余', value: 1 - rate }
        ]
      : []

  const deviceCountCards = computed(() => [
    {
      label: '设备总数',
      icon: 'ri:box-3-line',
      tone: 'primary',
      value: formatNumber(overview.value?.devices?.total),
      hint: '当前项目下已登记的设备'
    },
    {
      label: '在线设备数',
      icon: 'ri:router-line',
      tone: 'success',
      value: formatNumber(overview.value?.devices?.online),
      hint: '当前在线设备'
    },
    {
      label: '活跃设备数',
      icon: 'ri:pulse-line',
      tone: 'warning',
      value: formatNumber(overview.value?.devices?.active24h),
      hint: '当前在线或 24 小时内最近在线'
    }
  ])

  const rateCards = computed(() => {
    const devices = overview.value?.devices
    const alarmRate = overview.value?.alarmRate
    const alarmValue = alarmRate?.value
    const distribution = alarmDistribution(overview.value)
    const alarmAvailable = distribution.available && typeof alarmValue === 'number'
    return [
      {
        key: 'online',
        label: '当前在线率',
        rateText: formatRate(devices?.onlineRate),
        chartData: rateChartData('在线', devices?.onlineRate),
        available: typeof devices?.onlineRate === 'number',
        colors: ['#67C23A', '#E9EDF2'],
        hint: '当前在线设备占比'
      },
      {
        key: 'active24h',
        label: '24 小时活跃率',
        rateText: formatRate(devices?.active24hRate),
        chartData: rateChartData('活跃', devices?.active24hRate),
        available: typeof devices?.active24hRate === 'number',
        colors: ['#409EFF', '#E9EDF2'],
        hint: '当前在线或 24 小时内最近在线设备占比'
      },
      {
        key: 'alarm',
        label: '告警设备分布',
        rateText: alarmAvailable ? formatRate(alarmValue) : '—',
        chartData: distribution.data,
        available: alarmAvailable,
        colors: distribution.colors,
        hint: alarmAvailable
          ? `${distribution.alarmDevices} 台告警设备，按最高活动告警级别归类`
          : '告警分布暂不可用'
      }
    ]
  })

  const trafficCards = computed(() => [
    {
      label: '24 小时消息量',
      value: formatNumber(overview.value?.messages24h?.count),
      hint: '窗口内消息日志总数'
    },
    {
      label: '24 小时流量',
      value: formatBytes(overview.value?.messages24h?.bytes),
      hint: '窗口内报文总字节数'
    }
  ])

  const windowText = computed(() => {
    const window = overview.value?.window
    return window?.from && window.to
      ? `${formatTime(window.from)} 至 ${formatTime(window.to)}`
      : '—'
  })
  const generatedAtText = computed(() => formatTime(overview.value?.generatedAt))

  /** 失败由 HTTP 层统一提示；保留旧快照，避免一次暂时网络错误清空已读到的事实结果。 */
  const refresh = async () => {
    if (!projectId.value) return
    loading.value = true
    try {
      overview.value = await fetchProjectOverview(projectId.value)
    } catch (error) {
      if (!(error instanceof HttpError)) console.error('加载项目概要失败:', error)
    } finally {
      loading.value = false
    }
  }

  onMounted(() => void refresh())
</script>

<style lang="scss" scoped>
  .project-overview {
    padding: 10px;

    &__header {
      display: flex;
      gap: 10px;
      align-items: flex-start;
      justify-content: space-between;
      margin-bottom: 10px;

      h3 {
        margin: 0 0 6px;
      }

      p {
        margin: 0;
        color: var(--el-text-color-secondary);
      }
    }

    &__cards {
      display: grid;
      grid-template-columns: repeat(3, minmax(0, 1fr));
      gap: 10px;

      &--rates {
        margin-top: 10px;
      }

      &--traffic {
        grid-template-columns: repeat(2, minmax(0, 1fr));
        margin-top: 10px;
      }
    }

    &__meta {
      margin-top: 10px;
    }
  }

  .overview-card {
    min-height: 140px;

    &--count {
      position: relative;
      min-height: 140px;
      :deep(.el-card__body) {
        display: flex;
        flex-direction: column;
        justify-content: center;
        min-height: 138px;
        padding: 20px 90px 20px 20px;
      }
      .overview-card__value {
        font-size: 26px;
        font-weight: 500;
      }
      .overview-card__hint {
        margin-top: 8px;
        font-size: 12px;
      }
    }
    &__icon {
      position: absolute;
      top: 0;
      right: 20px;
      bottom: 0;
      display: flex;
      align-items: center;
      justify-content: center;
      width: 50px;
      height: 50px;
      margin: auto;
      font-size: 24px;
      border-radius: 12px;
      &--primary {
        color: var(--el-color-primary);
        background: var(--el-color-primary-light-9);
      }
      &--success {
        color: var(--el-color-success);
        background: var(--el-color-success-light-9);
      }
      &--warning {
        color: var(--el-color-warning);
        background: var(--el-color-warning-light-9);
      }
    }
    &__label {
      margin: 0;
      font-size: 14px;
      color: var(--el-text-color-secondary);
    }

    &__value {
      display: block;
      margin-top: 10px;
      font-size: 28px;
      line-height: 1.1;
      color: var(--el-text-color-primary);
    }

    &__hint {
      margin: 12px 0 0;
      font-size: 13px;
      color: var(--el-text-color-secondary);
    }

    &__label-skeleton {
      width: 38%;
    }

    &__value-skeleton {
      width: 56%;
      height: 32px;
      margin-top: 10px;
    }

    &__hint-skeleton {
      width: 72%;
      margin-top: 10px;
    }

    &--rate {
      min-height: 280px;

      .overview-card__hint {
        margin-top: 8px;
        text-align: center;
      }
    }

    &__chart-skeleton {
      width: 140px;
      height: 140px;
      margin: 10px auto;
      border-radius: 50%;
    }

    &__chart {
      margin-top: 4px;
    }
  }

  .overview-meta {
    &__skeleton {
      display: grid;
      grid-template-columns: repeat(3, minmax(0, 1fr));
      gap: 10px;
    }

    &__item {
      display: flex;
      flex-direction: column;
      gap: 8px;

      span {
        font-size: 13px;
        color: var(--el-text-color-secondary);
      }
    }
  }

  @media screen and (width <= 1100px) {
    .project-overview__cards {
      grid-template-columns: repeat(2, minmax(0, 1fr));
    }
  }

  @media screen and (width <= 640px) {
    .project-overview {
      padding: 16px;

      &__header {
        flex-direction: column;
        align-items: stretch;
      }

      &__header .el-button {
        align-self: flex-start;
      }

      &__cards,
      &__cards--traffic,
      .overview-meta__skeleton {
        grid-template-columns: 1fr;
      }
    }
  }
</style>
