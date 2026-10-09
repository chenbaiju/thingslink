<template>
  <section class="device-detail-list">
    <ElTabs v-model="mode" class="device-task-tabs" aria-label="任务调度视图">
      <ElTabPane name="jobs" label="当前任务" />
      <ElTabPane name="history" label="执行历史" />
    </ElTabs>
    <ElAlert type="info" :closable="false" show-icon>{{
      mode === 'jobs'
        ? '当前目标包含该设备的任务，含暂停配置；动态组变化后结果可能变化。'
        : '仅列出执行时实际选中过该设备的目标记录，整体执行状态与该设备结果分别展示。'
    }}</ElAlert>
    <ElAlert
      v-if="failed"
      type="error"
      title="设备任务读取失败，请切换标签后重试"
      :closable="false"
      show-icon
    />
    <ElTable v-loading="loading" :data="rows" row-key="id">
      <ElTableColumn
        prop="id"
        :label="mode === 'jobs' ? '任务 ID' : '执行 ID'"
        min-width="230"
        show-overflow-tooltip
      />
      <template v-if="mode === 'jobs'">
        <ElTableColumn prop="name" label="任务名称" min-width="140" />
        <ElTableColumn label="状态" width="90"
          ><template #default="{ row }">{{ jobStatuses[row.status] }}</template></ElTableColumn
        >
        <ElTableColumn prop="version" label="当前版本" width="95" />
        <ElTableColumn label="目标范围" min-width="130"
          ><template #default="{ row }">{{
            row.targetType === 'ALL_DEVICES' ? '项目全部设备' : '当前设备组成员'
          }}</template></ElTableColumn
        >
      </template>
      <template v-else>
        <ElTableColumn prop="jobId" label="原任务 ID" min-width="230" show-overflow-tooltip />
        <ElTableColumn label="触发方式" width="100"
          ><template #default="{ row }">{{
            row.triggerType === 'MANUAL' ? '手动' : '调度'
          }}</template></ElTableColumn
        >
        <ElTableColumn label="整体执行" width="120"
          ><template #default="{ row }">{{
            executionStatuses[row.executionStatus]
          }}</template></ElTableColumn
        >
        <ElTableColumn label="该设备结果" width="140"
          ><template #default="{ row }">{{
            targetStatuses[row.targetStatus]
          }}</template></ElTableColumn
        >
        <ElTableColumn prop="commandId" label="设备命令 ID" min-width="230" show-overflow-tooltip />
      </template>
      <ElTableColumn prop="commandKey" label="命令标识" min-width="120" />
      <ElTableColumn :label="mode === 'jobs' ? '任务建立时间' : '执行开始时间'" min-width="180">
        <template #default="{ row }">{{
          formatTime(mode === 'jobs' ? row.createdAt : row.startedAt)
        }}</template>
      </ElTableColumn>
      <template #empty
        ><ElEmpty
          :description="
            failed
              ? '任务关系不可用'
              : loading
                ? '正在读取任务'
                : mode === 'jobs'
                  ? '暂无当前关联任务'
                  : '暂无该设备执行目标记录'
          "
      /></template>
    </ElTable>
    <div
      v-if="rows.length > 0"
      class="device-detail-pagination"
      role="navigation"
      aria-label="列表分页"
    >
      <ElButton :disabled="loading || pageIndex === 0" @click="previous">上一页</ElButton>
      <span>第 {{ pageIndex + 1 }} 页</span>
      <ElButton :disabled="loading || failed || !nextCursor" @click="next">下一页</ElButton>
    </div>
  </section>
</template>
<script setup lang="ts">
  import { useUserStore } from '@/store/modules/user'
  import { formatTime } from '@/utils/time'
  import {
    fetchDeviceTasks,
    type DeviceTaskJob,
    type DeviceTaskExecution,
    type DeviceTaskMode
  } from '@/api/device-tasks'

  const props = defineProps<{ projectId: string; deviceId: string }>()
  const user = useUserStore()
  const rows = ref<(DeviceTaskJob | DeviceTaskExecution)[]>([])
  const loading = ref(false)
  const failed = ref(false)
  const pageIndex = ref(0)
  const nextCursor = ref<string>()
  let cursors: (string | undefined)[] = [undefined]
  let epoch = 0
  let controller: AbortController | undefined
  const mode = ref<DeviceTaskMode>('jobs')
  const jobStatuses: Record<string, string> = { ACTIVE: '启用', PAUSED: '暂停' }
  const executionStatuses: Record<string, string> = {
    EXPANDING: '展开目标',
    STOPPING: '停止中',
    DISPATCHING: '派发中',
    RUNNING: '执行中',
    SUCCEEDED: '全部成功',
    PARTIAL_FAILED: '部分失败',
    FAILED: '失败'
  }
  const targetStatuses: Record<string, string> = {
    PENDING: '待受理',
    ACCEPTED: '命令已受理',
    SUCCEEDED: '设备报告成功',
    FAILED: '失败',
    TIMED_OUT: '超时',
    SKIPPED: '已跳过'
  }
  function validRow(row: DeviceTaskJob | DeviceTaskExecution, kind: DeviceTaskMode) {
    if (!row.id || !row.commandKey) return false
    if (kind === 'jobs') {
      const job = row as DeviceTaskJob
      return (
        typeof job.name === 'string' &&
        Object.hasOwn(jobStatuses, job.status) &&
        Number.isInteger(job.version) &&
        job.version > 0 &&
        ['ALL_DEVICES', 'DEVICE_GROUP'].includes(job.targetType) &&
        (job.targetType === 'ALL_DEVICES' ? job.targetGroupId == null : !!job.targetGroupId) &&
        Number.isFinite(Date.parse(job.createdAt))
      )
    }
    const fact = row as DeviceTaskExecution
    return (
      !!fact.jobId &&
      ['MANUAL', 'SCHEDULE'].includes(fact.triggerType) &&
      Object.hasOwn(executionStatuses, fact.executionStatus) &&
      Object.hasOwn(targetStatuses, fact.targetStatus) &&
      Number.isFinite(Date.parse(fact.startedAt))
    )
  }
  async function load(index: number) {
    const run = ++epoch
    controller?.abort()
    controller = new AbortController()
    rows.value = []
    failed.value = false
    loading.value = true
    nextCursor.value = undefined
    pageIndex.value = index
    const device = props.deviceId
    const kind = mode.value
    try {
      const page = await fetchDeviceTasks(
        props.projectId,
        device,
        kind,
        cursors[index],
        controller.signal
      )
      if (run !== epoch) return
      if (!Array.isArray(page.items) || page.items.some((row) => !validRow(row, kind)))
        throw new Error('设备任务响应不完整')
      rows.value = page.items
      nextCursor.value = page.nextCursor ?? undefined
    } catch {
      if (run === epoch) failed.value = true
    } finally {
      if (run === epoch) loading.value = false
    }
  }
  function refresh() {
    cursors = [undefined]
    void load(0)
  }
  function next() {
    if (loading.value || failed.value || !nextCursor.value) return
    cursors[pageIndex.value + 1] = nextCursor.value
    void load(pageIndex.value + 1)
  }
  function previous() {
    if (!loading.value && pageIndex.value > 0) void load(pageIndex.value - 1)
  }
  watch(
    () => [mode.value, props.projectId, props.deviceId, user.info.userId, user.info.tenantId],
    refresh,
    {
      immediate: true
    }
  )
  onBeforeUnmount(() => {
    ++epoch
    controller?.abort()
  })
</script>

<style scoped>
  .device-task-tabs {
    flex: none;
  }

  .device-task-tabs :deep(.el-tabs__header) {
    margin-bottom: 10px;
  }

  .device-task-tabs :deep(.el-tabs__content) {
    display: none;
  }
</style>
