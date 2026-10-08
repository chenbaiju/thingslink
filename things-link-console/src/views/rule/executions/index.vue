<template>
  <div class="console-page rule-executions">
    <ConsoleWorkspaceHeader
      title="执行记录"
      description="按场景、自动化与上行规则查看真实执行结果；动作受理不代表外部送达。"
      :links="[
        { label: '消息规则', path: '/rule/messages', permission: 'rule:manage' },
        { label: '自动化', path: '/rule/automations', permission: 'rule:manage' },
        { label: '任务调度', path: '/task/jobs', permission: 'task:read' }
      ]"
    />
    <AutomationExecutions v-if="activeTab === 'automation'">
      <template #tabs
        ><ElTabs v-model="activeTab">
          <ElTabPane label="场景执行" name="scene" />
          <ElTabPane label="自动化执行" name="automation" />
          <ElTabPane label="上行规则执行" name="rule" /> </ElTabs
      ></template>
    </AutomationExecutions>
    <template v-else>
      <ElCard class="console-list-filter" shadow="never">
        <ElTabs v-model="activeTab">
          <ElTabPane label="场景执行" name="scene" />
          <ElTabPane label="自动化执行" name="automation" />
          <ElTabPane label="上行规则执行" name="rule" />
        </ElTabs>
        <template v-if="activeTab === 'scene'"
          ><ConsoleFilterBar
            :items="[
              { key: 'field0', label: '场景' },
              { key: 'field1', label: '执行状态' },
              { key: 'field2', label: '发生时间', span: 24 }
            ]"
            :show-expand="false"
            :show-reset="true"
            :show-search="true"
            @search="queryScene"
            :loading="sceneLoading"
            @reset="resetSceneFilters"
          >
            <template #field0
              ><ElSelect
                v-model="sceneFilters.sceneId"
                class="rule-executions__filter"
                clearable
                filterable
                placeholder="按场景筛选"
              >
                <ElOption
                  v-for="option in sceneOptions"
                  :key="option.id ?? ''"
                  :label="option.name ?? '未命名场景'"
                  :value="option.id ?? ''"
                /> </ElSelect
            ></template>
            <template #field1
              ><ElSelect
                v-model="sceneFilters.status"
                class="rule-executions__filter"
                clearable
                placeholder="按状态筛选"
              >
                <ElOption
                  v-for="option in sceneStatusOptions"
                  :key="option.value"
                  :label="option.label"
                  :value="option.value"
                /> </ElSelect
            ></template>
            <template #field2
              ><ElDatePicker
                v-model="sceneFilters.range"
                class="rule-executions__filter"
                type="datetimerange"
                start-placeholder="开始时间"
                end-placeholder="结束时间"
                range-separator="至"
            /></template> </ConsoleFilterBar
        ></template>
        <template v-else
          ><ConsoleFilterBar
            :items="[
              { key: 'field0', label: '消息规则' },
              { key: 'field1', label: '执行状态' },
              { key: 'field2', label: '发生时间', span: 24 }
            ]"
            :show-expand="false"
            :show-reset="true"
            :show-search="true"
            @search="queryRule"
            :loading="ruleLoading"
            @reset="resetRuleFilters"
          >
            <template #field0
              ><ElSelect
                v-model="ruleFilters.ruleId"
                class="rule-executions__filter"
                clearable
                filterable
                placeholder="按规则筛选"
              >
                <ElOption
                  v-for="option in ruleOptions"
                  :key="option.id ?? ''"
                  :label="option.name ?? '未命名规则'"
                  :value="option.id ?? ''"
                /> </ElSelect
            ></template>
            <template #field1
              ><ElSelect
                v-model="ruleFilters.status"
                class="rule-executions__filter"
                clearable
                placeholder="按状态筛选"
              >
                <ElOption
                  v-for="option in ruleStatusOptions"
                  :key="option.value"
                  :label="option.label"
                  :value="option.value"
                /> </ElSelect
            ></template>
            <template #field2
              ><ElDatePicker
                v-model="ruleFilters.range"
                class="rule-executions__filter"
                type="datetimerange"
                start-placeholder="开始时间"
                end-placeholder="结束时间"
                range-separator="至"
            /></template> </ConsoleFilterBar
        ></template>
      </ElCard>
      <ElCard class="console-list-data" shadow="never">
        <template v-if="activeTab === 'scene'"
          ><ElTable v-loading="sceneLoading" :data="sceneItems" row-key="id">
            <ElTableColumn label="场景" min-width="150" show-overflow-tooltip>
              <template #default="{ row }">{{ row.sceneName ?? '—' }}</template>
            </ElTableColumn>
            <ElTableColumn label="状态" width="96">
              <template #default="{ row }">
                <ElTag :type="sceneStatusTag(row.status)">{{ sceneStatusLabel(row.status) }}</ElTag>
              </template>
            </ElTableColumn>
            <ElTableColumn label="目标设备" min-width="150" show-overflow-tooltip>
              <template #default="{ row }">{{ row.deviceId ?? '—' }}</template>
            </ElTableColumn>
            <ElTableColumn label="操作人" min-width="150" show-overflow-tooltip>
              <template #default="{ row }">{{ row.operatorAccountId ?? '—' }}</template>
            </ElTableColumn>
            <ElTableColumn label="受理时间" width="180">
              <template #default="{ row }">{{ formatTime(row.occurredAt) }}</template>
            </ElTableColumn>
            <ElTableColumn label="完成时间" width="180">
              <template #default="{ row }">{{ formatTime(row.completedAt) }}</template>
            </ElTableColumn>
            <ElTableColumn
              class-name="console-table-actions-cell"
              label="操作"
              width="64"
              fixed="right"
            >
              <template #default="{ row }">
                <ConsoleTableAction
                  type="primary"
                  @click="openSceneDetail(row)"
                  label="详情"
                  icon="ri:eye-line"
                />
              </template>
            </ElTableColumn>
            <template #empty><ElEmpty description="还没有场景执行记录" /></template>
          </ElTable>
          <div v-if="sceneHasMore" class="rule-executions__more">
            <ElButton :loading="sceneLoading" type="primary" text @click="loadMoreScene">
              加载更多
            </ElButton>
          </div></template
        >
        <template v-else
          ><ElTable v-loading="ruleLoading" :data="ruleItems" :row-key="ruleRowKey">
            <ElTableColumn label="规则" min-width="150" show-overflow-tooltip>
              <template #default="{ row }">{{ row.ruleName ?? '—' }}</template>
            </ElTableColumn>
            <ElTableColumn label="状态" width="96">
              <template #default="{ row }">
                <ElTag :type="ruleStatusTag(row.status)">{{ ruleStatusLabel(row.status) }}</ElTag>
              </template>
            </ElTableColumn>
            <ElTableColumn
              show-overflow-tooltip
              label="尝试"
              width="72"
              align="right"
              prop="attemptCount"
            />
            <ElTableColumn label="结果码" min-width="140" show-overflow-tooltip>
              <template #default="{ row }">{{ row.resultCode ?? '—' }}</template>
            </ElTableColumn>
            <ElTableColumn label="累计耗时" width="110" align="right">
              <template #default="{ row }">{{
                formatDuration(row.cumulativeDurationMillis)
              }}</template>
            </ElTableColumn>
            <ElTableColumn label="首次尝试" width="180">
              <template #default="{ row }">{{ formatTime(row.firstAttemptAt) }}</template>
            </ElTableColumn>
            <ElTableColumn label="最后尝试" width="180">
              <template #default="{ row }">{{ formatTime(row.lastAttemptAt) }}</template>
            </ElTableColumn>
            <ElTableColumn
              class-name="console-table-actions-cell"
              label="操作"
              width="64"
              fixed="right"
            >
              <template #default="{ row }">
                <ConsoleTableAction
                  type="primary"
                  @click="openAttempts(row)"
                  label="时间线"
                  icon="ri:time-line"
                />
              </template>
            </ElTableColumn>
            <template #empty><ElEmpty description="还没有上行规则执行记录" /></template>
          </ElTable>
          <div v-if="ruleHasMore" class="rule-executions__more">
            <ElButton :loading="ruleLoading" type="primary" text @click="loadMoreRule">
              加载更多
            </ElButton>
          </div></template
        >
      </ElCard>
    </template>

    <!-- 场景执行详情抽屉 -->
    <ElDrawer
      v-model="sceneDetailVisible"
      :title="selectedScene?.sceneName ?? '场景执行详情'"
      size="680px"
      destroy-on-close
    >
      <ElDescriptions v-if="sceneDetail" :column="2" border class="rule-executions__summary">
        <ElDescriptionsItem label="状态">
          <ElTag :type="sceneStatusTag(sceneDetail.execution?.status)">
            {{ sceneStatusLabel(sceneDetail.execution?.status) }}
          </ElTag>
        </ElDescriptionsItem>
        <ElDescriptionsItem label="目标设备">
          {{ sceneDetail.execution?.deviceId ?? '—' }}
        </ElDescriptionsItem>
        <ElDescriptionsItem label="操作人">
          {{ sceneDetail.execution?.operatorAccountId ?? '—' }}
        </ElDescriptionsItem>
        <ElDescriptionsItem label="场景版本">
          {{ sceneDetail.execution?.sceneVersionId ?? '—' }}
        </ElDescriptionsItem>
        <ElDescriptionsItem label="受理时间" :span="2">
          {{ formatTime(sceneDetail.execution?.occurredAt) }}
        </ElDescriptionsItem>
        <ElDescriptionsItem label="完成时间" :span="2">
          {{ formatTime(sceneDetail.execution?.completedAt) }}
        </ElDescriptionsItem>
        <ElDescriptionsItem label="Trace ID" :span="2">
          {{ sceneDetail.execution?.traceId ?? '—' }}
        </ElDescriptionsItem>
      </ElDescriptions>

      <h4 class="rule-executions__section">通知投递</h4>
      <ElTable
        v-loading="sceneDetailLoading"
        :data="sceneDetail?.notifications ?? []"
        row-key="createdAt"
      >
        <ElTableColumn show-overflow-tooltip label="渠道" width="110" prop="channel" />
        <ElTableColumn label="收件人" min-width="150" show-overflow-tooltip prop="recipient" />
        <ElTableColumn label="状态" width="104">
          <template #default="{ row }">
            <ElTag :type="notificationStatusTag(row.status)">
              {{ notificationStatusLabel(row.status) }}
            </ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="尝试" width="96" align="right">
          <template #default="{ row }">{{ row.attemptCount }}/{{ row.maxAttempts }}</template>
        </ElTableColumn>
        <ElTableColumn label="送达时间" width="180">
          <template #default="{ row }">{{ formatTime(row.deliveredAt) }}</template>
        </ElTableColumn>
        <template #empty><ElEmpty description="本次执行没有通知投递" /></template>
      </ElTable>

      <h4 class="rule-executions__section">设备动作投递</h4>
      <ElTable :data="sceneDetail?.deviceActions ?? []" row-key="commandId">
        <ElTableColumn label="设备" min-width="150" show-overflow-tooltip prop="deviceId" />
        <ElTableColumn label="操作类型" width="110">
          <template #default="{ row }">{{ operationTypeLabel(row.operationType) }}</template>
        </ElTableColumn>
        <ElTableColumn label="状态" width="104">
          <template #default="{ row }">
            <ElTag :type="deviceActionStatusTag(row.status)">
              {{ deviceActionStatusLabel(row.status) }}
            </ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="受理时间" width="180">
          <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
        </ElTableColumn>
        <ElTableColumn label="完成时间" width="180">
          <template #default="{ row }">{{ formatTime(row.completedAt) }}</template>
        </ElTableColumn>
        <template #empty><ElEmpty description="本次执行没有设备动作" /></template>
      </ElTable>
    </ElDrawer>

    <!-- 上行规则执行 attempt 时间线抽屉 -->
    <ElDrawer
      v-model="attemptsVisible"
      :title="selectedRule?.ruleName ?? 'attempt 时间线'"
      size="680px"
      destroy-on-close
    >
      <ElDescriptions v-if="selectedRule" :column="2" border class="rule-executions__summary">
        <ElDescriptionsItem label="消息 ID">{{ selectedRule.messageId ?? '—' }}</ElDescriptionsItem>
        <ElDescriptionsItem label="状态">
          <ElTag :type="ruleStatusTag(selectedRule.status)">
            {{ ruleStatusLabel(selectedRule.status) }}
          </ElTag>
        </ElDescriptionsItem>
        <ElDescriptionsItem label="累计耗时">
          {{ formatDuration(selectedRule.cumulativeDurationMillis) }}
        </ElDescriptionsItem>
        <ElDescriptionsItem label="尝试次数">{{
          selectedRule.attemptCount ?? '—'
        }}</ElDescriptionsItem>
      </ElDescriptions>

      <ElTimeline v-loading="attemptsLoading">
        <ElTimelineItem
          v-for="attempt in attempts"
          :key="attempt.attempt"
          :timestamp="formatTime(attempt.completedAt)"
          placement="top"
        >
          <ElCard shadow="never">
            <div class="rule-executions__attempt-title">
              <strong>第 {{ attempt.attempt }} 次尝试</strong>
              <ElTag :type="ruleStatusTag(attempt.status)" size="small">
                {{ ruleStatusLabel(attempt.status) }}
              </ElTag>
            </div>
            <dl class="rule-executions__attempt-details">
              <dt>结果码</dt><dd>{{ attempt.resultCode ?? '—' }}</dd> <dt>耗时</dt
              ><dd>{{ formatDuration(attempt.durationMillis) }}</dd> <dt>输入字节</dt
              ><dd>{{ attempt.inputBytes ?? '—' }}</dd> <dt>输出字节</dt
              ><dd>{{ attempt.outputBytes ?? '—' }}</dd>
            </dl>
          </ElCard>
        </ElTimelineItem>
      </ElTimeline>
      <ElEmpty v-if="!attemptsLoading && attempts.length === 0" description="暂无尝试记录" />
    </ElDrawer>
  </div>
</template>

<script setup lang="ts">
  import { useRoute } from 'vue-router'
  import ConsoleWorkspaceHeader from '@/components/business/ConsoleWorkspaceHeader.vue'
  import ConsoleFilterBar from '@/components/ConsoleFilterBar.vue'
  import ConsoleTableAction from '@/components/ConsoleTableAction.vue'

  import AutomationExecutions from '../components/AutomationExecutions.vue'
  import { formatTime } from '@/utils/time'
  import { notificationStatusLabel, notificationStatusTag } from '@/utils/notificationStatus'

  import {
    fetchRuleExecutionAttempts,
    fetchRuleExecutionRuleOptions,
    fetchRuleExecutions,
    fetchSceneExecutionDetail,
    fetchSceneExecutionSceneOptions,
    fetchSceneExecutions,
    type RuleExecutionAttemptResponse,
    type RuleExecutionSummaryResponse,
    type RuleOptionResponse,
    type RuleSceneExecutionDetailResponse,
    type RuleSceneExecutionResponse
  } from '@/api/rule-execution'
  import { useUserStore } from '@/store/modules/user'
  import { HttpError } from '@/utils/http/error'

  defineOptions({ name: 'RuleExecutions' })

  type TabName = 'scene' | 'rule' | 'automation'

  const userStore = useUserStore()
  const projectId = computed(() => userStore.info.currentProjectId ?? '')

  const route = useRoute()
  const sourceTab = (): TabName => {
    const source = route?.query.source
    return source === 'rule' || source === 'automation' ? source : 'scene'
  }
  const activeTab = ref<TabName>(sourceTab())
  watch(
    () => route?.query.source,
    () => {
      activeTab.value = sourceTab()
    }
  )

  // —— 场景执行状态 ——
  const sceneItems = ref<RuleSceneExecutionResponse[]>([])
  const sceneOptions = ref<RuleOptionResponse[]>([])
  const sceneLoading = ref(false)
  const sceneNextCursor = ref<string>()
  const sceneHasMore = ref(false)
  const sceneFilters = reactive({
    sceneId: '',
    status: '',
    range: undefined as [Date, Date] | undefined
  })

  // —— 上行规则执行状态 ——
  const ruleItems = ref<RuleExecutionSummaryResponse[]>([])
  const ruleOptions = ref<RuleOptionResponse[]>([])
  const ruleLoading = ref(false)
  const ruleNextCursor = ref<string>()
  const ruleHasMore = ref(false)
  const ruleFilters = reactive({
    ruleId: '',
    status: '',
    range: undefined as [Date, Date] | undefined
  })

  // —— 抽屉状态 ——
  const sceneDetailVisible = ref(false)
  const sceneDetailLoading = ref(false)
  const selectedScene = ref<RuleSceneExecutionResponse>()
  const sceneDetail = ref<RuleSceneExecutionDetailResponse>()

  const attemptsVisible = ref(false)
  const attemptsLoading = ref(false)
  const selectedRule = ref<RuleExecutionSummaryResponse>()
  const attempts = ref<RuleExecutionAttemptResponse[]>([])

  // —— 状态标签与选项 ——
  const sceneStatusOptions = [
    { value: 'DISPATCHED', label: '已受理' },
    { value: 'SKIPPED', label: '已跳过' },
    { value: 'FAILED', label: '失败' }
  ]
  const ruleStatusOptions = [
    { value: 'SUCCESS', label: '成功' },
    { value: 'RETRY_SCHEDULED', label: '待重试' },
    { value: 'DEAD_LETTER', label: '永久失败' }
  ]

  const sceneStatusLabel = (value?: string) =>
    ({ DISPATCHED: '已受理', SKIPPED: '已跳过', FAILED: '失败' })[value ?? ''] ?? value ?? '—'
  const sceneStatusTag = (value?: string) =>
    ({ DISPATCHED: 'success', SKIPPED: 'info', FAILED: 'danger' })[value ?? ''] as
      | 'success'
      | 'info'
      | 'danger'
      | undefined
  const ruleStatusLabel = (value?: string) =>
    ({ SUCCESS: '成功', RETRY_SCHEDULED: '待重试', DEAD_LETTER: '永久失败' })[value ?? ''] ??
    value ??
    '—'
  const ruleStatusTag = (value?: string) =>
    ({ SUCCESS: 'success', RETRY_SCHEDULED: 'warning', DEAD_LETTER: 'danger' })[value ?? ''] as
      | 'success'
      | 'warning'
      | 'danger'
      | undefined
  const deviceActionStatusLabel = (value?: string) =>
    ({
      ACCEPTED: '已受理',
      REJECTED: '已拒绝',
      SUCCEEDED: '成功',
      FAILED: '失败',
      TIMED_OUT: '超时'
    })[value ?? ''] ??
    value ??
    '—'
  const deviceActionStatusTag = (value?: string) =>
    ({
      ACCEPTED: 'info',
      REJECTED: 'danger',
      SUCCEEDED: 'success',
      FAILED: 'danger',
      TIMED_OUT: 'warning'
    })[value ?? ''] as 'info' | 'danger' | 'success' | 'warning' | undefined
  const operationTypeLabel = (value?: string) =>
    ({ COMMAND: '命令', PROPERTY_SET: '属性设置' })[value ?? ''] ?? value ?? '—'

  const ruleRowKey = (row: RuleExecutionSummaryResponse) =>
    [row.messageId, row.ruleId, row.ruleVersionId].filter(Boolean).join(':') || row.ruleName

  const formatDuration = (ms?: number) => {
    if (ms == null) return '—'
    if (ms < 1000) return `${ms} ms`
    return `${(ms / 1000).toFixed(1)} s`
  }

  const rangeToQuery = (range?: [Date, Date]) => ({
    from: range?.[0]?.toISOString(),
    to: range?.[1]?.toISOString()
  })

  // —— 场景执行列表 ——
  const loadScene = async (append = false) => {
    if (!projectId.value) return
    sceneLoading.value = true
    try {
      const page = await fetchSceneExecutions(projectId.value, {
        sceneId: sceneFilters.sceneId || undefined,
        status: sceneFilters.status || undefined,
        ...rangeToQuery(sceneFilters.range),
        cursor: append ? sceneNextCursor.value : undefined
      })
      sceneItems.value = append ? [...sceneItems.value, ...(page.items ?? [])] : (page.items ?? [])
      sceneNextCursor.value = page.nextCursor ?? undefined
      sceneHasMore.value = page.hasMore ?? false
    } catch (error) {
      if (!(error instanceof HttpError)) console.error('加载场景执行记录失败:', error)
    } finally {
      sceneLoading.value = false
    }
  }
  const queryScene = () => void loadScene()
  const loadMoreScene = () => void loadScene(true)
  const resetSceneFilters = () => {
    sceneFilters.sceneId = ''
    sceneFilters.status = ''
    sceneFilters.range = undefined
    void loadScene()
  }

  // —— 上行规则执行列表 ——
  const loadRule = async (append = false) => {
    if (!projectId.value) return
    ruleLoading.value = true
    try {
      const page = await fetchRuleExecutions(projectId.value, {
        ruleId: ruleFilters.ruleId || undefined,
        status: ruleFilters.status || undefined,
        ...rangeToQuery(ruleFilters.range),
        cursor: append ? ruleNextCursor.value : undefined
      })
      ruleItems.value = append ? [...ruleItems.value, ...(page.items ?? [])] : (page.items ?? [])
      ruleNextCursor.value = page.nextCursor ?? undefined
      ruleHasMore.value = page.hasMore ?? false
    } catch (error) {
      if (!(error instanceof HttpError)) console.error('加载上行规则执行记录失败:', error)
    } finally {
      ruleLoading.value = false
    }
  }
  const queryRule = () => void loadRule()
  const loadMoreRule = () => void loadRule(true)
  const resetRuleFilters = () => {
    ruleFilters.ruleId = ''
    ruleFilters.status = ''
    ruleFilters.range = undefined
    void loadRule()
  }

  // —— 场景执行详情 ——
  const openSceneDetail = async (row: RuleSceneExecutionResponse) => {
    if (!projectId.value || !row.id) return
    selectedScene.value = row
    sceneDetail.value = undefined
    sceneDetailVisible.value = true
    sceneDetailLoading.value = true
    try {
      sceneDetail.value = await fetchSceneExecutionDetail(projectId.value, row.id)
    } catch (error) {
      if (!(error instanceof HttpError)) console.error('加载场景执行详情失败:', error)
    } finally {
      sceneDetailLoading.value = false
    }
  }

  // —— 上行规则执行 attempt 时间线 ——
  const openAttempts = async (row: RuleExecutionSummaryResponse) => {
    if (!projectId.value || !row.messageId || !row.ruleId || !row.ruleVersionId) return
    selectedRule.value = row
    attempts.value = []
    attemptsVisible.value = true
    attemptsLoading.value = true
    try {
      attempts.value = await fetchRuleExecutionAttempts(
        projectId.value,
        row.messageId,
        row.ruleId,
        row.ruleVersionId
      )
    } catch (error) {
      if (!(error instanceof HttpError)) console.error('加载 attempt 时间线失败:', error)
    } finally {
      attemptsLoading.value = false
    }
  }

  // 场景列表由首次挂载加载；切换页签后读取对应列表，避免把未查询误显示成空记录。
  watch(activeTab, (tab) => {
    if (tab === 'rule') void loadRule()
    else if (tab === 'scene') void loadScene()
    // 自动化页签挂载自己的组件，由它维护权限与读取生命周期。
  })

  onMounted(async () => {
    if (!projectId.value) return
    await Promise.allSettled([
      fetchSceneExecutionSceneOptions(projectId.value).then(
        (value) => (sceneOptions.value = value)
      ),
      fetchRuleExecutionRuleOptions(projectId.value).then((value) => (ruleOptions.value = value)),
      activeTab.value === 'rule'
        ? loadRule()
        : activeTab.value === 'scene'
          ? loadScene()
          : Promise.resolve()
    ])
  })
</script>

<style scoped lang="scss">
  .rule-executions {
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

    &__filters {
      display: flex;
      flex-wrap: wrap;
      gap: 10px;
      align-items: center;
      margin: 10px 0;
    }

    &__filter {
      width: 220px;
    }

    &__more {
      display: flex;
      justify-content: center;
      padding-top: 12px;
    }

    &__summary {
      margin-bottom: 10px;
    }

    &__section {
      margin: 10px 0;
      color: var(--art-text-gray-700);
    }

    &__attempt-title {
      display: flex;
      gap: 10px;
      align-items: center;
      justify-content: space-between;
    }

    &__attempt-details {
      display: grid;
      grid-template-columns: max-content 1fr;
      gap: 6px 12px;
      margin: 12px 0 0;

      dt {
        color: var(--art-text-gray-500);
      }
      dd {
        min-width: 0;
        margin: 0;
        overflow-wrap: anywhere;
      }
    }
  }
</style>
