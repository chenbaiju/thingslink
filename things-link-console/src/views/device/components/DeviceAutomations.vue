<template>
  <section>
    <div class="console-toolbar">
      <div class="console-actions">
        <ElButton
          v-if="canManage"
          :type="mode === 'definitions' ? 'primary' : 'default'"
          @click="mode = 'definitions'"
          >当前自动化</ElButton
        >
        <ElButton :type="mode === 'history' ? 'primary' : 'default'" @click="mode = 'history'"
          >执行历史</ElButton
        >
      </div>
      <ElButton :loading="loading" @click="refresh">刷新自动化</ElButton>
    </div>
    <p class="console-description">{{
      mode === 'definitions'
        ? '仅显示当前发布版本的设备关系，含暂停配置；定时触发使用设备上下文，不是设备主动上报。'
        : '保留原执行版本；动作意图受理及设备动作记录数均不代表设备物理执行成功。'
    }}</p>
    <ElAlert v-if="failed" type="error" title="自动化读取失败，请刷新重试" :closable="false" />
    <ElTable v-loading="loading" :data="rows" row-key="id">
      <ElTableColumn
        prop="id"
        :label="mode === 'definitions' ? '自动化 ID' : '执行 ID'"
        min-width="230"
        show-overflow-tooltip
      />
      <template v-if="mode === 'definitions'">
        <ElTableColumn prop="name" label="名称" min-width="130" />
        <ElTableColumn label="状态" width="90"
          ><template #default="{ row }">{{
            definitionStatuses[row.status]
          }}</template></ElTableColumn
        >
        <ElTableColumn prop="versionNumber" label="发布版本号" width="110" />
        <ElTableColumn label="条件" width="110"
          ><template #default="{ row }">{{
            row.usesConditionInput ? '含条件判断' : '无条件判断'
          }}</template></ElTableColumn
        >
        <ElTableColumn label="设备动作" width="100"
          ><template #default="{ row }">{{
            row.hasDeviceAction ? '有' : '无'
          }}</template></ElTableColumn
        >
      </template>
      <template v-else>
        <ElTableColumn
          prop="automationId"
          label="原自动化 ID"
          min-width="230"
          show-overflow-tooltip
        />
        <ElTableColumn
          prop="automationVersionId"
          label="原版本 ID"
          min-width="230"
          show-overflow-tooltip
        />
        <ElTableColumn label="执行状态" width="160"
          ><template #default="{ row }">{{
            executionStatuses[row.status]
          }}</template></ElTableColumn
        >
        <ElTableColumn prop="attemptCount" label="尝试次数" width="95" />
        <ElTableColumn prop="deviceActionRecordCount" label="设备动作记录数" width="145" />
        <ElTableColumn prop="reasonCode" label="原因码" min-width="150" />
      </template>
      <ElTableColumn label="触发与设备关系" min-width="200"
        ><template #default="{ row }">{{ triggers[row.triggerType] }}</template></ElTableColumn
      >
      <ElTableColumn
        :label="mode === 'definitions' ? '定义建立时间' : '执行登记时间'"
        min-width="180"
        ><template #default="{ row }">{{ formatTime(row.createdAt) }}</template></ElTableColumn
      >
      <template #empty
        ><ElEmpty
          :description="
            failed
              ? '自动化关系不可用'
              : loading
                ? '正在读取自动化'
                : mode === 'definitions'
                  ? '暂无当前发布的关联自动化'
                  : '暂无该设备自动化执行记录'
          "
      /></template>
    </ElTable>
    <div class="console-page-actions">
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
    fetchDeviceAutomations,
    type DeviceAutomation,
    type DeviceAutomationExecution,
    type DeviceAutomationMode
  } from '@/api/device-automations'

  const props = defineProps<{ projectId: string; deviceId: string; canManage: boolean }>()
  const user = useUserStore()
  const rows = ref<(DeviceAutomation | DeviceAutomationExecution)[]>([])
  const loading = ref(false)
  const failed = ref(false)
  const pageIndex = ref(0)
  const nextCursor = ref<string>()
  let cursors: (string | undefined)[] = [undefined]
  let epoch = 0
  let controller: AbortController | undefined
  const mode = ref<DeviceAutomationMode>(props.canManage ? 'definitions' : 'history')
  const definitionStatuses: Record<string, string> = { ACTIVE: '启用', PAUSED: '暂停' }
  const executionStatuses: Record<string, string> = {
    QUEUED: '排队中',
    RUNNING: '处理中',
    RETRY_WAIT: '等待重试',
    DISPATCHED: '动作意图已受理',
    SKIPPED: '已跳过',
    FAILED: '失败',
    REJECTED: '已拒绝'
  }
  const triggers: Record<string, string> = {
    PROPERTY_REPORTED: '设备上报源',
    ONE_SHOT: '一次定时设备上下文',
    CRON: '周期定时设备上下文'
  }
  function validRow(row: DeviceAutomation | DeviceAutomationExecution, kind: DeviceAutomationMode) {
    if (
      !row.id ||
      !Object.hasOwn(triggers, row.triggerType) ||
      !Number.isFinite(Date.parse(row.createdAt))
    )
      return false
    if (kind === 'definitions') {
      const definition = row as DeviceAutomation
      return (
        typeof definition.name === 'string' &&
        Object.hasOwn(definitionStatuses, definition.status) &&
        !!definition.activeVersionId &&
        Number.isInteger(definition.versionNumber) &&
        definition.versionNumber > 0 &&
        typeof definition.usesConditionInput === 'boolean' &&
        typeof definition.hasDeviceAction === 'boolean'
      )
    }
    const fact = row as DeviceAutomationExecution
    return (
      !!fact.automationId &&
      !!fact.automationVersionId &&
      Object.hasOwn(executionStatuses, fact.status) &&
      Number.isInteger(fact.attemptCount) &&
      fact.attemptCount >= 0 &&
      Number.isSafeInteger(fact.deviceActionRecordCount) &&
      fact.deviceActionRecordCount >= 0
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
      const page = await fetchDeviceAutomations(
        props.projectId,
        device,
        kind,
        cursors[index],
        controller.signal
      )
      if (run !== epoch) return
      if (!Array.isArray(page.items) || page.items.some((row) => !validRow(row, kind)))
        throw new Error('设备自动化响应不完整')
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
    () => props.canManage,
    (allowed) => {
      if (!allowed) mode.value = 'history'
    }
  )
  watch(
    () => [
      props.canManage,
      mode.value,
      props.projectId,
      props.deviceId,
      user.info.userId,
      user.info.tenantId
    ],
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
