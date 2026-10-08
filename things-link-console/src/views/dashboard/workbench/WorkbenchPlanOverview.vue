<script setup lang="ts">
  import { computed } from 'vue'
  import type { ProjectQuotaOverviewResponse, QuotaMetricUsageResponse } from '@/api/quota'
  import { subscriptionStatusLabel } from '@/features/plan/plan-summary-model'

  const props = defineProps<{
    overview?: ProjectQuotaOverviewResponse
    loading: boolean
    failed: boolean
    showDetails: boolean
  }>()
  defineEmits<{ retry: []; resources: []; details: [] }>()

  const summary = computed(() => props.overview?.planSummary)
  const number = (value?: number) =>
    typeof value === 'number' ? value.toLocaleString('zh-CN') : '—'
  // 用共享池已用值对照共享上限，不能把当前项目贡献当成共享池总用量。
  const rows = computed(() => {
    const pool = props.overview?.tenantSharedPool
    const metric = (code: string) => pool?.dailyMetrics?.find((item) => item.metric === code)
    return [
      { label: '设备数', usage: pool?.deviceCount },
      { label: '上行消息 / 日', usage: metric('UPLINK_MESSAGE') },
      { label: '下行消息 / 日', usage: metric('DOWNLINK_MESSAGE') },
      { label: '通知投递 / 日', usage: metric('NOTIFICATION_DELIVERY') }
    ]
  })
  const usageText = (usage?: QuotaMetricUsageResponse) =>
    usage ? `${number(usage.used)} / ${usage.limit == null ? '不限' : number(usage.limit)}` : '—'
  // 缺失、未设上限和零额度均不伪造百分比；超额保留原始数值，进度条最多填满。
  const percentage = (usage?: QuotaMetricUsageResponse) =>
    typeof usage?.used === 'number' && typeof usage.limit === 'number' && usage.limit > 0
      ? Math.min(100, Math.max(0, (usage.used / usage.limit) * 100))
      : undefined
  const progressStatus = (usage?: QuotaMetricUsageResponse) =>
    usage?.status === 'HARD_LIMIT' || usage?.status === 'DEGRADED'
      ? 'exception'
      : usage?.status === 'SOFT_LIMIT'
        ? 'warning'
        : undefined
</script>

<template>
  <section class="workbench-plan" aria-labelledby="plan-overview-heading">
    <div class="workbench-plan__header">
      <h2 id="plan-overview-heading">套餐概况</h2>
      <ElButton text aria-label="查看用量与项目资源" @click="$emit('resources')">
        <ArtSvgIcon icon="ri:arrow-right-line" />
      </ElButton>
    </div>
    <ElSkeleton :loading="loading" animated :rows="5">
      <ElAlert v-if="failed" type="warning" title="套餐概况暂不可用" :closable="false">
        <ElButton text @click="$emit('retry')">重新读取</ElButton>
      </ElAlert>
      <template v-else>
        <dl class="workbench-plan__usage">
          <div class="workbench-plan__row">
            <dt>订阅套餐</dt>
            <dd>{{ summary?.subscribedPlan?.name || '暂不可用' }}</dd>
          </div>
          <div v-if="summary?.subscriptionStatus" class="workbench-plan__row">
            <dt>订阅状态</dt>
            <dd>{{ subscriptionStatusLabel(summary.subscriptionStatus) }}</dd>
          </div>
          <div v-for="row in rows" :key="row.label" class="workbench-plan__row">
            <dt>{{ row.label }}</dt>
            <dd>
              <span>{{ usageText(row.usage) }}</span>
              <ElProgress
                v-if="percentage(row.usage) !== undefined"
                :percentage="percentage(row.usage)"
                :show-text="false"
                :stroke-width="4"
                :status="progressStatus(row.usage)"
                :aria-label="`${row.label}：${usageText(row.usage)}`"
              />
            </dd>
          </div>
          <div class="workbench-plan__row">
            <dt>项目成员</dt>
            <dd>{{ number(overview?.memberCount) }} 人</dd>
          </div>
        </dl>
        <p class="workbench-plan__caption">设备及日用量为租户共享池，成员数为当前项目人数。</p>
        <p v-if="summary?.effectiveMatchesSubscribed === false" class="workbench-plan__warning"
          >当前生效额度与订阅套餐不同，详见项目资源。</p
        >
      </template>
    </ElSkeleton>
    <div v-if="showDetails" class="workbench-plan__details">
      <h3>接入更多设备？</h3>
      <p>查看各套餐的资源额度与功能权益。</p>
      <ElButton type="primary" @click="$emit('details')">了解详情</ElButton>
    </div>
  </section>
</template>

<style scoped lang="scss">
  .workbench-plan {
    padding: 20px 24px;
    background: color-mix(in srgb, #329fd7 10%, var(--el-bg-color));
    border: 1px solid var(--el-color-primary-light-8);
    border-radius: 10px;

    &__header {
      display: flex;
      gap: 12px;
      align-items: center;
      justify-content: space-between;
      margin-bottom: 12px;

      h2 {
        margin: 0;
        font-size: 17px;
        font-weight: 600;
      }

      .el-button {
        width: 28px;
        padding: 0;
      }
    }

    &__usage {
      display: flex;
      flex-direction: column;
      gap: 12px;
      margin: 0;
      font-size: 14px;
    }

    &__row {
      display: flex;
      gap: 16px;
      align-items: center;
      justify-content: space-between;

      dt {
        color: var(--el-text-color-regular);
      }

      dd {
        min-width: 96px;
        margin: 0;
        font-variant-numeric: tabular-nums;
        text-align: right;
        overflow-wrap: anywhere;
      }

      .el-progress {
        width: 104px;
        margin: 5px 0 0 auto;
      }
    }

    &__caption,
    &__warning {
      margin: 16px 0 0;
      font-size: 13px;
      line-height: 1.6;
      color: var(--el-text-color-secondary);
    }

    &__warning {
      color: var(--el-color-warning-dark-2);
    }

    &__details {
      margin-top: 24px;
      text-align: center;

      h3 {
        margin: 0;
        font-size: 16px;
        font-weight: 600;
      }

      p {
        margin: 10px 0 16px;
        font-size: 13px;
        color: var(--el-text-color-regular);
      }

      .el-button {
        width: 100%;
      }
    }
  }
</style>
