<template>
  <section class="device-detail-list">
    <ElTabs v-model="mode" class="device-scene-tabs" aria-label="场景视图">
      <ElTabPane v-if="canManage" name="definitions" label="项目场景候选" />
      <ElTabPane name="history" label="执行历史" />
    </ElTabs>
    <ElAlert type="info" :closable="false" show-icon>{{
      mode === 'definitions'
        ? '项目场景候选未绑定此设备；条件是否满足须在执行时判断，暂停场景不可执行。'
        : '保留原执行版本；动作意图受理及设备动作记录数均不代表设备物理执行成功。'
    }}</ElAlert>
    <ElAlert
      v-if="failed"
      type="error"
      title="场景读取失败，请重新打开场景页重试"
      :closable="false"
      show-icon
    />
    <ElTable v-loading="loading" :data="rows" row-key="id">
      <ElTableColumn
        prop="id"
        :label="mode === 'definitions' ? '场景 ID' : '执行 ID'"
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
        <ElTableColumn prop="sceneId" label="原场景 ID" min-width="230" show-overflow-tooltip />
        <ElTableColumn
          prop="sceneVersionId"
          label="原版本 ID"
          min-width="230"
          show-overflow-tooltip
        />
        <ElTableColumn label="执行状态" width="160"
          ><template #default="{ row }">{{
            executionStatuses[row.status]
          }}</template></ElTableColumn
        >
        <ElTableColumn prop="deviceActionRecordCount" label="设备动作记录数" width="145" />
      </template>
      <ElTableColumn
        :label="mode === 'definitions' ? '定义建立时间' : '执行登记时间'"
        min-width="180"
        ><template #default="{ row }">{{ formatTime(row.createdAt) }}</template></ElTableColumn
      >
      <template #empty
        ><ElEmpty
          :description="
            failed
              ? '场景关系不可用'
              : loading
                ? '正在读取场景'
                : mode === 'definitions'
                  ? '暂无当前发布的项目场景候选'
                  : '暂无该设备场景执行记录'
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
    fetchDeviceScenes,
    type DeviceScene,
    type DeviceSceneExecution,
    type DeviceSceneMode
  } from '@/api/device-scenes'

  const props = defineProps<{ projectId: string; deviceId: string; canManage: boolean }>()
  const user = useUserStore()
  const rows = ref<(DeviceScene | DeviceSceneExecution)[]>([])
  const loading = ref(false)
  const failed = ref(false)
  const pageIndex = ref(0)
  const nextCursor = ref<string>()
  let cursors: (string | undefined)[] = [undefined]
  let epoch = 0
  let controller: AbortController | undefined
  const mode = ref<DeviceSceneMode>(props.canManage ? 'definitions' : 'history')
  const definitionStatuses: Record<string, string> = { ACTIVE: '启用', PAUSED: '暂停' }
  const executionStatuses: Record<string, string> = {
    DISPATCHED: '动作意图已受理',
    SKIPPED: '已跳过',
    FAILED: '失败'
  }
  function validRow(row: DeviceScene | DeviceSceneExecution, kind: DeviceSceneMode) {
    if (!row.id || !Number.isFinite(Date.parse(row.createdAt))) return false
    if (kind === 'definitions') {
      const definition = row as DeviceScene
      return (
        definition.relationScope === 'PROJECT_CANDIDATE' &&
        typeof definition.name === 'string' &&
        Object.hasOwn(definitionStatuses, definition.status) &&
        !!definition.activeVersionId &&
        Number.isInteger(definition.versionNumber) &&
        definition.versionNumber > 0 &&
        typeof definition.usesConditionInput === 'boolean' &&
        typeof definition.hasDeviceAction === 'boolean'
      )
    }
    const fact = row as DeviceSceneExecution
    return (
      !!fact.sceneId &&
      !!fact.sceneVersionId &&
      Object.hasOwn(executionStatuses, fact.status) &&
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
      const page = await fetchDeviceScenes(
        props.projectId,
        device,
        kind,
        cursors[index],
        controller.signal
      )
      if (run !== epoch) return
      if (!Array.isArray(page.items) || page.items.some((row) => !validRow(row, kind)))
        throw new Error('设备场景响应不完整')
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

<style scoped>
  .device-scene-tabs {
    flex: none;
  }

  .device-scene-tabs :deep(.el-tabs__header) {
    margin-bottom: 10px;
  }

  .device-scene-tabs :deep(.el-tabs__content) {
    display: none;
  }
</style>
