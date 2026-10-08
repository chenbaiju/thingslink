<!--
  系统状态页只展示后端实时健康探针的脱敏结果。

  页面不直接请求数据库、Redis 或中间件，也不把 HTTP 200 等同于全部依赖健康；总体状态
  与每项状态都由同一后端快照给出。刷新失败时保留上一次已知结果，避免瞬时网络错误清空事实。
-->
<template>
  <div class="console-page system-status console-page--single-panel" v-loading="loading">
    <ConsoleWorkspaceHeader
      project-style
      title="系统状态"
      description="查看平台组件当前状态与观测时间，用于定位服务可用性问题。"
      :links="[{ label: '项目列表', path: '/project/list' }]"
    />
    <ElAlert
      type="info"
      show-icon
      :closable="false"
      :title="$t('systemStatus.notice')"
      class="system-status__notice"
    />

    <ElCard shadow="never" class="system-status__summary">
      <ElSkeleton :loading="loading && !snapshot" animated>
        <template #template>
          <ElSkeletonItem variant="text" class="system-status__summary-skeleton" />
        </template>
        <template #default>
          <div class="system-status__summary-content">
            <div>
              <span>{{ $t('systemStatus.overall') }}</span>
              <ElTag :type="tagType(snapshot?.status)" effect="light" size="large">
                {{ statusText(snapshot?.status) }}
              </ElTag>
            </div>
            <div>
              <span>{{ $t('systemStatus.observedAt') }}</span>
              <strong>{{ formatTime(snapshot?.observedAt) }}</strong>
            </div>
          </div>
        </template>
      </ElSkeleton>
    </ElCard>

    <header class="system-status__section-header">
      <h2 class="system-status__section-title">{{ $t('systemStatus.dependencies') }}</h2>
      <p class="console-description">查看数据库、存储和通知等运行依赖的健康状态，定位异常组件。</p>
    </header>
    <ElEmpty
      v-if="!loading && !snapshot?.dependencies?.length"
      :description="$t('systemStatus.empty')"
    />
    <section v-else class="system-status__grid" aria-label="运行依赖健康状态">
      <ElCard
        v-for="dependency in snapshot?.dependencies ?? []"
        :key="dependency.code"
        shadow="never"
        class="dependency-card"
      >
        <div class="dependency-card__content">
          <div>
            <strong>{{ dependency.name }}</strong>
            <p class="console-description">{{ dependency.code }}</p>
          </div>
          <ElTag :type="tagType(dependency.status)" effect="light">
            {{ statusText(dependency.status) }}
          </ElTag>
        </div>
      </ElCard>
    </section>
  </div>
</template>

<script setup lang="ts">
  import ConsoleWorkspaceHeader from '@/components/business/ConsoleWorkspaceHeader.vue'
  import { formatTime } from '@/utils/time'
  import { useI18n } from 'vue-i18n'
  import {
    fetchSystemStatus,
    type SystemStatusResponse,
    type DependencyStatusResponse
  } from '@/api/system-status'
  import { HttpError } from '@/utils/http/error'

  defineOptions({ name: 'SystemStatus' })

  type HealthStatus =
    | SystemStatusResponse['status']
    | DependencyStatusResponse['status']
    | undefined

  const { t } = useI18n()
  const loading = ref(false)
  const snapshot = ref<SystemStatusResponse>()

  /** 状态颜色与服务端枚举逐项对应；未知值保持中性色，不能把缺失误画成绿色。 */
  const tagType = (status: HealthStatus) => {
    if (status === 'UP') return 'success'
    if (status === 'DOWN') return 'danger'
    if (status === 'DEGRADED' || status === 'OUT_OF_SERVICE') return 'warning'
    return 'info'
  }

  /** 契约外的状态按 UNKNOWN 显示，防止后端扩展状态时页面直接露出英文内部值。 */
  const statusText = (status: HealthStatus) => t(`systemStatus.status.${status || 'UNKNOWN'}`)

  /** 失败由统一 HTTP 层提示；保留旧快照让一次刷新失败不抹掉最近已知事实。 */
  const refresh = async () => {
    loading.value = true
    try {
      snapshot.value = await fetchSystemStatus()
    } catch (error) {
      if (!(error instanceof HttpError)) console.error('加载系统状态失败:', error)
    } finally {
      loading.value = false
    }
  }

  onMounted(() => void refresh())
</script>

<style lang="scss" scoped>
  .system-status {
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

    &__notice,
    &__summary {
      margin-bottom: 10px;
    }

    &__summary-skeleton {
      width: 50%;
    }

    &__summary-content {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 10px;

      > div {
        display: flex;
        flex-direction: column;
        gap: 8px;
      }

      span {
        color: var(--art-text-gray-500);
      }
    }

    &__section-header {
      padding-bottom: 18px;
      margin: 10px 0 14px;
      border-bottom: 1px solid var(--console-line);

      .console-description {
        margin: 0;
        font-size: 12px;
        line-height: 20px;
      }
    }

    &__section-title {
      margin: 0 0 8px;
      font-size: 28px;
      font-weight: 400;
      line-height: 40px;
      color: var(--el-text-color-primary);
    }

    &__grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
      gap: 10px;
    }
  }

  .dependency-card {
    &__content {
      display: flex;
      gap: 10px;
      align-items: center;
      justify-content: space-between;

      p {
        margin: 6px 0 0;
        font-family: monospace;
        color: var(--art-text-gray-500);
      }
    }
  }

  @media (width <= 640px) {
    .system-status__summary-content {
      grid-template-columns: 1fr;
    }
  }
</style>
