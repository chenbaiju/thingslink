<template>
  <section class="alarm-notification-deliveries">
    <div class="console-toolbar"
      ><h4>通知投递记录</h4
      ><ElButton :disabled="loading" @click="refresh">刷新投递记录</ElButton></div
    >
    <ElAlert
      class="alarm-notification-deliveries__notice"
      title="记录是服务端投递意图与执行状态，执行成功不代表收件人或供应商已确认送达。目标已脱敏；此处不提供重发操作。"
      type="info"
      :closable="false"
      show-icon
    />
    <ElAlert
      v-if="failed"
      title="投递记录读取失败，请刷新；不能据此判断没有投递。"
      type="error"
      :closable="false"
    />
    <ElTable v-loading="loading" :data="rows" row-key="id">
      <ElTableColumn prop="channel" label="渠道" width="100" />
      <ElTableColumn prop="target" label="脱敏目标" width="100" />
      <ElTableColumn label="执行状态" min-width="190"
        ><template #default="{ row }"
          >{{ statusLabel(row.status) }}（{{ row.status }}）</template
        ></ElTableColumn
      >
      <ElTableColumn prop="attemptCount" label="尝试次数" width="100" />
      <ElTableColumn label="下次计划" min-width="180"
        ><template #default="{ row }">{{ formatTime(row.nextAttemptAt) }}</template></ElTableColumn
      >
      <ElTableColumn label="创建时间" min-width="180"
        ><template #default="{ row }">{{ formatTime(row.createdAt) }}</template></ElTableColumn
      >
      <ElTableColumn label="更新时间" min-width="180"
        ><template #default="{ row }">{{ formatTime(row.updatedAt) }}</template></ElTableColumn
      >
      <ElTableColumn prop="id" label="投递记录ID" min-width="230" />
      <ElTableColumn prop="alarmEventId" label="触发事件ID" min-width="230" />
      <template #empty
        ><ElEmpty
          :description="failed ? '读取失败，请刷新' : loading ? '正在读取' : '此告警暂无投递记录'"
      /></template>
    </ElTable>
    <ElButton v-if="nextCursor" :disabled="loading || failed" @click="load(true)"
      >加载更多投递记录</ElButton
    >
  </section>
</template>
<script setup lang="ts">
  import {
    fetchAlarmNotificationDeliveries,
    type AlarmNotificationDeliveryResponse
  } from '@/api/alarm'
  import { useUserStore } from '@/store/modules/user'
  import { currentIdentityEpoch } from '@/utils/http/identity-scope'
  import { formatTime } from '@/utils/time'
  const props = defineProps<{ projectId: string; instanceId: string }>()
  const user = useUserStore()
  const labels: Record<NonNullable<AlarmNotificationDeliveryResponse['status']>, string> = {
    QUEUED: '排队中',
    SENDING: '执行中',
    SUCCEEDED: '执行成功',
    RETRY_SCHEDULED: '已安排重试',
    DEAD_LETTER: '已进入死信',
    SKIPPED_AUTHORIZATION: '权限失效，已跳过',
    SUPPRESSED_QUOTA: '额度抑制',
    TEMPLATE_INVALID: '模板无效'
  }
  const statusLabel = (value?: AlarmNotificationDeliveryResponse['status']) =>
    value ? labels[value] : '—'
  const rows = ref<AlarmNotificationDeliveryResponse[]>([]),
    loading = ref(false),
    failed = ref(false)
  const nextCursor = ref<string | null>(null)
  let generation = 0,
    read = 0,
    request: AbortController | undefined
  const uuid = (value: unknown) =>
    typeof value === 'string' && /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(value)
  const instant = (value: unknown) =>
    typeof value === 'string' && Number.isFinite(Date.parse(value))
  function valid(row: AlarmNotificationDeliveryResponse) {
    return (
      row &&
      Object.keys(row).sort().join(',') ===
        'alarmEventId,attemptCount,channel,createdAt,id,instanceId,nextAttemptAt,status,target,updatedAt' &&
      uuid(row.id) &&
      uuid(row.alarmEventId) &&
      row.instanceId === props.instanceId &&
      ['EMAIL', 'WEBHOOK', 'PUSH'].includes(row.channel || '') &&
      Object.hasOwn(labels, row.status || '') &&
      row.target === '***' &&
      Number.isInteger(row.attemptCount) &&
      row.attemptCount! >= 0 &&
      row.attemptCount! <= 2147483647 &&
      (row.nextAttemptAt === null || instant(row.nextAttemptAt)) &&
      instant(row.createdAt) &&
      instant(row.updatedAt)
    )
  }
  async function load(append = false) {
    if (!uuid(props.projectId) || !uuid(props.instanceId)) {
      failed.value = true
      return
    }
    if (loading.value || (append && !nextCursor.value)) return
    const scope = generation,
      sequence = ++read,
      after = append ? nextCursor.value! : undefined
    request?.abort()
    request = new AbortController()
    loading.value = true
    failed.value = false
    if (!append) {
      rows.value = []
      nextCursor.value = null
    }
    try {
      const page = await fetchAlarmNotificationDeliveries(
        props.projectId,
        props.instanceId,
        after,
        request.signal
      )
      if (scope !== generation || sequence !== read) return
      if (
        !Array.isArray(page.items) ||
        page.items.length > 20 ||
        typeof page.hasMore !== 'boolean' ||
        page.items.some((row) => !valid(row)) ||
        (page.hasMore &&
          (!page.items.length ||
            typeof page.nextCursor !== 'string' ||
            !page.nextCursor ||
            page.nextCursor.length > 2048 ||
            page.nextCursor === after))
      )
        throw new Error('投递响应无效')
      const result = append ? [...rows.value, ...page.items] : page.items
      if (new Set(result.map((row) => row.id)).size !== result.length)
        throw new Error('重复投递响应')
      rows.value = result
      nextCursor.value = page.hasMore ? page.nextCursor! : null
    } catch {
      if (scope === generation && sequence === read) failed.value = true
    } finally {
      if (scope === generation && sequence === read) loading.value = false
    }
  }
  function refresh() {
    if (!loading.value) void load()
  }
  watch(
    [
      () => props.projectId,
      () => props.instanceId,
      () => user.info.userId,
      () => user.info.tenantId,
      currentIdentityEpoch
    ],
    () => {
      generation++
      read++
      request?.abort()
      rows.value = []
      nextCursor.value = null
      loading.value = false
      failed.value = false
      void load()
    },
    { immediate: true, flush: 'sync' }
  )
  onBeforeUnmount(() => {
    generation++
    read++
    request?.abort()
  })
</script>

<style scoped lang="scss">
  .alarm-notification-deliveries__notice {
    margin-bottom: 10px;

    :deep(.el-alert__title) {
      font-size: 12px;
      font-weight: normal;
      line-height: 20px;
    }
    :deep(.el-alert__icon) {
      width: 14px;
      height: 14px;
      font-size: 14px;
    }
  }
</style>
