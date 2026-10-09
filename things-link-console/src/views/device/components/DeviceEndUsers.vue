<template>
  <section class="device-detail-list">
    <ElDivider content-position="left">当前终端用户</ElDivider>
    <div class="device-end-users__actions">
      <ElButton :loading="loading" @click="refresh">刷新用户</ElButton>
      <ElAlert type="info" :closable="false" show-icon
        >仅列出账号、项目角色和设备关系均有效的用户；关系角色不等于项目角色。</ElAlert
      >
    </div>
    <ElAlert
      v-if="failed"
      type="error"
      title="终端用户读取失败，请刷新重试"
      :closable="false"
      show-icon
    />
    <DeviceClaimToken
      :project-id="projectId"
      :device-id="deviceId"
      :primary-known="rows.some((row) => row.relationRole === 'PRIMARY')"
    />
    <ElTable v-loading="loading" :data="rows" row-key="id">
      <ElTableColumn prop="appUserId" label="用户 ID" min-width="230" show-overflow-tooltip />
      <ElTableColumn label="显示名" min-width="140">
        <template #default="{ row }">{{ row.displayName || '—' }}</template>
      </ElTableColumn>
      <ElTableColumn label="设备关系" width="100">
        <template #default="{ row }">{{ roles[row.relationRole] }}</template>
      </ElTableColumn>
      <ElTableColumn label="关系建立时间" min-width="180">
        <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
      </ElTableColumn>
      <template #empty
        ><ElEmpty
          :description="
            failed ? '用户关系不可用' : loading ? '正在读取用户' : '暂无当前有效终端用户'
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
  import DeviceClaimToken from './DeviceClaimToken.vue'
  import { useUserStore } from '@/store/modules/user'
  import { formatTime } from '@/utils/time'
  import { fetchDeviceEndUsers, type DeviceEndUser } from '@/api/device-end-users'

  const props = defineProps<{ projectId: string; deviceId: string }>()
  const user = useUserStore()
  const rows = ref<DeviceEndUser[]>([])
  const loading = ref(false)
  const failed = ref(false)
  const pageIndex = ref(0)
  const nextCursor = ref<string>()
  let cursors: (string | undefined)[] = [undefined]
  let epoch = 0
  let controller: AbortController | undefined
  const roles: Record<string, string> = {
    PRIMARY: '主控',
    MEMBER: '成员',
    READ_ONLY: '只读'
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
      const page = await fetchDeviceEndUsers(
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
            !row.appUserId ||
            !Object.hasOwn(roles, row.relationRole) ||
            !Number.isFinite(Date.parse(row.createdAt)) ||
            (row.displayName != null && typeof row.displayName !== 'string')
        )
      )
        throw new Error('设备终端用户响应不完整')
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
  watch(() => [props.projectId, props.deviceId, user.info.userId, user.info.tenantId], refresh, {
    immediate: true
  })
  onBeforeUnmount(() => {
    ++epoch
    controller?.abort()
  })
</script>

<style scoped>
  .device-end-users__actions {
    display: flex;
    gap: 10px;
    align-items: center;
    margin-bottom: 10px;
  }

  .device-end-users__actions :deep(.el-alert) {
    flex: 1;
    min-width: 0;
    margin-bottom: 0;
  }
</style>
