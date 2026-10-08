<!--
  项目设置首屏优先呈现用量与额度，再展示项目 Agent、知识资料及个人事实集合。

  套餐归属与限额按租户共享，项目只是事实拆分维度。普通协作者只能经此项目接口读取
  当前项目的贡献以及共享池余量，不能借助页面反推出同租户其他项目或账单聚合；套餐摘要
  字段对跨租户协作者整体缺省，页面显示说明而不是伪造零额度套餐。
-->
<template>
  <div class="console-page project-settings" v-loading="loading">
    <ConsoleWorkspaceHeader
      title="用量与项目设置"
      description="先查看当前项目用量与租户共享额度，再按需要配置项目 Agent 与知识资料。"
      :links="[
        { label: '项目列表', path: '/project/list' },
        { label: '成员与邀请', path: '/project/members', permission: 'member:read' },
        { label: '套餐目录', path: '/plan-catalog' }
      ]"
    />
    <ElEmpty v-if="!projectId && !loading" :description="$t('quota.noProject')" />

    <template v-else>
      <section aria-label="用量与额度">
        <h2 class="console-heading">用量与额度</h2>
        <ElAlert
          type="info"
          show-icon
          :closable="false"
          :title="$t('quota.sharedPoolNotice')"
          class="project-settings__notice"
        />

        <PlanSummaryPanel :summary="overview?.planSummary" />

        <ElCard shadow="never" class="project-settings__meta">
          <ElSkeleton animated :loading="loading">
            <template #template>
              <div class="quota-meta__skeleton">
                <ElSkeletonItem v-for="item in 5" :key="item" variant="text" />
              </div>
            </template>
            <template #default>
              <div class="quota-meta__content">
                <div class="quota-meta__item">
                  <span>{{ $t('quota.planCode') }}</span>
                  <strong>{{ overview?.policyCode || '—' }}</strong>
                </div>
                <div class="quota-meta__item">
                  <span>{{ $t('quota.window') }}</span>
                  <strong>{{ windowText }}</strong>
                </div>
                <div class="quota-meta__item">
                  <span>{{ $t('quota.policyVersion') }}</span>
                  <strong>{{ formatNumber(overview?.policyVersion) }}</strong>
                </div>
                <div class="quota-meta__item">
                  <span>{{ $t('quota.memberCount') }}</span>
                  <strong>{{ formatNumber(overview?.memberCount) }}</strong>
                </div>
                <div class="quota-meta__item">
                  <span>项目时区</span>
                  <strong>{{ projectTimezone || '—' }}</strong>
                </div>
              </div>
            </template>
          </ElSkeleton>
        </ElCard>

        <section class="project-settings__sections" aria-label="配额与日用量">
          <ElCard shadow="never">
            <template #header>
              <div class="quota-section__header console-page-header">
                <div>
                  <h4>{{ $t('quota.projectUsageTitle') }}</h4>
                  <p class="console-description">{{ $t('quota.projectUsageHint') }}</p>
                </div>
              </div>
            </template>
            <QuotaUsageTable :scope="overview?.project" />
          </ElCard>

          <ElCard shadow="never">
            <template #header>
              <div class="quota-section__header console-page-header">
                <div>
                  <h4>{{ $t('quota.tenantPoolTitle') }}</h4>
                  <p class="console-description">{{ $t('quota.tenantPoolHint') }}</p>
                </div>
              </div>
            </template>
            <QuotaUsageTable :scope="overview?.tenantSharedPool" />
          </ElCard>
        </section>
      </section>
      <section class="console-editor-section" aria-label="Agent 与知识配置">
        <h2 class="console-heading">Agent 与知识配置</h2>
        <p class="console-description"
          >按需配置项目模型接入、知识资料与个人事实集合。具体操作遵循当前账号权限。</p
        >
        <AgentModelPanel />
        <ProjectKnowledgePanel :project-id="projectId" />
        <PersonalFactCollectionPanel :project-id="projectId" />
      </section>
    </template>
  </div>
</template>

<script setup lang="ts">
  import ConsoleWorkspaceHeader from '@/components/business/ConsoleWorkspaceHeader.vue'
  import AgentModelPanel from './AgentModelPanel.vue'
  import ProjectKnowledgePanel from '@/components/agent/ProjectKnowledgePanel.vue'
  import PersonalFactCollectionPanel from '@/components/agent/PersonalFactCollectionPanel.vue'
  import QuotaUsageTable from './QuotaUsageTable.vue'
  import PlanSummaryPanel from './PlanSummaryPanel.vue'
  import { fetchProjectQuota, type ProjectQuotaOverviewResponse } from '@/api/quota'
  import { fetchProjects } from '@/api/project'
  import { useUserStore } from '@/store/modules/user'
  import { HttpError } from '@/utils/http/error'
  import dayjs from 'dayjs'
  import utc from 'dayjs/plugin/utc'

  dayjs.extend(utc)

  defineOptions({ name: 'ProjectSettings' })

  const userStore = useUserStore()
  const projectId = computed(() => userStore.info.currentProjectId ?? '')
  const loading = ref(false)
  const overview = ref<ProjectQuotaOverviewResponse>()
  const projectTimezone = ref('')

  /**
   * 服务端固定用 UTC `[00:00, 24:00)` 结算；这里也显式按 UTC 输出，不能让浏览器时区
   * 把边界显示成相邻自然日，从而误导使用者以为套餐按项目本地时区计量。
   */
  const formatUtcTime = (value?: string) =>
    value ? dayjs.utc(value).format('YYYY-MM-DD HH:mm:ss') : '—'

  const windowText = computed(() =>
    overview.value?.windowStart && overview.value.windowEnd
      ? `${formatUtcTime(overview.value.windowStart)} 至 ${formatUtcTime(overview.value.windowEnd)} UTC`
      : '—'
  )

  /** 缺失不是零；配额返回缺字段时用破折号保留“不确定”的语义。 */
  const formatNumber = (value?: number) =>
    typeof value === 'number' ? new Intl.NumberFormat('zh-CN').format(value) : '—'

  /** 失败由 HTTP 层统一提示；保留成功读取的旧快照，让临时网络问题不抹掉已知事实。 */
  const refresh = async () => {
    if (!projectId.value) return
    loading.value = true
    try {
      const [quota, projects] = await Promise.all([
        fetchProjectQuota(projectId.value),
        fetchProjects()
      ])
      overview.value = quota
      projectTimezone.value =
        projects.find((project) => project.id === projectId.value)?.timezone ?? ''
    } catch (error) {
      if (!(error instanceof HttpError)) console.error('加载项目配额与日用量失败:', error)
    } finally {
      loading.value = false
    }
  }

  onMounted(() => void refresh())
</script>

<style lang="scss" scoped>
  .project-settings {
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
        color: var(--art-text-gray-500);
      }
    }

    &__notice {
      margin-bottom: 10px;
    }

    &__meta {
      margin-bottom: 10px;
    }

    &__sections {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 10px;
    }
  }

  .quota-meta {
    &__skeleton,
    &__content {
      display: grid;
      grid-template-columns: repeat(4, minmax(0, 1fr));
      gap: 10px;
    }

    &__item {
      display: flex;
      flex-direction: column;
      gap: 8px;

      span {
        font-size: 13px;
        color: var(--art-text-gray-500);
      }
    }
  }

  .quota-section__header {
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

  @media screen and (width <= 1100px) {
    .project-settings__sections {
      grid-template-columns: 1fr;
    }
  }

  @media screen and (width <= 640px) {
    .project-settings {
      padding: 16px;

      &__header {
        flex-direction: column;
        align-items: stretch;
      }

      &__header .el-button {
        align-self: flex-start;
      }
    }

    .quota-meta__skeleton,
    .quota-meta__content {
      grid-template-columns: 1fr;
    }
  }
</style>
