<template>
  <section v-loading="loading" class="device-capability console-fragment">
    <div class="device-capability__toolbar">
      <span>{{ descriptions[kind] }}</span>
    </div>
    <ElAlert v-if="error" :title="error" type="warning" :closable="false" show-icon />
    <template v-else-if="loaded">
      <template v-if="kind === 'diagnostics' && diagnostics">
        <ElDescriptions :column="2" border>
          <ElDescriptionsItem
            v-for="field in diagnosticFields"
            :key="field.prop"
            :label="field.label"
          >
            {{ cell(diagnostics, field) }}
          </ElDescriptionsItem>
        </ElDescriptions>
      </template>
      <template v-else>
        <p class="console-description" v-if="kind === 'topology' && device.gatewayId"
          >所属网关 ID：{{ device.gatewayId }}</p
        >
        <ElTable :data="rows" row-key="id">
          <ElTableColumn
            v-for="column in columns[kind]"
            :key="column.prop"
            :label="column.label"
            :min-width="column.width ?? 150"
            show-overflow-tooltip
          >
            <template #default="{ row }">{{ cell(row, column) }}</template>
          </ElTableColumn>
          <template #empty><ElEmpty :description="emptyTexts[kind]" /></template>
        </ElTable>
        <div v-if="kind === 'alarms' || kind === 'ota'" class="device-capability__pagination">
          <div class="console-actions">
            <ElButton :disabled="loading || !pageCursor" @click="load()">首页</ElButton>
            <ElButton :disabled="loading || !hasMore || !nextCursor" @click="load(nextCursor)"
              >下一页</ElButton
            >
          </div>
        </div>
      </template>
    </template>
  </section>
</template>

<script setup lang="ts">
  import {
    fetchDeviceCredentials,
    fetchDeviceAccessDiagnostics,
    type DeviceResponse,
    type DeviceAccessDiagnosticsResponse
  } from '@/api/device'
  import { fetchOtaDeviceJobs } from '@/api/ota'
  import { fetchDeviceTopologies } from '@/api/device-topology'
  import { fetchModbusPoints } from '@/api/modbus-point'
  import { fetchBindingMetadata, createDesignerReadScope } from '@/api/dashboard-binding'
  import { fetchDesignerAlarms } from '@/api/dashboard-alarms'
  import type { DesignerReadScope } from '@/api/designer-read-scope'
  import { currentIdentityEpoch } from '@/utils/http/identity-scope'
  import { formatTime } from '@/utils/time'
  import { jobStatusLabel } from '@/features/ota/job-model'

  export type DeviceCapability =
    | 'credentials'
    | 'alarms'
    | 'ota'
    | 'topology'
    | 'modbus'
    | 'diagnostics'
  interface Column {
    prop: string
    label: string
    width?: number
    time?: boolean
  }
  const props = defineProps<{ projectId: string; device: DeviceResponse; kind: DeviceCapability }>()
  const loading = ref(false)
  const loaded = ref(false)
  const error = ref('')
  const rows = shallowRef<object[]>([])
  const diagnostics = ref<DeviceAccessDiagnosticsResponse>()
  const pageCursor = ref<string>()
  const nextCursor = ref<string>()
  const hasMore = ref(false)
  let generation = 0
  let readScope: DesignerReadScope | undefined

  const descriptions: Record<DeviceCapability, string> = {
    credentials: '当前设备的连接凭据（不包含明文密钥）',
    alarms: '当前设备的活动与历史告警',
    ota: '当前设备的固件升级作业',
    topology: '当前设备的网关与子设备关系',
    modbus: '当前网关的 Modbus 点位配置',
    diagnostics: '当前设备的接入与会话诊断'
  }
  const emptyTexts: Record<DeviceCapability, string> = {
    credentials: '当前设备没有凭据',
    alarms: '当前设备没有告警记录',
    ota: '当前设备没有升级作业',
    topology: '当前设备没有挂载子设备',
    modbus: '当前网关没有 Modbus 点位',
    diagnostics: '暂无接入诊断'
  }
  const columns: Record<DeviceCapability, Column[]> = {
    credentials: [
      { prop: 'displayName', label: '名称' },
      { prop: 'authType', label: '凭据类型' },
      { prop: 'createdAt', label: '创建时间', time: true }
    ],
    alarms: [
      { prop: 'alarmType', label: '告警类型' },
      { prop: 'severity', label: '严重度' },
      { prop: 'conditionState', label: '条件状态' },
      { prop: 'ackState', label: '确认状态' },
      { prop: 'activatedAt', label: '激活时间', time: true },
      { prop: 'clearedAt', label: '清除时间', time: true }
    ],
    ota: [
      { prop: 'id', label: '作业 ID', width: 260 },
      { prop: 'status', label: '状态' },
      { prop: 'batchNumber', label: '批次' },
      { prop: 'attemptNo', label: '尝试次数' },
      { prop: 'failureCode', label: '失败代码' },
      { prop: 'firstDispatchedAt', label: '首次下发时间', time: true },
      { prop: 'deadlineAt', label: '截止时间', time: true }
    ],
    topology: [
      { prop: 'subDeviceId', label: '子设备 ID', width: 260 },
      { prop: 'onlineStatus', label: '在线状态' },
      { prop: 'bindSource', label: '绑定来源' },
      { prop: 'boundAt', label: '绑定时间', time: true }
    ],
    modbus: [
      { prop: 'subDeviceId', label: '子设备 ID', width: 260 },
      { prop: 'propertyKey', label: '属性标识' },
      { prop: 'slaveAddress', label: '从站地址' },
      { prop: 'functionCode', label: '功能码' },
      { prop: 'registerAddress', label: '寄存器地址' },
      { prop: 'dataType', label: '数据类型' },
      { prop: 'pollingIntervalMs', label: '轮询周期（ms）' },
      { prop: 'status', label: '发布状态' }
    ],
    diagnostics: []
  }
  const diagnosticFields: Column[] = [
    { prop: 'protocol', label: '接入协议' },
    { prop: 'enabled', label: '接入启用' },
    { prop: 'online', label: '在线状态' },
    { prop: 'state', label: '会话状态' },
    { prop: 'configVersion', label: '配置版本' },
    { prop: 'generation', label: '会话代次' },
    { prop: 'connectedAt', label: '连接时间', time: true },
    { prop: 'lastSeenAt', label: '最近可见时间', time: true },
    { prop: 'lastAuthenticatedAt', label: '最近认证时间', time: true },
    { prop: 'lastActivityAt', label: '最近活动时间', time: true },
    { prop: 'lastDisconnectReason', label: '最近断开原因' },
    { prop: 'lastDisconnectedAt', label: '最近断开时间', time: true }
  ]
  const labels: Record<string, string> = {
    CRITICAL: '严重',
    MAJOR: '主要',
    MINOR: '次要',
    WARNING: '警告',
    INFO: '提示',
    PENDING: '等待持续',
    ACTIVE: '活动',
    CLEARED: '已清除',
    UNACKNOWLEDGED: '未确认',
    ACKNOWLEDGED: '已确认',
    ACCESS_TOKEN: '设备密钥',
    UNKNOWN: '未知',
    ONLINE: '在线',
    OFFLINE: '离线',
    CONTROL_PLANE: '控制台绑定',
    LEGACY_BACKFILL: '历史迁移',
    GATEWAY_REPORTED: '网关上报',
    DRAFT: '草稿',
    PUBLISHED: '已发布'
  }
  const cell = (row: object, column: Column) => {
    const value: unknown = Reflect.get(row, column.prop)
    if (value === undefined || value === null || value === '') return '—'
    if (column.time && typeof value === 'string') return formatTime(value)
    if (typeof value === 'boolean') return value ? '是' : '否'
    if (props.kind === 'ota' && column.prop === 'status' && typeof value === 'string')
      return jobStatusLabel(value)
    return labels[String(value)] ?? String(value)
  }
  const load = async (cursor?: string) => {
    const token = ++generation
    const identity = currentIdentityEpoch()
    readScope?.close()
    const project = props.projectId
    const device = props.device.id
    if (!project || !device) return
    const current = () =>
      token === generation &&
      identity === currentIdentityEpoch() &&
      project === props.projectId &&
      device === props.device.id
    loading.value = true
    loaded.value = false
    error.value = ''
    rows.value = []
    diagnostics.value = undefined
    nextCursor.value = undefined
    hasMore.value = false
    const scope = props.kind === 'alarms' ? createDesignerReadScope() : undefined
    readScope = scope
    try {
      if (props.kind === 'credentials') {
        const result = await fetchDeviceCredentials(project, device)
        if (current()) rows.value = result
      } else if (props.kind === 'ota') {
        const result = await fetchOtaDeviceJobs(project, device, { cursor, limit: 50 })
        if (current()) {
          rows.value = result.items ?? []
          nextCursor.value = result.nextCursor ?? undefined
          hasMore.value = result.hasMore ?? false
        }
      } else if (props.kind === 'topology') {
        const result = props.device.gatewayId ? [] : await fetchDeviceTopologies(project, device)
        if (current()) rows.value = result
      } else if (props.kind === 'modbus') {
        const result = await fetchModbusPoints(project, device)
        if (current()) rows.value = result
      } else if (props.kind === 'diagnostics') {
        const result = await fetchDeviceAccessDiagnostics(project, device)
        if (current()) diagnostics.value = result
      } else if (scope) {
        const metadata = await fetchBindingMetadata(project, device, scope)
        if (!current()) return
        const result = await fetchDesignerAlarms(
          project,
          {
            queryId: 'device-detail-alarms',
            devices: [{ deviceId: device, expectedModelVersionId: metadata.model.versionId }],
            conditionStates: ['PENDING', 'ACTIVE', 'CLEARED'],
            ackStates: ['UNACKNOWLEDGED', 'ACKNOWLEDGED'],
            severities: ['CRITICAL', 'MAJOR', 'MINOR', 'WARNING', 'INFO'],
            limit: 50
          },
          'device-detail',
          cursor,
          scope
        )
        if (!current()) return
        if (result.status !== 'READY') throw new Error('设备模型已变化，请刷新重试')
        rows.value = [...result.items]
        nextCursor.value = result.nextCursor ?? undefined
        hasMore.value = result.hasMore
      }
      if (current()) {
        pageCursor.value = cursor
        loaded.value = true
      }
    } catch (cause) {
      if (current()) error.value = cause instanceof Error ? cause.message : '读取失败，请重试'
    } finally {
      scope?.close()
      if (current()) loading.value = false
    }
  }
  watch(
    () => [props.projectId, props.device.id, props.kind],
    () => void load(),
    { immediate: true }
  )
  onUnmounted(() => {
    generation += 1
    readScope?.close()
  })
</script>

<style scoped>
  .device-capability__toolbar {
    display: flex;
    gap: 16px;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 16px;
  }
  .device-capability__toolbar > span {
    color: var(--el-text-color-secondary);
  }
  .device-capability__pagination {
    display: flex;
    gap: 8px;
    justify-content: flex-end;
    margin-top: 16px;
  }
</style>
