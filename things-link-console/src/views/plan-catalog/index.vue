<!--
  套餐与权益目录页（S14-6e，关闭 D-167 遗留入口）。

  这是**平台全局只读页**：目录没有租户列、不走 RLS，展示的是四个档位的名称、冻结额度维度、能力权益
  与销售状态。付费档当前未开卖，因此页面把「参考价」明确标注为**不是成交价**，不显示任何成交价或订单。

  权限口径与「系统状态」页一致（见 MenuCatalog）：平台级事实、无需项目角色、登录即可读；
  服务端 `GET /api/v1/plans` 仍要求有效令牌，页面可见性不替代接口认证。

  展示模型复用 S14-1c 已交付的 `features/plan/catalog-model`：销售价与参考价分离、DISABLED 权益
  不携带数值额度、未知编码原样透传。
-->
<template>
  <div class="console-page plan-catalog console-page--single-panel">
    <ElCard class="console-page__main-panel" shadow="never">
      <ElAlert
        v-if="error"
        data-testid="plan-catalog-error"
        type="error"
        show-icon
        :closable="false"
        :title="$t('planCatalog.loadFailed')"
      />

      <ElEmpty
        v-else-if="!loading && tiers.length === 0"
        data-testid="plan-catalog-empty"
        :description="$t('planCatalog.empty')"
      />

      <div v-else class="plan-catalog__tiers" data-testid="plan-catalog-tiers">
        <ElCard
          v-for="tier in tiers"
          :key="tier.code"
          shadow="never"
          class="plan-catalog__tier"
          :data-testid="`plan-catalog-tier-${tier.code}`"
        >
          <template #header>
            <div class="plan-catalog__tier-header">
              <strong>{{ tier.name }}（{{ tier.code }}）</strong>
              <ElTag :type="tier.saleStatusTag" disable-transitions>{{
                tier.saleStatusLabel
              }}</ElTag>
            </div>
          </template>

          <div class="plan-catalog__meta">
            <span>{{ tier.revision }} · v{{ tier.revisionNo }}</span>
            <span>{{ $t('planCatalog.billingPeriod') }}：{{ tier.billingPeriodLabel }}</span>
            <span data-testid="plan-catalog-price">
              {{ $t('planCatalog.price') }}：{{ tier.displayPrice }}
              <small v-if="tier.priceIsReference">{{ $t('planCatalog.priceIsReference') }}</small>
            </span>
          </div>

          <h5>{{ $t('planCatalog.quotaTitle') }}</h5>
          <ElTable
            :data="[...tier.quotas]"
            size="small"
            :data-testid="`plan-catalog-quota-${tier.code}`"
          >
            <ElTableColumn :label="$t('planCatalog.quotaTitle')" min-width="150">
              <template #default="{ row }">{{ row.label }}</template>
            </ElTableColumn>
            <ElTableColumn label="数值" min-width="130" align="right">
              <template #default="{ row }">{{ formatNumber(row.value) }} {{ row.unit }}</template>
            </ElTableColumn>
            <ElTableColumn label="计量窗口" min-width="110">
              <template #default="{ row }">{{ windowLabel(row.window) }}</template>
            </ElTableColumn>
            <template #empty><ElEmpty description="—" :image-size="50" /></template>
          </ElTable>

          <h5>{{ $t('planCatalog.capabilityTitle') }}</h5>
          <p class="console-description">{{ $t('planCatalog.capabilityHint') }}</p>
          <div class="plan-catalog__capabilities">
            <ElTag
              v-for="capability in tier.enabledCapabilities"
              :key="capability.code"
              type="success"
              disable-transitions
              >{{ capability.label }} · {{ capability.statusLabel }}</ElTag
            >
            <ElTag
              v-for="capability in tier.disabledCapabilities"
              :key="capability.code"
              type="info"
              disable-transitions
              >{{ capability.label }} · {{ capability.statusLabel }}</ElTag
            >
          </div>
        </ElCard>
      </div>

      <p class="plan-catalog__notice console-description">{{ $t('planCatalog.frozenNotice') }}</p>
    </ElCard>
  </div>
</template>

<script setup lang="ts">
  import { fetchPlanCatalog } from '@/api/plan'
  import { buildPlanCatalogModel, windowLabel } from '@/features/plan/catalog-model'

  /** 目录为只读平台事实：页面只负责取数与渲染，不提供任何写入口。 */
  const loading = ref(false)
  /** 读取失败时给出明确提示，而不是渲染成空目录（空目录与不可用是两件事）。 */
  const error = ref(false)
  /** 四档展示模型。 */
  const tiers = ref<ReturnType<typeof buildPlanCatalogModel>['tiers']>([])

  /** 千分位展示；缺失不是零。 */
  const formatNumber = (value?: number | null) =>
    typeof value === 'number' ? new Intl.NumberFormat('zh-CN').format(value) : '—'

  /** 读取目录并转换为展示模型。 */
  const load = async () => {
    loading.value = true
    error.value = false
    try {
      const plans = await fetchPlanCatalog()
      tiers.value = buildPlanCatalogModel(plans ?? []).tiers
    } catch {
      error.value = true
      tiers.value = []
    } finally {
      loading.value = false
    }
  }

  onMounted(load)
</script>

<style lang="scss" scoped>
  .plan-catalog__header {
    display: flex;
    gap: 10px;
    align-items: flex-start;
    justify-content: space-between;
  }

  .plan-catalog__tiers {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
    gap: 10px;
  }

  .plan-catalog__tier-header {
    display: flex;
    gap: 8px;
    align-items: center;
    justify-content: space-between;
  }

  .plan-catalog__meta {
    display: flex;
    flex-wrap: wrap;
    gap: 10px;
    margin-bottom: 8px;
    font-size: 13px;

    small {
      margin-left: 4px;
      color: var(--el-text-color-secondary);
    }
  }

  .plan-catalog__capabilities {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
  }

  .plan-catalog__notice {
    margin-top: 10px;
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }
</style>
