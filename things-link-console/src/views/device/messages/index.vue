<template>
  <div class="console-page message-logs console-page--single-panel">
    <ElAlert class="message-logs__notice" type="info" :closable="false" show-icon>
      <template #title>日志仅保留报文摘要</template>
      原始报文不会提供给浏览器；摘要最多保留 256 个字符，详细长度以“字节数”为准。
    </ElAlert>

    <ElCard class="console-list-filter" shadow="never">
      <MessageLogFilterForm
        v-model="filter"
        :devices="deviceOptions"
        :devices-loading="devicesLoading"
        @search="search"
        @reset="resetFilter"
        @device-search="searchDevices"
        @device-scroll="onDevicePopupScroll"
      />
    </ElCard>

    <ElCard class="console-list-data console-page__main-panel" shadow="never">
      <ElTable v-loading="loading" :data="items" row-key="id">
        <ElTableColumn label="发生时间" width="180">
          <template #default="{ row }">{{ formatTime(row.ts) }}</template>
        </ElTableColumn>
        <ElTableColumn label="设备" min-width="170" show-overflow-tooltip>
          <template #default="{ row }">{{ deviceName(row.deviceId) }}</template>
        </ElTableColumn>
        <ElTableColumn label="方向" width="82">
          <template #default="{ row }">
            <ElTag :type="row.direction === 'UP' ? 'success' : 'warning'" size="small">
              {{ row.direction === 'UP' ? '上行' : '下行' }}
            </ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn show-overflow-tooltip label="协议" width="82" prop="protocol" />
        <ElTableColumn label="消息类型" width="120">
          <template #default="{ row }">{{ row.messageType || '—' }}</template>
        </ElTableColumn>
        <ElTableColumn label="Topic / 路径" min-width="220" prop="topic" show-overflow-tooltip />
        <ElTableColumn label="报文摘要" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">
            <ElButton link type="primary" @click="openDetail(row)">
              {{ row.payloadSummary || '查看详情' }}
            </ElButton>
          </template>
        </ElTableColumn>
        <ElTableColumn label="标记" width="132">
          <template #default="{ row }">
            <ElTag v-if="row.truncated" type="warning" size="small" class="message-logs__tag"
              >已截断</ElTag
            >
            <ElTag v-if="row.sampled" type="info" size="small" class="message-logs__tag"
              >采样</ElTag
            >
            <span v-if="!row.truncated && !row.sampled">—</span>
          </template>
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
              :disabled="!row.deviceId"
              @click="openDiagnostics(row.deviceId)"
              label="连接诊断"
              icon="ri:stethoscope-line"
            />
          </template>
        </ElTableColumn>
        <ElTableColumn label="字节数" width="92" align="right">
          <template #default="{ row }">{{ row.rawBytes ?? 0 }}</template>
        </ElTableColumn>
        <ElTableColumn label="处理错误" min-width="125" prop="errorCode" show-overflow-tooltip>
          <template #default="{ row }">{{ row.errorCode || '—' }}</template>
        </ElTableColumn>
        <ElTableColumn label="Trace ID" min-width="160" prop="traceId" show-overflow-tooltip />
        <template #empty>
          <ElEmpty description="当前筛选条件下没有消息日志" />
        </template>
      </ElTable>

      <div v-if="hasMore" class="message-logs__more">
        <ElButton :loading="loading" type="primary" text @click="loadMore">加载更多</ElButton>
      </div>
    </ElCard>

    <ElDialog
      class="console-dialog"
      v-model="detailVisible"
      title="消息详情"
      width="760px"
      destroy-on-close
    >
      <ElAlert type="info" :closable="false" show-icon>
        凭据类字段由服务端整字段移除；截断与采样标记如实呈现，摘要不代表完整报文。
      </ElAlert>
      <ElRadioGroup v-model="detailFormat" class="message-logs__format" @change="reloadDetail">
        <ElRadioButton value="JSON">JSON</ElRadioButton>
        <ElRadioButton value="HEX">HEX</ElRadioButton>
      </ElRadioGroup>
      <ElDescriptions :column="2" border size="small">
        <ElDescriptionsItem label="消息类型">{{ detail?.messageType || '—' }}</ElDescriptionsItem>
        <ElDescriptionsItem label="方向">
          {{ detail?.direction === 'UP' ? '上行' : '下行' }}
        </ElDescriptionsItem>
        <ElDescriptionsItem label="受理时刻">{{
          formatTime(detail?.acceptedAt)
        }}</ElDescriptionsItem>
        <ElDescriptionsItem label="处理完成">{{
          formatTime(detail?.processedAt)
        }}</ElDescriptionsItem>
        <ElDescriptionsItem label="投递时刻">{{
          formatTime(detail?.deliveredAt)
        }}</ElDescriptionsItem>
        <ElDescriptionsItem label="回复时刻">{{
          formatTime(detail?.repliedAt)
        }}</ElDescriptionsItem>
        <ElDescriptionsItem label="错误码">{{ detail?.errorCode || '—' }}</ElDescriptionsItem>
        <ElDescriptionsItem label="原始字节数">{{ detail?.rawBytes ?? 0 }}</ElDescriptionsItem>
      </ElDescriptions>
      <pre class="message-logs__summary">{{
        detailFormat === 'HEX' ? detail?.payloadHex || '—' : detail?.payloadSummary || '—'
      }}</pre>
      <template #footer
        ><ElButton type="primary" @click="detailVisible = false">关闭</ElButton></template
      >
    </ElDialog>

    <ElDialog
      class="console-dialog"
      v-model="diagnosticsVisible"
      title="接入连接诊断"
      width="620px"
      destroy-on-close
    >
      <ElDescriptions v-if="diagnostics" :column="2" border size="small">
        <ElDescriptionsItem label="协议">{{ diagnostics.protocol }}</ElDescriptionsItem>
        <ElDescriptionsItem label="配置版本">{{ diagnostics.configVersion }}</ElDescriptionsItem>
        <ElDescriptionsItem label="状态">{{ stateText(diagnostics.state) }}</ElDescriptionsItem>
        <ElDescriptionsItem label="会话代次">{{ diagnostics.generation }}</ElDescriptionsItem>
        <ElDescriptionsItem label="最近认证">{{
          formatTime(diagnostics.lastAuthenticatedAt)
        }}</ElDescriptionsItem>
        <ElDescriptionsItem label="最近活动">{{
          formatTime(diagnostics.lastActivityAt)
        }}</ElDescriptionsItem>
        <ElDescriptionsItem label="断开原因">{{
          diagnostics.lastDisconnectReason || '—'
        }}</ElDescriptionsItem>
        <ElDescriptionsItem label="归属实例">{{
          diagnostics.ownerInstanceHash || '—'
        }}</ElDescriptionsItem>
      </ElDescriptions>
      <template #footer
        ><ElButton type="primary" @click="diagnosticsVisible = false">关闭</ElButton></template
      >
    </ElDialog>
  </div>
</template>

<script setup lang="ts">
  import ConsoleTableAction from '@/components/ConsoleTableAction.vue'

  import { formatTime } from '@/utils/time'
  import {
    fetchDeviceAccessDiagnostics,
    fetchMessageLogDetail,
    fetchProjectMessages,
    type DeviceAccessDiagnosticsResponse,
    type DeviceResponse,
    type MessageLogDetailResponse,
    type MessageLogResponse
  } from '@/api/device'
  import { usePagedDeviceCatalog } from '@/composables/usePagedDeviceCatalog'
  import { useUserStore } from '@/store/modules/user'
  import { HttpError } from '@/utils/http/error'
  import MessageLogFilterForm, { type MessageLogFilterModel } from './MessageLogFilterForm.vue'

  defineOptions({ name: 'DeviceMessages' })

  const userStore = useUserStore()
  const projectId = computed(() => userStore.info.currentProjectId ?? '')
  const loading = ref(false)
  const items = ref<MessageLogResponse[]>([])
  const {
    devices,
    devicesLoading,
    loadDevices,
    searchDevices,
    ensureDevices,
    onDevicePopupScroll
  } = usePagedDeviceCatalog(projectId)
  const nextCursor = ref<string>()
  const hasMore = ref(false)
  const detailVisible = ref(false)
  const detailFormat = ref<'JSON' | 'HEX'>('JSON')
  const detail = ref<MessageLogDetailResponse>()
  const diagnosticsVisible = ref(false)
  const diagnostics = ref<DeviceAccessDiagnosticsResponse>()
  const selectedLogId = ref('')
  const selectedDeviceId = ref('')

  const defaultRange = (): [Date, Date] => {
    const to = new Date()
    const from = new Date(to.getTime() - 24 * 60 * 60 * 1000)
    return [from, to]
  }
  const filter = ref<MessageLogFilterModel>({
    deviceId: '',
    direction: '',
    messageType: '',
    timeRange: defaultRange(),
    traceId: ''
  })
  const deviceOptions = computed(() =>
    devices.value
      .filter((device): device is DeviceResponse & { id: string } => Boolean(device.id))
      .map((device) => ({
        id: device.id,
        name: device.name ?? '未命名设备',
        deviceKey: device.deviceKey ?? '—'
      }))
  )
  const deviceNameMap = computed(
    () =>
      new Map(
        deviceOptions.value.map((device) => [device.id, `${device.name}（${device.deviceKey}）`])
      )
  )
  const deviceName = (deviceId?: string) =>
    (deviceId ? deviceNameMap.value.get(deviceId) : undefined) ?? deviceId ?? '—'

  const query = (cursor?: string) => ({
    deviceId: filter.value.deviceId || undefined,
    direction: filter.value.direction || undefined,
    from: filter.value.timeRange[0].toISOString(),
    to: filter.value.timeRange[1].toISOString(),
    traceId: filter.value.traceId || undefined,
    messageType: filter.value.messageType || undefined,
    cursor,
    limit: 50
  })
  const loadMessages = async (append = false) => {
    if (!projectId.value) return
    loading.value = true
    try {
      const page = await fetchProjectMessages(
        projectId.value,
        query(append ? nextCursor.value : undefined)
      )
      items.value = append ? [...items.value, ...(page.items ?? [])] : (page.items ?? [])
      await ensureDevices((page.items ?? []).map((item) => item.deviceId))
      nextCursor.value = page.nextCursor ?? undefined
      hasMore.value = page.hasMore ?? false
    } catch (error) {
      if (!(error instanceof HttpError)) console.error('加载消息日志失败:', error)
    } finally {
      loading.value = false
    }
  }
  const search = () => {
    void loadMessages()
  }
  const resetFilter = () => {
    filter.value = {
      deviceId: '',
      direction: '',
      messageType: '',
      timeRange: defaultRange(),
      traceId: ''
    }
    void loadMessages()
  }
  const loadMore = () => void loadMessages(true)
  const loadDetail = async () => {
    if (!projectId.value || !selectedDeviceId.value || !selectedLogId.value) return
    try {
      detail.value = await fetchMessageLogDetail(
        projectId.value,
        selectedDeviceId.value,
        selectedLogId.value,
        detailFormat.value
      )
    } catch (error) {
      if (!(error instanceof HttpError)) console.error('加载消息详情失败:', error)
    }
  }
  const openDetail = (row: MessageLogResponse) => {
    selectedLogId.value = String(row.id)
    selectedDeviceId.value = String(row.deviceId ?? '')
    detailFormat.value = 'JSON'
    detail.value = undefined
    detailVisible.value = true
    void loadDetail()
  }
  const reloadDetail = () => void loadDetail()
  const openDiagnostics = async (deviceId?: string) => {
    if (!projectId.value || !deviceId) return
    diagnostics.value = undefined
    diagnosticsVisible.value = true
    try {
      diagnostics.value = await fetchDeviceAccessDiagnostics(projectId.value, deviceId)
    } catch (error) {
      if (!(error instanceof HttpError)) console.error('加载接入诊断失败:', error)
    }
  }
  const stateText = (state?: string) =>
    state === 'CONNECTED' ? '连接在线' : state === 'LAST_ACTIVITY' ? '最近活动内' : '离线'
  onMounted(async () => {
    await Promise.allSettled([loadDevices(), loadMessages()])
  })
</script>

<style lang="scss" scoped>
  .message-logs {
    padding: 10px;

    &__header {
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

    &__format {
      margin: 12px 0;
    }

    &__tag {
      margin-right: 4px;
    }

    &__summary {
      max-height: 420px;
      padding: 12px;
      margin: 16px 0 0;
      overflow: auto;
      color: var(--art-text-gray-800);
      overflow-wrap: anywhere;
      white-space: pre-wrap;
      background: var(--art-bg-color);
      border-radius: 4px;
    }
  }
</style>
