<template>
  <div class="console-page alarm-page console-page--single-panel">
    <ConsoleWorkspaceHeader
      title="告警历史"
      description="查看异常设备，确认或清除告警，并从单条告警追踪通知投递。"
      :links="[
        { label: '告警规则', path: '/alarm/rules', permission: 'alarm:read' },
        { label: '通知组', path: '/alarm/notification-groups', permission: 'alarm:read' }
      ]"
    />
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
      <div class="alarm-events__toolbar">
        <span>摘要与事件分别读取；外部操作后可刷新查看。</span>
        <ElButton :loading="detailLoading || eventsLoading" @click="refreshDetails">
          刷新详情
        </ElButton>
      </div>
      <ElAlert
        v-if="detailError"
        title="告警摘要暂不可用，请刷新详情重试"
        type="error"
        :closable="false"
        show-icon
      />
      <ElSkeleton v-if="detailLoading" :rows="2" animated />
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

      <ElAlert
        v-if="eventsError"
        title="告警事件暂不可用，请刷新详情重试"
        type="error"
        :closable="false"
        show-icon
      />
      <ElTimeline v-if="selected" v-loading="eventsLoading">
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
      <ElEmpty
        v-if="selected && !eventsLoading && !eventsError && events.length === 0"
        description="暂无事件"
      />
      <div v-if="eventsHasMore" class="alarm-page__more">
        <ElButton :loading="eventsLoading" text type="primary" @click="loadMoreEvents">
          加载更多事件
        </ElButton>
      </div>
      <AlarmNotificationDeliveries
        v-if="eventsVisible && selected?.id && projectId"
        :project-id="projectId"
        :instance-id="selected.id"
      />
    </ElDrawer>
  </div>
</template>

<script setup lang="ts">
  import ConsoleWorkspaceHeader from '@/components/business/ConsoleWorkspaceHeader.vue'
  import ConsoleTableAction from '@/components/ConsoleTableAction.vue'
  import AlarmNotificationDeliveries from './AlarmNotificationDeliveries.vue'
  import { currentIdentityEpoch } from '@/utils/http/identity-scope'

  import { formatTime } from '@/utils/time'

  import {
    fetchAcknowledgeAlarm,
    fetchAlarmEvents,
    fetchAlarmInstances,
    fetchAlarmInstance,
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
  const selectedId = ref<string>()
  const detailLoading = ref(false)
  const detailError = ref(false)
  const eventsError = ref(false)
  const events = ref<AlarmEventResponse[]>([])
  const eventsNextCursor = ref<string>()
  const eventsHasMore = ref(false)
  let identity = 0,
    instanceRead = 0,
    eventRead = 0,
    detailRead = 0

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
    if (!projectId.value || loading.value) return
    const epoch = identity,
      sequence = ++instanceRead,
      id = projectId.value
    loading.value = true
    try {
      const page = await fetchAlarmInstances(id, append ? nextCursor.value : undefined)
      if (epoch !== identity || sequence !== instanceRead) return
      await ensureDevices((page.items ?? []).map((item) => item.deviceId))
      if (epoch !== identity || sequence !== instanceRead) return
      items.value = append ? [...items.value, ...(page.items ?? [])] : (page.items ?? [])
      nextCursor.value = page.nextCursor ?? undefined
      hasMore.value = page.hasMore ?? false
    } catch (error) {
      if (epoch === identity && sequence === instanceRead && !(error instanceof HttpError))
        console.error('加载告警历史失败:', error)
    } finally {
      if (epoch === identity && sequence === instanceRead) loading.value = false
    }
  }
  const loadMore = () => void loadInstances(true)
  const replaceItem = (value: AlarmInstanceResponse) => {
    items.value = items.value.map((item) => (item.id === value.id ? value : item))
    if (selected.value?.id === value.id) selected.value = value
  }
  const acknowledge = async (row: AlarmInstanceResponse) => {
    if (!projectId.value || !row.id || row.version == null) return
    const epoch = identity,
      id = projectId.value
    try {
      const value = await fetchAcknowledgeAlarm(id, row.id, { version: row.version })
      if (epoch !== identity) return
      replaceItem(value)
      ElMessage.success('告警已确认，条件状态保持不变')
    } catch (error) {
      if (epoch === identity && !(error instanceof HttpError)) console.error('确认告警失败:', error)
    }
  }
  const clear = async (row: AlarmInstanceResponse) => {
    if (!projectId.value || !row.id || row.version == null) return
    const epoch = identity,
      id = projectId.value
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
      if (epoch !== identity) return
      const value = await fetchClearAlarm(id, row.id, { version: row.version })
      if (epoch !== identity) return
      replaceItem(value)
      ElMessage.success('告警已人工清除')
    } catch (error) {
      if (
        epoch === identity &&
        error !== 'cancel' &&
        error !== 'close' &&
        !(error instanceof HttpError)
      ) {
        console.error('人工清除告警失败:', error)
      }
    }
  }
  const loadEvents = async (append = false) => {
    if (!projectId.value || !selected.value?.id || eventsLoading.value) return
    const epoch = identity,
      sequence = ++eventRead,
      id = projectId.value,
      instanceId = selected.value.id
    eventsLoading.value = true
    eventsError.value = false
    try {
      const page = await fetchAlarmEvents(
        id,
        instanceId,
        append ? eventsNextCursor.value : undefined
      )
      if (
        epoch !== identity ||
        sequence !== eventRead ||
        selected.value?.id !== instanceId ||
        !eventsVisible.value
      )
        return
      events.value = append ? [...events.value, ...(page.items ?? [])] : (page.items ?? [])
      eventsNextCursor.value = page.nextCursor ?? undefined
      eventsHasMore.value = page.hasMore ?? false
    } catch (error) {
      if (epoch === identity && sequence === eventRead && eventsVisible.value) {
        eventsError.value = true
        if (!(error instanceof HttpError)) console.error('加载告警事件失败:', error)
      }
    } finally {
      if (epoch === identity && sequence === eventRead) eventsLoading.value = false
    }
  }
  const refreshDetails = async () => {
    if (!projectId.value || !selectedId.value || !eventsVisible.value) return
    const epoch = identity,
      sequence = ++detailRead,
      id = projectId.value,
      instanceId = selectedId.value
    eventRead++
    selected.value = undefined
    detailLoading.value = true
    detailError.value = false
    eventsError.value = false
    eventsLoading.value = false
    events.value = []
    eventsNextCursor.value = undefined
    eventsHasMore.value = false
    const current = () =>
      epoch === identity &&
      sequence === detailRead &&
      selectedId.value === instanceId &&
      eventsVisible.value
    try {
      const value = await fetchAlarmInstance(id, instanceId)
      if (!current()) return
      selected.value = value
      detailLoading.value = false
      replaceItem(value)
      // 摘要与事件是两次独立读取，不承诺后端事务快照。
      await loadEvents()
    } catch (error) {
      if (!current()) return
      detailError.value = true
      if (!(error instanceof HttpError)) console.error('加载告警摘要失败:', error)
    } finally {
      if (current()) detailLoading.value = false
    }
  }
  const resetDetails = () => {
    detailRead++
    eventRead++
    selectedId.value = undefined
    selected.value = undefined
    detailLoading.value = false
    detailError.value = false
    eventsError.value = false
    eventsLoading.value = false
    events.value = []
    eventsNextCursor.value = undefined
    eventsHasMore.value = false
  }
  const openEvents = (row: AlarmInstanceResponse) => {
    if (!row.id) return
    selectedId.value = row.id
    eventsVisible.value = true
    void refreshDetails()
  }
  const loadMoreEvents = () => void loadEvents(true)

  watch(
    [projectId, () => userStore.info.userId, () => userStore.info.tenantId, currentIdentityEpoch],
    () => {
      identity++
      instanceRead++
      eventRead++
      loading.value = false
      items.value = []
      nextCursor.value = undefined
      hasMore.value = false
      eventsVisible.value = false
      resetDetails()
      void loadInstances()
    },
    { immediate: true, flush: 'sync' }
  )
  watch(
    eventsVisible,
    (visible) => {
      if (!visible) {
        resetDetails()
      }
    },
    { flush: 'sync' }
  )
  onBeforeUnmount(() => {
    identity++
    instanceRead++
    eventRead++
    detailRead++
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

  .alarm-events__toolbar {
    display: flex;
    gap: 12px;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 12px;
    color: var(--art-text-gray-500);
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
