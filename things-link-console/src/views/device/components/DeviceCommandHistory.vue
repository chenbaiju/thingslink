<template>
  <section class="command-history">
    <div class="console-toolbar">
      <h4>命令执行历史</h4>
      <ElButton :loading="loading" @click="refresh">刷新历史</ElButton>
    </div>
    <ElAlert type="info" :closable="false" show-icon>
      按受理时间倒序，包含命令与属性设置。受理或交给 Broker 不代表设备执行成功。
    </ElAlert>
    <ElAlert
      v-if="failed"
      type="error"
      title="命令历史读取失败，请刷新重试"
      :closable="false"
      show-icon
    />
    <ElTable v-loading="loading" :data="rows" row-key="id">
      <ElTableColumn prop="id" label="命令 ID" min-width="230" show-overflow-tooltip />
      <ElTableColumn label="操作" min-width="130">
        <template #default="{ row }">{{
          row.operationType === 'PROPERTY_SET' ? '属性设置' : row.commandKey
        }}</template>
      </ElTableColumn>
      <ElTableColumn label="状态" width="150">
        <template #default="{ row }">{{ statuses[row.status] ?? '状态不可用' }}</template>
      </ElTableColumn>
      <ElTableColumn label="尝试次数" width="100">
        <template #default="{ row }">{{ row.attemptCount }} / {{ row.maxAttempts }}</template>
      </ElTableColumn>
      <ElTableColumn label="受理时间" min-width="180">
        <template #default="{ row }">{{ formatTime(row.acceptedAt) }}</template>
      </ElTableColumn>
      <ElTableColumn label="完成时间" min-width="180">
        <template #default="{ row }">{{
          row.completedAt ? formatTime(row.completedAt) : '—'
        }}</template>
      </ElTableColumn>
      <template #empty
        ><ElEmpty
          :description="
            failed ? '历史状态不可用' : loading ? '正在读取历史' : '暂无可查询的命令历史'
          "
      /></template>
    </ElTable>
    <DeviceDetailPagination
      v-if="rows.length > 0"
      :page-index="pageIndex"
      :loading="loading"
      :failed="failed"
      :has-next="!!nextCursor"
      @previous="previous"
      @next="next"
    />
  </section>
</template>
<script setup lang="ts">
  import DeviceDetailPagination from './DeviceDetailPagination.vue'
  import { useUserStore } from '@/store/modules/user'
  import { formatTime } from '@/utils/time'
  import {
    fetchDeviceCommandHistory,
    type DeviceCommandHistoryItem
  } from '@/api/device-command-history'

  const props = defineProps<{ projectId: string; deviceId: string; refreshKey?: string }>()
  const user = useUserStore()
  const rows = ref<DeviceCommandHistoryItem[]>([])
  const loading = ref(false)
  const failed = ref(false)
  const pageIndex = ref(0)
  const nextCursor = ref<string>()
  let cursors: (string | undefined)[] = [undefined]
  let epoch = 0
  let controller: AbortController | undefined
  const statuses: Record<string, string> = {
    ACCEPTED: '平台已受理',
    DISPATCHED: '已交给 Broker',
    ACKNOWLEDGED: '设备已确认接收',
    SUCCEEDED: '设备报告成功',
    FAILED: '失败',
    TIMED_OUT: '已超时'
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
    try {
      const page = await fetchDeviceCommandHistory(
        props.projectId,
        device,
        cursors[index],
        controller.signal
      )
      if (run !== epoch) return
      if (
        !Array.isArray(page.items) ||
        page.items.some(
          (row) =>
            !row.id ||
            row.deviceId !== device ||
            !Object.hasOwn(statuses, row.status) ||
            !['COMMAND', 'PROPERTY_SET'].includes(row.operationType) ||
            !Number.isFinite(Date.parse(row.acceptedAt)) ||
            !Number.isInteger(row.attemptCount) ||
            !Number.isInteger(row.maxAttempts) ||
            row.attemptCount < 0 ||
            row.maxAttempts < row.attemptCount
        )
      )
        throw new Error('命令历史响应不完整')
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
    () => [props.projectId, props.deviceId, props.refreshKey, user.info.userId, user.info.tenantId],
    refresh,
    { immediate: true }
  )
  onBeforeUnmount(() => {
    ++epoch
    controller?.abort()
  })
</script>
