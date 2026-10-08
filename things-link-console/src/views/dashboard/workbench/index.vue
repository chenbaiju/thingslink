<template>
  <main class="console-page developer-workbench">
    <div class="workbench-columns">
      <div class="workbench-content">
        <header class="workbench-hero">
          <div>
            <p class="workbench-eyebrow">开发者工作台</p>
            <h1>{{ projectName || (projectId ? '当前项目' : '开始你的设备开发') }}</h1>
            <p class="console-description">从物模型到设备接入，让数据进入你的应用。</p>
            <span v-if="projectId" class="workbench-role">{{ roleLabel }} · 项目工作区</span>
          </div>
          <ElButton
            v-if="allowed('/device/types')"
            type="primary"
            size="large"
            @click="go('/device/types')"
            >开发设备 <ArtSvgIcon icon="ri:arrow-right-line"
          /></ElButton>
          <ElButton v-else type="primary" @click="go('/project/list')">选择项目</ElButton>
        </header>
        <template v-if="projectId">
          <section class="workbench-section" aria-labelledby="development-heading">
            <div class="workbench-section-heading"
              ><h2 id="development-heading">继续开发</h2><span>按需要进入任一步骤</span></div
            >
            <div class="development-steps">
              <button
                v-for="(step, index) in steps"
                :key="step.path"
                class="development-step"
                :disabled="!allowed(step.path)"
                @click="go(step.path)"
              >
                <span class="development-step__number">0{{ index + 1 }}</span
                ><ArtSvgIcon :icon="step.icon" class="development-step__icon" />
                <strong>{{ step.title }}</strong
                ><span>{{ allowed(step.path) ? step.description : '当前角色无此入口' }}</span>
                <ArtSvgIcon icon="ri:arrow-right-up-line" class="development-step__arrow" />
              </button>
            </div>
          </section>
          <div class="workbench-main">
            <section class="workbench-panel" aria-labelledby="recent-heading">
              <div class="workbench-section-heading"
                ><h2 id="recent-heading">本机最近访问</h2
                ><ElButton v-if="recent.length" text @click="clearRecent">清空</ElButton></div
              >
              <p v-if="!recent.length" class="console-description"
                >打开设备类型、设备或应用后，可在这里继续。记录仅保留最近 7 天。</p
              >
              <ul v-else class="workbench-recent"
                ><li v-for="item in recent" :key="`${item.kind}:${item.id}`"
                  ><button @click="openRecent(item)"
                    ><span>{{ kindLabel[item.kind] }}</span
                    ><strong>{{ item.label }}</strong
                    ><ArtSvgIcon icon="ri:arrow-right-line" /></button></li
              ></ul>
              <div v-if="!recent.length" class="workbench-shortcuts"
                ><ElButton
                  v-for="step in steps.filter((item) => allowed(item.path)).slice(0, 3)"
                  :key="step.path"
                  @click="go(step.path)"
                  >{{ step.title }}</ElButton
                ></div
              >
            </section>
            <section
              v-if="allowed('/dashboard/overview')"
              class="workbench-panel"
              aria-labelledby="snapshot-heading"
            >
              <div class="workbench-section-heading"
                ><h2 id="snapshot-heading">项目运行摘要</h2
                ><ElButton text type="primary" @click="go('/dashboard/overview')"
                  >项目概况 <ArtSvgIcon icon="ri:arrow-right-line" /></ElButton
              ></div>
              <ElAlert
                v-if="snapshotError"
                title="运行摘要暂不可用"
                type="warning"
                :closable="false"
                ><ElButton text @click="load">重新读取</ElButton></ElAlert
              >
              <ElSkeleton :loading="loading" animated :rows="2"
                ><div class="workbench-metrics"
                  ><div v-for="metric in metrics" :key="metric.label"
                    ><span>{{ metric.label }}</span
                    ><strong>{{ metric.value }}</strong></div
                  ></div
                ></ElSkeleton
              >
            </section>
            <template v-if="allowed('/dashboard/overview')">
              <section class="workbench-traffic" aria-label="消息流量概览">
                <section v-for="item in trafficCards" :key="item.label" class="workbench-panel">
                  <ElSkeleton :loading="loading" animated :rows="2">
                    <p class="workbench-traffic__label">{{ item.label }}</p>
                    <strong class="workbench-traffic__value">{{ item.value }}</strong>
                    <p class="workbench-caption">{{ item.hint }}</p>
                  </ElSkeleton>
                </section>
                <section class="workbench-panel workbench-window" aria-label="统计窗口">
                  <ElSkeleton :loading="loading" animated :rows="2">
                    <div>
                      <span>统计窗口</span>
                      <strong>{{ windowText }}</strong>
                    </div>
                    <div>
                      <span>生成时间</span>
                      <strong>{{ formatTime(snapshot?.generatedAt) }}</strong>
                    </div>
                  </ElSkeleton>
                </section>
              </section>
            </template>
          </div>
        </template>
        <ElEmpty v-else description="先选择或创建项目，再开始设备开发"
          ><ElButton @click="go('/project/list')">进入项目列表</ElButton></ElEmpty
        >
      </div>
      <aside v-if="projectId" class="workbench-aside">
        <WorkbenchPlanOverview
          v-if="allowed('/project/settings')"
          :overview="quota"
          :loading="quotaLoading"
          :failed="quotaError"
          :show-details="allowed('/plan-catalog')"
          @retry="load"
          @resources="go('/project/settings')"
          @details="go('/plan-catalog')"
        />
        <section v-if="showFirstDeviceHelp" class="workbench-panel workbench-help"
          ><ArtSvgIcon icon="ri:terminal-box-line" class="workbench-help__icon" /><h2
            >接入你的第一台设备</h2
          ><p>选择设备类型，获取当前设备的接入配置，再通过消息记录确认上报结果。</p
          ><ElButton @click="helpOpen = true">查看接入步骤</ElButton></section
        >
        <WorkbenchResources
          v-else-if="hasDevices"
          :project-id="projectId"
          :scope-key="JSON.stringify(scope)"
          :allowed-paths="resourcePaths"
        />
      </aside>
    </div>
    <ElDrawer v-model="helpOpen" title="设备接入步骤" size="min(480px, 100%)">
      <ol class="workbench-guide"
        ><li v-for="step in steps" :key="step.path"
          ><h3>{{ step.title }}</h3
          ><p>{{ step.help }}</p
          ><ElButton v-if="allowed(step.path)" text type="primary" @click="openHelpStep(step.path)"
            >进入{{ step.title }} →</ElButton
          ></li
        ></ol
      >
      <ElAlert
        title="凭据只在受权操作中展示，请使用设备页面给出的实际地址与协议配置。"
        type="info"
        :closable="false"
      />
    </ElDrawer>
  </main>
</template>
<script setup lang="ts">
  import { computed, ref, watch } from 'vue'
  import { useRouter } from 'vue-router'
  import { useUserStore } from '@/store/modules/user'
  import { useMenuStore } from '@/store/modules/menu'
  import { fetchProjectOverview, type OverviewResponse } from '@/api/overview'
  import { fetchProjects } from '@/api/project'
  import { fetchProjectQuota, type ProjectQuotaOverviewResponse } from '@/api/quota'
  import WorkbenchPlanOverview from './WorkbenchPlanOverview.vue'
  import WorkbenchResources from './WorkbenchResources.vue'
  import { currentIdentityEpoch } from '@/utils/http/identity-scope'
  import {
    clearRecentResources,
    readRecentResources,
    type RecentResource
  } from '@/utils/workbench-recent'
  import { formatTime } from '@/utils/time'
  import { alarmDistribution } from '@/utils/alarm-distribution'
  import type { AppRouteRecord } from '@/types/router'
  defineOptions({ name: 'DeveloperWorkbench' })
  const user = useUserStore(),
    menu = useMenuStore(),
    router = useRouter()
  const projectId = computed(() => user.info.currentProjectId ?? '')
  const scope = computed(() => ({
    userId: user.info.userId ?? '',
    tenantId: user.info.tenantId ?? '',
    projectId: projectId.value
  }))
  const projectName = ref(''),
    snapshot = ref<OverviewResponse>(),
    loading = ref(false),
    snapshotError = ref(false),
    helpOpen = ref(false)
  const recent = ref<RecentResource[]>([])
  const quota = ref<ProjectQuotaOverviewResponse>()
  const quotaLoading = ref(false)
  const quotaError = ref(false)
  const deviceCount = computed(
    () => snapshot.value?.devices?.total ?? quota.value?.project?.deviceCount?.used
  )
  const showFirstDeviceHelp = computed(() => deviceCount.value === 0)
  const hasDevices = computed(() => typeof deviceCount.value === 'number' && deviceCount.value > 0)
  const kindLabel = { type: '设备类型', device: '设备', application: '应用' }
  const paths = computed(() => {
    const result = new Set<string>()
    const visit = (rows: AppRouteRecord[]) =>
      rows.forEach((row) => {
        result.add(row.path)
        if (row.children) visit(row.children)
      })
    visit(menu.menuList)
    return result
  })
  const allowed = (path: string) => paths.value.has(path)
  const resourcePaths = computed(() => Array.from(paths.value))
  const go = (path: string) => {
    if (allowed(path)) void router.push(path)
  }
  const roleLabel = computed(
    () =>
      ({ OWNER: '项目所有者', ADMIN: '管理员', OPERATOR: '操作员', VIEWER: '观察者' })[
        user.info.roles?.[0] ?? ''
      ] ?? '项目成员'
  )
  const steps = [
    {
      title: '定义物模型',
      description: '定义属性、事件与命令',
      path: '/device/types',
      icon: 'ri:shapes-line',
      help: '在设备类型中定义属性、事件和命令，核对协议后发布模型。'
    },
    {
      title: '设备接入',
      description: '创建设备并获取配置',
      path: '/device/list',
      icon: 'ri:cpu-line',
      help: '选择已发布的设备类型，创建设备并在接入配置中读取实际连接地址与凭据要求。'
    },
    {
      title: '上报调试',
      description: '核对消息与设备数据',
      path: '/device/messages',
      icon: 'ri:terminal-line',
      help: '完成设备连接与上报后，在设备详情和消息日志中核对结果。在线不等于上报已成功。'
    },
    {
      title: '规则与自动化',
      description: '配置触发条件与动作',
      path: '/rule/automations',
      icon: 'ri:git-branch-line',
      help: '按场景配置规则或自动化，保存后通过执行记录核对结果；只读角色可从运行记录查看事实。'
    },
    {
      title: '应用与看板',
      description: '把设备数据呈现给用户',
      path: '/dashboard/applications',
      icon: 'ri:layout-grid-line',
      help: '创建看板并绑定受权设备数据，预览确认后按现有流程发布，再将看板组合到应用中。'
    }
  ]
  const number = (value?: number) =>
    typeof value === 'number' ? value.toLocaleString('zh-CN') : '—'
  const metrics = computed(() => [
    { label: '设备总数', value: number(snapshot.value?.devices?.total) },
    { label: '在线设备', value: number(snapshot.value?.devices?.online) },
    { label: '活跃设备数', value: number(snapshot.value?.devices?.active24h) },
    { label: '告警设备数', value: number(alarmDistribution(snapshot.value).alarmDevices) }
  ])
  /** 沿用项目概况的二进制流量单位，缺少后端统计时不显示为零。 */
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
  const trafficCards = computed(() => [
    {
      label: '24 小时消息量',
      value: number(snapshot.value?.messages24h?.count),
      hint: '窗口内消息日志总数'
    },
    {
      label: '24 小时流量',
      value: formatBytes(snapshot.value?.messages24h?.bytes),
      hint: '窗口内报文总字节数'
    }
  ])
  const windowText = computed(() => {
    const window = snapshot.value?.window
    return window?.from && window.to
      ? `${formatTime(window.from)} 至 ${formatTime(window.to)}`
      : '—'
  })
  let generation = 0
  async function load() {
    const id = projectId.value,
      epoch = currentIdentityEpoch(),
      request = ++generation
    snapshot.value = undefined
    quota.value = undefined
    quotaLoading.value = !!id && allowed('/project/settings')
    quotaError.value = false
    projectName.value = ''
    snapshotError.value = false
    helpOpen.value = false
    recent.value = readRecentResources(scope.value)
    loading.value = !!id
    if (!id) return
    const current = () =>
      generation === request && epoch === currentIdentityEpoch() && projectId.value === id
    void fetchProjects()
      .then((rows) => {
        if (current()) projectName.value = rows.find((row) => row.id === id)?.name ?? ''
      })
      .catch(() => {})
    if (allowed('/project/settings')) {
      void fetchProjectQuota(id)
        .then((value) => {
          if (current()) quota.value = value
        })
        .catch(() => {
          if (current()) quotaError.value = true
        })
        .finally(() => {
          if (current()) quotaLoading.value = false
        })
    }
    if (!allowed('/dashboard/overview')) {
      loading.value = false
      return
    }
    try {
      const value = await fetchProjectOverview(id)
      if (current()) snapshot.value = value
    } catch {
      if (current()) snapshotError.value = true
    } finally {
      if (current()) loading.value = false
    }
  }
  function openHelpStep(path: string) {
    helpOpen.value = false
    go(path)
  }
  function clearRecent() {
    clearRecentResources(scope.value)
    recent.value = []
  }
  function openRecent(item: RecentResource) {
    const path = {
      type: '/device/types',
      device: '/device/list',
      application: '/dashboard/applications'
    }[item.kind]
    if (allowed(path))
      void router.push({ path, query: { resourceId: item.id, contextProjectId: projectId.value } })
  }
  watch(
    () => [
      projectId.value,
      user.info.userId,
      user.info.tenantId,
      currentIdentityEpoch(),
      allowed('/dashboard/overview'),
      allowed('/project/settings')
    ],
    load,
    { immediate: true }
  )
</script>
<style scoped lang="scss">
  .developer-workbench {
    container-type: inline-size;
    container-name: workbench;
    padding: 16px;
  }
  .workbench-hero {
    display: flex;
    flex-wrap: wrap;
    gap: 24px;
    align-items: center;
    justify-content: space-between;
    padding: 28px;
    background: var(--el-bg-color);
    border: 1px solid var(--el-border-color-lighter);
    border-top: 3px solid var(--el-color-primary);
    border-radius: 12px;
  }
  .workbench-hero h1 {
    margin: 8px 0;
    font-size: 28px;
    font-weight: 600;
    overflow-wrap: anywhere;
  }
  .workbench-eyebrow {
    margin: 0;
    font-size: 13px;
    font-weight: 600;
    color: var(--el-color-primary);
    letter-spacing: 1px;
  }
  .workbench-role,
  .workbench-caption {
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }
  .workbench-section {
    margin: 28px 0;
  }
  .workbench-section-heading {
    display: flex;
    flex-wrap: wrap;
    gap: 12px;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 16px;
  }
  .workbench-section-heading > span {
    font-size: 13px;
    color: var(--el-text-color-secondary);
  }
  .developer-workbench h2 {
    margin: 0;
    font-size: 17px;
    font-weight: 600;
  }
  .development-steps {
    display: grid;
    grid-template-columns: repeat(5, minmax(0, 1fr));
    gap: 12px;
  }
  .development-step {
    position: relative;
    display: flex;
    flex-direction: column;
    gap: 10px;
    align-items: flex-start;
    padding: 20px;
    color: var(--el-text-color-primary);
    text-align: left;
    cursor: pointer;
    background: var(--el-bg-color);
    border: 1px solid var(--el-border-color-lighter);
    border-radius: 10px;
    transition:
      border-color 0.16s ease,
      transform 0.16s ease;
  }
  .development-step:hover:not(:disabled) {
    border-color: var(--el-color-primary);
    transform: translateY(-2px);
  }
  .development-step:focus-visible {
    outline: 2px solid var(--el-color-primary);
    outline-offset: 3px;
  }
  .development-step:disabled {
    cursor: default;
    opacity: 0.55;
  }
  .development-step > span:not(.development-step__number) {
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }
  .development-step__number {
    position: absolute;
    top: 16px;
    right: 16px;
    font-size: 12px;
    color: var(--el-text-color-placeholder);
  }
  .development-step__icon {
    font-size: 24px;
    color: var(--el-color-primary);
  }
  .development-step__arrow {
    align-self: flex-end;
    color: var(--el-text-color-secondary);
  }
  .workbench-columns {
    display: grid;
    grid-template-columns: minmax(0, 1fr) minmax(280px, 440px);
    gap: 20px;
  }
  .workbench-main,
  .workbench-aside {
    display: flex;
    flex-direction: column;
    gap: 20px;
    min-width: 0;
  }
  .workbench-content {
    min-width: 0;
  }
  .workbench-panel {
    padding: 24px;
    background: var(--el-bg-color);
    border: 1px solid var(--el-border-color-lighter);
    border-radius: 10px;
  }
  .workbench-shortcuts {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
    margin-top: 20px;
  }
  .workbench-shortcuts .el-button {
    margin-left: 0;
  }
  .workbench-metrics {
    display: grid;
    grid-template-columns: repeat(4, minmax(0, 1fr));
    gap: 16px;
  }
  .workbench-metrics div {
    display: flex;
    flex-direction: column;
    gap: 8px;
  }
  .workbench-metrics span {
    font-size: 13px;
    color: var(--el-text-color-secondary);
  }
  .workbench-metrics strong {
    font-size: 28px;
    font-weight: 600;
  }
  .workbench-caption {
    margin: 20px 0 0;
  }
  .workbench-traffic {
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 20px;
  }
  .workbench-traffic__label {
    margin: 0 0 12px;
    font-size: 13px;
    color: var(--el-text-color-secondary);
  }
  .workbench-traffic__value {
    font-size: 28px;
    font-weight: 600;
  }
  .workbench-window {
    display: flex;
    flex-direction: column;
    gap: 16px;
  }
  .workbench-window div {
    display: flex;
    flex-direction: column;
    gap: 8px;
  }
  .workbench-window span {
    font-size: 13px;
    color: var(--el-text-color-secondary);
  }
  .workbench-window strong {
    font-size: 14px;
    font-weight: 500;
    overflow-wrap: anywhere;
  }
  .workbench-help__icon {
    margin-bottom: 16px;
    font-size: 28px;
    color: var(--el-color-primary);
  }
  .workbench-help p,
  .workbench-guide p {
    line-height: 1.8;
    color: var(--el-text-color-secondary);
  }
  .workbench-recent {
    padding: 0;
    list-style: none;
  }
  .workbench-recent button {
    display: flex;
    gap: 16px;
    align-items: center;
    width: 100%;
    padding: 14px 0;
    color: var(--el-text-color-primary);
    text-align: left;
    cursor: pointer;
    background: transparent;
    border: 0;
    border-bottom: 1px solid var(--el-border-color-lighter);
  }
  .workbench-recent span {
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }
  .workbench-recent strong {
    flex: 1;
    overflow-wrap: anywhere;
  }
  .workbench-guide {
    padding-left: 24px;
  }
  .workbench-guide li {
    margin-bottom: 24px;
  }
  @media (width <= 1100px) {
    .development-steps {
      grid-template-columns: repeat(3, minmax(0, 1fr));
    }
  }
  @media (width <= 760px) {
    .developer-workbench {
      padding: 4px;
    }
    .workbench-columns {
      grid-template-columns: 1fr;
    }
    .development-steps {
      grid-template-columns: repeat(2, minmax(0, 1fr));
    }
    .workbench-hero {
      padding: 20px;
    }
    .workbench-hero h1 {
      font-size: 24px;
    }
  }
  @container workbench (max-width: 560px) {
    .workbench-metrics {
      grid-template-columns: repeat(2, minmax(0, 1fr));
    }
    .workbench-traffic {
      grid-template-columns: 1fr;
    }
  }
  @media (prefers-reduced-motion: reduce) {
    .development-step {
      transition: none;
    }
    .development-step:hover:not(:disabled) {
      transform: none;
    }
  }
  @container workbench (max-width: 900px) {
    .development-steps {
      grid-template-columns: repeat(3, minmax(0, 1fr));
    }
    .workbench-columns {
      grid-template-columns: minmax(0, 1fr);
    }
  }
  @container workbench (max-width: 560px) {
    .development-steps {
      grid-template-columns: repeat(2, minmax(0, 1fr));
    }
    .development-step {
      padding: 16px;
    }
  }
</style>
