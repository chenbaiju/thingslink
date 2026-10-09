<template>
  <section class="end-user-devices">
    <ElDivider content-position="left">当前用户的设备关系</ElDivider>
    <div class="console-actions"
      ><ElButton :disabled="loading || busy" @click="refresh">刷新设备关系</ElButton></div
    >
    <p class="console-metadata">用户 ID：{{ appUserId }}</p>
    <ElAlert class="console-hint" type="info" show-icon :closable="false"
      >项目角色不代表设备授权；关闭关系后不会因恢复项目角色而自动恢复。</ElAlert
    >
    <ElAlert v-if="notice" :title="notice" type="warning" :closable="false" show-icon />
    <ElTable v-loading="loading" :data="rows" row-key="deviceId">
      <ElTableColumn prop="deviceId" label="设备ID" min-width="230" />
      <ElTableColumn prop="relationRole" label="设备关系角色" min-width="150" />
      <ElTableColumn prop="status" label="关系状态" min-width="120" />
      <ElTableColumn label="建立时间" min-width="180"
        ><template #default="{ row }">{{ formatTime(row.createdAt) }}</template></ElTableColumn
      >
      <ElTableColumn v-if="canManage" label="操作" width="120"
        ><template #default="{ row }"
          ><ElButton
            :disabled="busy || loading || !allowWrite || row.status !== 'ACTIVE'"
            @click="unbind(row.deviceId)"
            >解绑设备</ElButton
          ></template
        ></ElTableColumn
      >
      <template #empty
        ><ElEmpty
          :description="
            failed
              ? '设备关系读取失败，请刷新'
              : loading
                ? '正在读取关系'
                : '此用户在本项目暂无设备关系'
          "
      /></template>
    </ElTable>
  </section>
</template>
<script setup lang="ts">
  import { ElMessageBox } from 'element-plus'
  import { fetchEndUserDevices, unbindEndUserDevice } from '@/api/end-users'
  import { useUserStore } from '@/store/modules/user'
  import { currentIdentityEpoch } from '@/utils/http/identity-scope'
  import { HttpError } from '@/utils/http/error'
  import { formatTime } from '@/utils/time'
  import type { components } from '@/types/api/schema'
  const props = defineProps<{ projectId: string; appUserId: string; allowWrite: boolean }>()
  const user = useUserStore()
  const canManage = computed(() => (user.info.buttons || []).includes('enduser:manage'))
  const rows = ref<components['schemas']['EndUserDeviceBindingResponse'][]>([])
  const loading = ref(false),
    busy = ref(false),
    failed = ref(false),
    notice = ref('')
  let identity = 0,
    read = 0
  async function refresh() {
    const epoch = identity,
      sequence = ++read
    rows.value = []
    failed.value = false
    loading.value = true
    try {
      const result = await fetchEndUserDevices(props.projectId, props.appUserId)
      if (epoch !== identity || sequence !== read) return
      if (
        !Array.isArray(result) ||
        result.some(
          (row) =>
            !row.deviceId ||
            !['PRIMARY', 'MEMBER', 'READ_ONLY'].includes(row.relationRole || '') ||
            !['ACTIVE', 'CLOSED'].includes(row.status || '') ||
            !Number.isFinite(Date.parse(row.createdAt || ''))
        )
      )
        throw new Error('设备关系响应无效')
      rows.value = result
    } catch {
      if (epoch === identity && sequence === read) failed.value = true
    } finally {
      if (epoch === identity && sequence === read) loading.value = false
    }
  }
  async function unbind(deviceId: string) {
    if (
      busy.value ||
      loading.value ||
      !props.allowWrite ||
      !canManage.value ||
      !rows.value.some((row) => row.deviceId === deviceId && row.status === 'ACTIVE')
    )
      return
    const epoch = identity,
      project = props.projectId,
      account = props.appUserId
    busy.value = true
    notice.value = ''
    try {
      try {
        await ElMessageBox.confirm(
          '关闭此用户在本项目的设备关系，保留历史。此操作不会删除设备，也不会修改账号或项目角色。',
          '确认解绑设备',
          { type: 'warning' }
        )
      } catch {
        return
      }
      if (epoch !== identity || !props.allowWrite || !canManage.value) return
      try {
        await unbindEndUserDevice(project, account, deviceId)
        if (epoch === identity) notice.value = '解绑已完成，已重新读取设备关系。'
      } catch (error) {
        if (epoch === identity)
          notice.value =
            error instanceof HttpError && !error.outcomeUnknown
              ? `解绑未成功：${error.message}`
              : '解绑结果未知，先刷新当前关系核对；不会自动重试。'
      }
      if (epoch === identity) await refresh()
    } finally {
      if (epoch === identity) busy.value = false
    }
  }
  watch(
    () => [
      props.projectId,
      props.appUserId,
      user.info.userId,
      user.info.tenantId,
      currentIdentityEpoch(),
      canManage.value
    ],
    () => {
      identity++
      read++
      busy.value = false
      notice.value = ''
      void refresh()
    },
    { immediate: true, flush: 'sync' }
  )
  onBeforeUnmount(() => {
    identity++
    read++
  })
</script>
