<!--
  项目设置页的「我的套餐」面板（S14-2c）。

  摘要只在调用者属于项目归属租户时由服务端返回；缺省时明确说明原因，而不是渲染一份
  零额度套餐。面板把「订阅锁定的档位」与「运行时实际绑定的档位」并列，二者不一致时给出
  警告；参考价永远标注为“不是成交价”。
-->
<template>
  <ElCard shadow="never" class="plan-summary">
    <template #header>
      <div class="plan-summary__header">
        <div>
          <h4>{{ $t('quota.planSummaryTitle') }}</h4>
          <p>{{ $t('quota.planSummaryHint') }}</p>
        </div>
      </div>
    </template>

    <ElAlert
      v-if="!model"
      data-testid="plan-summary-unavailable"
      type="info"
      show-icon
      :closable="false"
      :title="$t('quota.planSummaryUnavailable')"
    />

    <template v-else>
      <div class="plan-summary__meta">
        <div class="plan-summary__item">
          <span>{{ $t('quota.planName') }}</span>
          <strong>{{ model.subscribedPlan.name }}（{{ model.subscribedPlan.code }}）</strong>
        </div>
        <div class="plan-summary__item">
          <span>{{ $t('quota.planRevision') }}</span>
          <strong
            >{{ model.subscribedPlan.revision }} · v{{ model.subscribedPlan.revisionNo }}</strong
          >
        </div>
        <div class="plan-summary__item">
          <span>{{ $t('quota.planStatus') }}</span>
          <ElTag :type="model.subscriptionStatusTag" disable-transitions>{{
            model.subscriptionStatusLabel
          }}</ElTag>
        </div>
        <div class="plan-summary__item">
          <span>{{ $t('quota.planPeriod') }}</span>
          <strong>{{ model.periodText }}</strong>
        </div>
        <div class="plan-summary__item">
          <span>{{ $t('quota.planBillingPeriod') }}</span>
          <strong>{{ model.billingPeriodLabel }}</strong>
        </div>
        <div class="plan-summary__item">
          <span>{{ $t('quota.planRenewal') }}</span>
          <strong>{{ model.renewalModeLabel }}</strong>
        </div>
        <div class="plan-summary__item">
          <span>{{ $t('quota.planReferencePrice') }}</span>
          <strong>{{ model.referencePrice ?? '—' }}</strong>
          <small>{{ $t('quota.planReferencePriceNotice') }}</small>
        </div>
        <div class="plan-summary__item">
          <span>{{ $t('quota.planEffectivePlan') }}</span>
          <strong>{{ effectivePlanText }}</strong>
        </div>
      </div>

      <ElAlert
        v-if="!model.effectiveMatchesSubscribed"
        type="warning"
        show-icon
        :closable="false"
        :title="$t('quota.planEffectiveMismatch')"
        class="plan-summary__mismatch"
      />

      <section class="plan-summary__sections">
        <div>
          <h5>{{ $t('quota.planLimitsTitle') }}</h5>
          <p>{{ $t('quota.planLimitsHint') }}</p>
          <ElTable data-testid="plan-frozen-limits" :data="model.limits" size="small">
            <ElTableColumn :label="$t('quota.planLimitDimension')" min-width="150">
              <template #default="{ row }">{{ row.label }}</template>
            </ElTableColumn>
            <ElTableColumn :label="$t('quota.planLimitValue')" min-width="140" align="right">
              <template #default="{ row }">{{ formatNumber(row.value) }} {{ row.unit }}</template>
            </ElTableColumn>
            <ElTableColumn :label="$t('quota.planLimitWindow')" min-width="110">
              <template #default="{ row }">{{ row.windowLabel }}</template>
            </ElTableColumn>
            <template #empty><ElEmpty description="—" :image-size="60" /></template>
          </ElTable>
        </div>
        <div>
          <h5>{{ $t('quota.planEffectiveLimitsTitle') }}</h5>
          <p>{{ $t('quota.planEffectiveLimitsHint') }}</p>
          <ElTable
            v-if="model.effectiveLimitsAvailable"
            data-testid="plan-effective-limits"
            :data="model.effectiveLimits"
            size="small"
          >
            <ElTableColumn :label="$t('quota.planLimitDimension')" min-width="150">
              <template #default="{ row }">{{ row.label }}</template>
            </ElTableColumn>
            <ElTableColumn :label="$t('quota.planLimitValue')" min-width="140" align="right">
              <template #default="{ row }">{{ formatNumber(row.value) }} {{ row.unit }}</template>
            </ElTableColumn>
            <ElTableColumn :label="$t('quota.planLimitWindow')" min-width="110">
              <template #default="{ row }">{{ row.windowLabel }}</template>
            </ElTableColumn>
            <template #empty><ElEmpty description="—" :image-size="60" /></template>
          </ElTable>
          <ElAlert
            v-else
            data-testid="plan-effective-unavailable"
            type="info"
            show-icon
            :closable="false"
            :title="$t('quota.planEffectiveLimitsUnavailable')"
          />
        </div>
        <div>
          <h5>{{ $t('quota.planAdditionsTitle') }}</h5>
          <p>{{ $t('quota.planAdditionsHint') }}</p>
          <ElTable data-testid="plan-additions" :data="model.additions" size="small">
            <ElTableColumn :label="$t('quota.planAdditionSource')" min-width="100">
              <template #default="{ row }">{{ row.sourceLabel }}</template>
            </ElTableColumn>
            <ElTableColumn :label="$t('quota.planAdditionDimension')" min-width="130">
              <template #default="{ row }">{{ row.dimensionLabel }}</template>
            </ElTableColumn>
            <ElTableColumn :label="$t('quota.planAdditionAmount')" min-width="120" align="right">
              <template #default="{ row }">{{ formatNumber(row.amount) }} {{ row.unit }}</template>
            </ElTableColumn>
            <ElTableColumn :label="$t('quota.planAdditionPeriod')" min-width="220">
              <template #default="{ row }">{{ row.periodText }}</template>
            </ElTableColumn>
            <ElTableColumn :label="$t('quota.planAdditionStatus')" min-width="130">
              <template #default="{ row }">
                <ElTag :type="row.statusTag" disable-transitions>{{ row.statusLabel }}</ElTag>
                <span v-if="row.effectiveNow"> · {{ $t('quota.planAdditionEffectiveNow') }}</span>
                <span v-else> · {{ $t('quota.planAdditionNotEffectiveNow') }}</span>
              </template>
            </ElTableColumn>
            <ElTableColumn :label="$t('quota.planAdditionReason')" min-width="140">
              <template #default="{ row }">{{ row.reason ?? '—' }}</template>
            </ElTableColumn>
            <template #empty><ElEmpty description="—" :image-size="60" /></template>
          </ElTable>
        </div>
        <div>
          <h5>{{ $t('quota.planCapabilitiesTitle') }}</h5>
          <p>{{ $t('quota.planCapabilitiesHint') }}</p>
          <div class="plan-summary__capabilities">
            <ElTag
              v-for="capability in model.enabledCapabilities"
              :key="capability.code"
              type="success"
              disable-transitions
              >{{ capability.label }} · {{ capability.statusLabel }}</ElTag
            >
            <ElTag
              v-for="capability in model.disabledCapabilities"
              :key="capability.code"
              type="info"
              disable-transitions
              >{{ capability.label }} · {{ capability.statusLabel }}</ElTag
            >
            <ElEmpty
              v-if="model.enabledCapabilities.length + model.disabledCapabilities.length === 0"
              :image-size="60"
              description="—"
            />
          </div>
        </div>
      </section>
    </template>
  </ElCard>
</template>

<script setup lang="ts">
  import type { ProjectPlanSummaryResponse } from '@/api/quota'
  import {
    buildProjectPlanSummaryModel,
    type ProjectPlanSummaryModel
  } from '@/features/plan/plan-summary-model'

  const props = defineProps<{
    summary?: ProjectPlanSummaryResponse | null
  }>()

  /** 摘要缺省时模型为 null，面板显示“不可用”说明而不是伪造零额度套餐。 */
  const model = computed<ProjectPlanSummaryModel | null>(() =>
    buildProjectPlanSummaryModel(props.summary)
  )

  /** 运行时绑定档位的展示文案；绑定不是套餐模板时显示破折号而不是猜测档位。 */
  const effectivePlanText = computed(() => {
    const plan = model.value?.effectivePlan
    return plan ? `${plan.name}（${plan.code}）· ${plan.revision}` : '—'
  })

  /** 缺失不是零；用破折号保留“不确定”的语义。 */
  const formatNumber = (value?: number | null) =>
    typeof value === 'number' ? new Intl.NumberFormat('zh-CN').format(value) : '—'
</script>

<style lang="scss" scoped>
  .plan-summary {
    margin-bottom: 16px;

    &__header {
      h4 {
        margin: 0 0 6px;
        font-size: 16px;
      }

      p {
        margin: 0;
        font-size: 13px;
        color: var(--art-text-gray-500);
      }
    }

    &__meta {
      display: grid;
      grid-template-columns: repeat(4, minmax(0, 1fr));
      gap: 20px;
    }

    &__item {
      display: flex;
      flex-direction: column;
      gap: 8px;

      span {
        font-size: 13px;
        color: var(--art-text-gray-500);
      }

      small {
        font-size: 12px;
        color: var(--art-text-gray-500);
      }
    }

    &__mismatch {
      margin-top: 16px;
    }

    &__sections {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 16px;
      margin-top: 16px;

      h5 {
        margin: 0 0 6px;
        font-size: 14px;
      }

      p {
        margin: 0 0 8px;
        font-size: 13px;
        color: var(--art-text-gray-500);
      }
    }

    &__capabilities {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
    }
  }

  @media screen and (width <= 1100px) {
    .plan-summary__meta,
    .plan-summary__sections {
      grid-template-columns: 1fr;
    }
  }
</style>
