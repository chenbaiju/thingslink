<template>
  <div class="console-page alarm-page console-page--single-panel">
    <ElAlert class="alarm-page__notice" type="info" :closable="false" show-icon>
      已清除告警不会删除。设备再次异常时会创建新一代实例，不会复活旧事故。
    </ElAlert>

    <ElCard class="console-page__main-panel" shadow="never">
      <ElTable v-loading="loading" :data="items" row-key="id">
        <ElTableColumn label="告警类型" min-width="145" prop="alarmType" show-overflow-tooltip />
        <ElTableColumn label="设备" min-width="170" show-overflow-tooltip>
          <template #default="{ row }">{{ deviceName(row.deviceId) }}</template>
        </ElTableColumn>
        <ElTableColumn label="严重度" width="96">
          <template #default="{ row }">
            <ElTag :type="severityTag(row.severity)">{{ severityLabel(row.severity) }}</ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="条件状态" width="105">
          <template #default="{ row }">
            <ElTag :type="conditionTag(row.conditionState)">
              {{ conditionLabel(row.conditionState) }}
            </ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="确认状态" width="105">
          <template #default="{ row }">
            <ElTag :type="row.ackState === 'ACKNOWLEDGED' ? 'success' : 'info'">
              {{ row.ackState === 'ACKNOWLEDGED' ? '已确认' : '未确认' }}
            </ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="当前值" width="100" align="right">
          <template #default="{ row }">{{ row.lastValue ?? '—' }}</template>
        </ElTableColumn>
        <ElTableColumn label="首次命中" width="180">
          <template #default="{ row }">{{ formatTime(row.firstConditionAt) }}</template>
        </ElTableColumn>
        <ElTableColumn label="激活时间" width="180">
          <template #default="{ row }">{{ formatTime(row.activatedAt) }}</template>
        </ElTableColumn>
        <ElTableColumn label="清除信息" min-width="200">
          <template #default="{ row }">
            <template v-if="row.clearedAt">
              {{ formatTime(row.clearedAt) }} · {{ clearReasonLabel(row.clearReason) }}
            </template>
            <span v-else>—</span>
          </template>
        </ElTableColumn>
        <ElTableColumn
          class-name="console-table-actions-cell"
          label="操作"
          width="144"
          fixed="right"
        >
          <template #default="{ row }">
            <ConsoleTableAction
              type="primary"
              @click="openEvents(row)"
              label="事件"
              icon="ri:time-line"
            />
            <ConsoleTableAction
              v-if="hasAuth('alarm:maintain') && canAcknowledge(row)"
              type="success"
              @click="acknowledge(row)"
              label="确认"
              icon="ri:checkbox-circle-line"
            />
            <ConsoleTableAction
              v-if="hasAuth('alarm:maintain') && canClear(row)"
              type="danger"
              @click="clear(row)"
              label="人工清除"
              icon="ri:eraser-line"
            />
          </template>
        </ElTableColumn>
        <template #empty><ElEmpty description="还没有告警实例" /></template>
      </ElTable>

      <div v-if="hasMore" class="alarm-page__more">
        <ElButton :loading="loading" type="primary" text @click="loadMore">加载更多</ElButton>
      </div>
    </ElCard>

    <ElDrawer v-model="eventsVisible" title="告警事件时间线" size="680px" destroy-on-close>
      <ElDescriptions v-if="selected" :column="2" border class="alarm-events__summary">
        <ElDescriptionsItem label="告警类型">{{ selected.alarmType ?? '—' }}</ElDescriptionsItem>
        <ElDescriptionsItem label="设备">{{ deviceName(selected.deviceId) }}</ElDescriptionsItem>
        <ElDescriptionsItem label="条件状态">
          {{ conditionLabel(selected.conditionState) }}
        </ElDescriptionsItem>
        <ElDescriptionsItem label="确认状态">
          {{ selected.ackState === 'ACKNOWLEDGED' ? '已确认' : '未确认' }}
        </ElDescriptionsItem>
      </ElDescriptions>

      <ElTimeline v-loading="eventsLoading">
        <ElTimelineItem
          v-for="event in events"
          :key="event.id"
          :timestamp="formatTime(event.receivedAt)"
          placement="top"
        >
          <ElCard shadow="never">
            <div class="alarm-event__title">
              <strong>{{ eventLabel(event.eventType) }}</strong>
              <ElTag v-if="event.value != null" size="small">值 {{ event.value }}</ElTag>
            </div>
            <dl class="alarm-event__details">
              <template v-if="event.occurredAt">
                <dt>设备发生时间</dt><dd>{{ formatTime(event.occurredAt) }}</dd>
              </template>
              <template v-if="event.traceId">
                <dt>Trace ID</dt><dd>{{ event.traceId }}</dd>
              </template>
              <template v-if="event.clearReason">
                <dt>清除原因</dt><dd>{{ clearReasonLabel(event.clearReason) }}</dd>
              </template>
            </dl>
          </ElCard>
        </ElTimelineItem>
      </ElTimeline>
      <ElEmpty v-if="!eventsLoading && events.length === 0" description="暂无事件" />
      <div v-if="eventsHasMore" class="alarm-page__more">
        <ElButton :loading="eventsLoading" text type="primary" @click="loadMoreEvents">
          加载更多事件
        </ElButton>
      </div>
    </ElDrawer>
  </div>
</template>

<script setup lang="ts">
  import ConsoleTableAction from '@/components/ConsoleTableAction.vue'

  import { formatTime } from '@/utils/time'

  import {
    fetchAcknowledgeAlarm,
    fetchAlarmEvents,
    fetchAlarmInstances,
    fetchClearAlarm,
    type AlarmEventResponse,
    type AlarmInstanceResponse
  } from '@/api/alarm'
  import type { DeviceResponse } from '@/api/device'
  import { usePagedDeviceCatalog } from '@/composables/usePagedDeviceCatalog'
  import { useAuth } from '@/hooks/core/useAuth'
  import { useUserStore } from '@/store/modules/user'
  import { HttpError } from '@/utils/http/error'

  defineOptions({ name: 'AlarmHistory' })

  const userStore = useUserStore()
  const { hasAuth } = useAuth()
  const projectId = computed(() => userStore.info.currentProjectId ?? '')
  const loading = ref(false)
  const items = ref<AlarmInstanceResponse[]>([])
  const { devices, ensureDevices } = usePagedDeviceCatalog(projectId)
  const nextCursor = ref<string>()
  const hasMore = ref(false)
  const eventsVisible = ref(false)
  const eventsLoading = ref(false)
  const selected = ref<AlarmInstanceResponse>()
  const events = ref<AlarmEventResponse[]>([])
  const eventsNextCursor = ref<string>()
  const eventsHasMore = ref(false)

  const deviceMap = computed(
    () =>
      new Map(
        devices.value
          .filter((device): device is DeviceResponse & { id: string } => Boolean(device.id))
          .map((device) => [
            device.id,
            `${device.name ?? '未命名设备'}（${device.deviceKey ?? '—'}）`
          ])
      )
  )
  const deviceName = (id?: string) => (id ? deviceMap.value.get(id) : undefined) ?? id ?? '—'
  const severityLabel = (value?: string) =>
    ({ CRITICAL: '严重', MAJOR: '主要', MINOR: '次要', WARNING: '警告', INFO: '提示' })[
      value ?? ''
    ] ??
    value ??
    '—'
  const severityTag = (value?: string) =>
    ({ CRITICAL: 'danger', MAJOR: 'danger', MINOR: 'warning', WARNING: 'warning', INFO: 'info' })[
      value ?? ''
    ] as 'danger' | 'warning' | 'info' | undefined
  const conditionLabel = (value?: string) =>
    ({ PENDING: '等待持续', ACTIVE: '活动', CLEARED: '已清除' })[value ?? ''] ?? value ?? '—'
  const conditionTag = (value?: string) =>
    ({ PENDING: 'warning', ACTIVE: 'danger', CLEARED: 'success' })[value ?? ''] as
      | 'warning'
      | 'danger'
      | 'success'
      | undefined
  const clearReasonLabel = (value?: string) =>
    ({ AUTO_RECOVERY: '自动恢复', MANUAL: '人工清除' })[value ?? ''] ?? value ?? '—'
  const eventLabel = (value?: string) =>
    ({
      PENDING: '开始等待持续',
      ACTIVATED: '告警激活',
      ACKNOWLEDGED: '人工确认',
      CLEARED: '告警清除'
    })[value ?? ''] ??
    value ??
    '未知事件'
  const canAcknowledge = (row: AlarmInstanceResponse) =>
    row.conditionState !== 'PENDING' && row.ackState === 'UNACKNOWLEDGED'
  const canClear = (row: AlarmInstanceResponse) => row.conditionState !== 'CLEARED'

  const loadInstances = async (append = false) => {
    if (!projectId.value) return
    loading.value = true
    try {
      const page = await fetchAlarmInstances(projectId.value, append ? nextCursor.value : undefined)
      items.value = append ? [...items.value, ...(page.items ?? [])] : (page.items ?? [])
      await ensureDevices((page.items ?? []).map((item) => item.deviceId))
      nextCursor.value = page.nextCursor ?? undefined
      hasMore.value = page.hasMore ?? false
    } catch (error) {
      if (!(error instanceof HttpError)) console.error('加载告警历史失败:', error)
    } finally {
      loading.value = false
    }
  }
  const loadMore = () => void loadInstances(true)
  const replaceItem = (value: AlarmInstanceResponse) => {
    items.value = items.value.map((item) => (item.id === value.id ? value : item))
    if (selected.value?.id === value.id) selected.value = value
  }
  const acknowledge = async (row: AlarmInstanceResponse) => {
    if (!projectId.value || !row.id || row.version == null) return
    try {
      const value = await fetchAcknowledgeAlarm(projectId.value, row.id, { version: row.version })
      replaceItem(value)
      ElMessage.success('告警已确认，条件状态保持不变')
    } catch (error) {
      if (!(error instanceof HttpError)) console.error('确认告警失败:', error)
    }
  }
  const clear = async (row: AlarmInstanceResponse) => {
    if (!projectId.value || !row.id || row.version == null) return
    try {
      await ElMessageBox.confirm(
        '人工清除会结束本代事故，但不会自动标记为已确认。是否继续？',
        '人工清除告警',
        {
          type: 'warning',
          confirmButtonText: '清除',
          cancelButtonText: '取消'
        }
      )
      const value = await fetchClearAlarm(projectId.value, row.id, { version: row.version })
      replaceItem(value)
      ElMessage.success('告警已人工清除')
    } catch (error) {
      if (error !== 'cancel' && error !== 'close' && !(error instanceof HttpError)) {
        console.error('人工清除告警失败:', error)
      }
    }
  }
  const loadEvents = async (append = false) => {
    if (!projectId.value || !selected.value?.id) return
    eventsLoading.value = true
    try {
      const page = await fetchAlarmEvents(
        projectId.value,
        selected.value.id,
        append ? eventsNextCursor.value : undefined
      )
      events.value = append ? [...events.value, ...(page.items ?? [])] : (page.items ?? [])
      eventsNextCursor.value = page.nextCursor ?? undefined
      eventsHasMore.value = page.hasMore ?? false
    } catch (error) {
      if (!(error instanceof HttpError)) console.error('加载告警事件失败:', error)
    } finally {
      eventsLoading.value = false
    }
  }
  const openEvents = (row: AlarmInstanceResponse) => {
    selected.value = row
    events.value = []
    eventsNextCursor.value = undefined
    eventsVisible.value = true
    void loadEvents()
  }
  const loadMoreEvents = () => void loadEvents(true)

  onMounted(async () => {
    await loadInstances()
  })
</script>

<style scoped lang="scss">
  .alarm-page {
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

    &__notice {
      margin-bottom: 10px;
    }
    &__more {
      display: flex;
      justify-content: center;
      padding-top: 12px;
    }
  }

  .alarm-events__summary {
    margin-bottom: 10px;
  }
  .alarm-event__title {
    display: flex;
    gap: 10px;
    align-items: center;
    justify-content: space-between;
  }
  .alarm-event__details {
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
</style>
