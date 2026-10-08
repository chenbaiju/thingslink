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
    <ConsoleWorkspaceHeader
      project-style
      title="套餐与权益"
      description="查看套餐能力、资源额度和销售状态；项目实际可用额度以项目用量页面为准。"
      :links="[{ label: '用量与项目设置', path: '/project/settings', permission: 'quota:read' }]"
    />
    <ElAlert
      class="plan-catalog__notice"
      type="info"
      show-icon
      :closable="false"
      :title="$t('planCatalog.frozenNotice')"
    />
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

      <div
        v-else
        v-loading="loading"
        class="plan-catalog__comparison"
        data-testid="plan-catalog-tiers"
      >
        <table class="plan-catalog__table" aria-label="套餐额度与功能对比">
          <thead>
            <tr>
              <th scope="col" class="plan-catalog__label">套餐对比</th>
              <th
                v-for="tier in tiers"
                :key="tier.code"
                scope="col"
                :data-testid="`plan-catalog-tier-${tier.code}`"
                :title="`${tier.revision} · v${tier.revisionNo} · ${tier.code}`"
              >
                <strong class="plan-catalog__name">{{ tier.name }}</strong>
                <ElTag :type="tier.saleStatusTag" size="small" disable-transitions>{{
                  tier.saleStatusLabel
                }}</ElTag>
              </th>
            </tr>
            <tr>
              <th scope="row" class="plan-catalog__label">价格与周期</th>
              <td v-for="tier in tiers" :key="tier.code" data-testid="plan-catalog-price">
                <strong class="plan-catalog__price">{{ tier.displayPrice }}</strong>
                <span class="plan-catalog__period"> / {{ tier.billingPeriodLabel }}</span>
                <small v-if="tier.priceIsReference">{{ $t('planCatalog.priceIsReference') }}</small>
              </td>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="row in comparisonRows"
              :key="row.key"
              :class="{ 'plan-catalog__section': row.section }"
            >
              <th v-if="row.section" :colspan="tiers.length + 1" scope="rowgroup">{{
                row.label
              }}</th>
              <template v-else>
                <th scope="row" class="plan-catalog__label">{{ row.label }}</th>
                <td
                  v-for="(cell, index) in row.cells"
                  :key="tiers[index].code"
                  :title="cell.detail"
                  :class="`plan-catalog__cell--${cell.kind}`"
                  :data-testid="`${row.key}-${tiers[index].code}`"
                >
                  <span
                    v-if="cell.kind === 'included'"
                    class="plan-catalog__check"
                    role="img"
                    aria-label="目录包含"
                    >✓</span
                  >
                  <span v-else :aria-label="cell.kind === 'excluded' ? '目录未包含' : undefined">{{
                    cell.text
                  }}</span>
                </td>
              </template>
            </tr>
          </tbody>
        </table>
        <p class="plan-catalog__legend"
          >✓ 套餐目录包含；— 套餐目录未包含；未提供：接口未提供数据。{{
            $t('planCatalog.capabilityHint')
          }}</p
        >
      </div>
    </ElCard>
  </div>
</template>

<script setup lang="ts">
  import ConsoleWorkspaceHeader from '@/components/business/ConsoleWorkspaceHeader.vue'
  import { fetchPlanCatalog } from '@/api/plan'
  import { buildPlanCatalogModel } from '@/features/plan/catalog-model'
  import { buildPlanComparisonRows } from '@/features/plan/comparison-model'

  /** 目录为只读平台事实：页面只负责取数与渲染，不提供任何写入口。 */
  const loading = ref(false)
  /** 读取失败时给出明确提示，而不是渲染成空目录（空目录与不可用是两件事）。 */
  const error = ref(false)
  /** 四档展示模型。 */
  const tiers = ref<ReturnType<typeof buildPlanCatalogModel>['tiers']>([])

  const comparisonRows = computed(() => buildPlanComparisonRows(tiers.value))

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
  .plan-catalog__notice {
    margin-bottom: 10px;
  }
  .plan-catalog__comparison {
    max-height: max(360px, calc(var(--art-full-height) - 190px));
    overflow: auto;
  }
  .plan-catalog__table {
    width: 100%;
    min-width: 900px;
    table-layout: fixed;
    border-spacing: 0;
    border-collapse: separate;
    border-top: 1px solid var(--console-line);
    border-left: 1px solid var(--console-line);
    th,
    td {
      padding: 10px 16px;
      font-size: 13px;
      font-weight: 400;
      line-height: 20px;
      text-align: center;
      border-right: 1px solid var(--console-line);
      border-bottom: 1px solid var(--console-line);
    }
    thead {
      position: sticky;
      top: 0;
      z-index: 2;
    }
    thead th,
    thead td {
      background: var(--el-fill-color-light);
    }
    thead th {
      padding-block: 18px;
    }
    tbody tr:not(.plan-catalog__section):hover > * {
      background: var(--el-fill-color-extra-light);
    }
    .plan-catalog__label {
      position: sticky;
      left: 0;
      z-index: 1;
      width: 220px;
      text-align: left;
      background: var(--default-box-color);
    }
  }
  .plan-catalog__name {
    display: block;
    margin-bottom: 8px;
    font-size: 18px;
    font-weight: 500;
  }
  .plan-catalog__price {
    font-size: 22px;
    font-weight: 500;
  }
  .plan-catalog__period {
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }
  .plan-catalog__table small {
    display: block;
    margin-top: 4px;
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }
  .plan-catalog__section th {
    font-weight: 500;
    text-align: left;
    background: var(--el-fill-color-light);
  }
  .plan-catalog__check {
    font-size: 22px;
    font-weight: 500;
    color: var(--el-color-primary);
  }
  .plan-catalog__cell--excluded,
  .plan-catalog__cell--missing,
  .plan-catalog__cell--unknown {
    color: var(--el-text-color-secondary);
  }
  .plan-catalog__legend {
    margin: 14px 0 0;
    font-size: 12px;
    line-height: 20px;
    color: var(--el-text-color-secondary);
  }
</style>
