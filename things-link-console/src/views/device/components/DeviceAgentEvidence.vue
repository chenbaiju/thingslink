<template>
  <section class="agent-evidence" data-testid="agent-evidence">
    <h4>诊断证据</h4>
    <p class="console-description"
      >查看设备事实与来源状态。读取证据不会调用外部模型；模型分析需另行确认。</p
    >
    <ElAlert v-if="!readable" type="info" :closable="false" title="请选择当前可访问项目内的设备" />
    <template v-else>
      <div class="console-toolbar">
        <ElButton :disabled="busy" @click="loadCatalog">加载属性目录</ElButton>
        <ElSelect
          v-model="selected"
          multiple
          :multiple-limit="10"
          :disabled="busy || !metadata"
          placeholder="选择 1 至 10 个属性"
          aria-label="证据属性"
        >
          <ElOption v-for="key in keys" :key="key" :value="key" :label="key" />
        </ElSelect>
        <ElButton :disabled="!canRead" :loading="busy" @click="read">读取证据</ElButton>
      </div>
      <ElAlert v-if="error" type="error" :closable="false" :title="error" />
      <p v-if="metadata && !keys.length">当前物模型没有可读取的属性。</p>
      <template v-if="snapshot">
        <ElDescriptions :column="1" border>
          <ElDescriptionsItem label="采集区间"
            >{{ snapshot.collectionStartedAt }} 至
            {{ snapshot.collectionFinishedAt }}</ElDescriptionsItem
          >
          <ElDescriptionsItem label="物模型版本">{{ snapshot.modelVersionId }}</ElDescriptionsItem>
          <ElDescriptionsItem label="设备状态">{{
            deviceStatusLabel(snapshot.device.status)
          }}</ElDescriptionsItem>
          <ElDescriptionsItem label="最后上线">{{
            snapshot.device.lastOnlineAt ?? '从未上线'
          }}</ElDescriptionsItem>
          <ElDescriptionsItem label="设备读取时间">{{ snapshot.device.readAt }}</ElDescriptionsItem>
          <ElDescriptionsItem label="告警状态">{{
            snapshot.alarmSummary.state === 'ACTIVE' ? '有活动告警' : '无活动告警'
          }}</ElDescriptionsItem>
          <ElDescriptionsItem label="告警观测时间">{{
            snapshot.alarmSummary.observedAt
          }}</ElDescriptionsItem>
        </ElDescriptions>
        <p class="console-description"
          >各来源读取时间可能不同；属性值仅供浏览器展示，不能代替实时观测或模型分析输入。</p
        >
        <ElTable :data="snapshot.properties" row-key="key">
          <ElTableColumn prop="key" label="属性键" min-width="140" />
          <ElTableColumn label="来源状态" min-width="130">
            <template #default="{ row }">{{ availability[row.availability] }}</template>
          </ElTableColumn>
          <ElTableColumn label="值" min-width="160">
            <template #default="{ row }"
              ><span class="agent-evidence__value">{{ displayValue(row) }}</span></template
            >
          </ElTableColumn>
          <ElTableColumn label="上报时间" min-width="210">
            <template #default="{ row }">{{ row.occurredAt ?? '未提供' }}</template>
          </ElTableColumn>
          <ElTableColumn label="上报修订" min-width="140">
            <template #default="{ row }">{{ row.reportedRevision ?? '未提供' }}</template>
          </ElTableColumn>
          <ElTableColumn label="来源模型版本" min-width="240">
            <template #default="{ row }">{{ row.sourceModelVersionId ?? '未提供' }}</template>
          </ElTableColumn>
          <ElTableColumn prop="readAt" label="属性读取时间" min-width="210" />
        </ElTable>
      </template>
      <p v-else-if="!error">{{
        busy ? '正在读取…' : '加载属性目录并选择属性后，手动读取证据。'
      }}</p>
    </template>
    <DeviceAgentAnalysis
      :project-id="projectId"
      :device-id="deviceId"
      :model-version-id="metadata?.model.versionId ?? ''"
      :property-keys="selected"
      :parent-busy="busy"
    />
    <DevicePersonalRecords
      :project-id="projectId"
      :device-id="deviceId"
      :model-version-id="metadata?.model.versionId ?? ''"
      :property-keys="selected"
      :parent-busy="busy"
    />
    <DeviceEvidenceTools
      :project-id="projectId"
      :device-id="deviceId"
      :model-version-id="metadata?.model.versionId ?? ''"
      :property-keys="
        metadata?.properties.filter((p) => p.dataType === 'NUMBER').map((p) => p.key) ?? []
      "
      :parent-busy="busy"
    />
  </section>
</template>

<script setup lang="ts">
  import { computed, onBeforeUnmount, ref, watch } from 'vue'
  import DevicePersonalRecords from './DevicePersonalRecords.vue'
  import DeviceEvidenceTools from './DeviceEvidenceTools.vue'
  import DeviceAgentAnalysis from './DeviceAgentAnalysis.vue'
  import { useUserStore } from '@/store/modules/user'
  import { currentIdentityEpoch } from '@/utils/http/identity-scope'
  import { deviceStatusLabel } from '@/utils/deviceStatus'
  import {
    createDesignerReadScope,
    fetchBindingMetadata,
    type BindingMetadata
  } from '@/api/dashboard-binding'
  import { readDeviceEvidence, type DeviceEvidenceSnapshot } from '@/api/assistant-evidence'

  const props = defineProps<{ projectId: string; deviceId: string }>()
  const user = useUserStore()
  const metadata = ref<BindingMetadata>()
  const selected = ref<string[]>([])
  const snapshot = ref<DeviceEvidenceSnapshot>()
  const busy = ref(false)
  const error = ref('')
  const keys = computed(() => metadata.value?.properties.map((p) => p.key) ?? [])
  const readable = computed(() =>
    Boolean(
      user.isLogin &&
        user.info.userId &&
        user.info.tenantId &&
        props.deviceId &&
        props.projectId &&
        user.info.currentProjectId === props.projectId &&
        user.info.roles?.some((role) => ['OWNER', 'ADMIN', 'OPERATOR', 'VIEWER'].includes(role))
    )
  )
  const validSelection = computed(
    () =>
      selected.value.length >= 1 &&
      selected.value.length <= 10 &&
      new Set(selected.value).size === selected.value.length &&
      selected.value.every((key) => keys.value.includes(key))
  )
  const canRead = computed(
    () => readable.value && !busy.value && Boolean(metadata.value) && validSelection.value
  )
  const availability: Record<string, string> = {
    PRESENT: '有来源记录',
    MISSING: '未上报',
    SOURCE_UNKNOWN: '来源未知',
    MODEL_MISMATCH: '来源模型不一致'
  }
  let generation = 0
  let controller: AbortController | undefined
  let catalogScope: ReturnType<typeof createDesignerReadScope> | undefined

  function cancel() {
    ++generation
    controller?.abort()
    catalogScope?.close()
    controller = catalogScope = undefined
    busy.value = false
    snapshot.value = undefined
    error.value = ''
  }
  function begin() {
    cancel()
    const run = generation
    const identity = currentIdentityEpoch()
    busy.value = true
    return () => run === generation && identity === currentIdentityEpoch() && readable.value
  }
  async function loadCatalog() {
    if (!readable.value || busy.value) return
    metadata.value = undefined
    selected.value = []
    const current = begin()
    const scope = createDesignerReadScope()
    catalogScope = scope
    try {
      const result = await fetchBindingMetadata(props.projectId, props.deviceId, scope)
      if (!current()) return
      if (!result.properties.every((p) => /^[A-Za-z0-9_-]{1,64}$/.test(p.key)))
        throw new Error('INVALID_KEYS')
      metadata.value = result
    } catch {
      if (current()) error.value = '属性目录不可用，请确认设备物模型后手动重新加载。'
    } finally {
      scope.close()
      if (catalogScope === scope) catalogScope = undefined
      if (current()) busy.value = false
    }
  }
  const time = (value: unknown) => typeof value === 'string' && Number.isFinite(Date.parse(value))
  function matches(value: DeviceEvidenceSnapshot, version: string, requested: string[]) {
    return (
      value?.schemaVersion === 1 &&
      value.projectId === props.projectId &&
      value.deviceId === props.deviceId &&
      value.modelVersionId === version &&
      time(value.collectionStartedAt) &&
      time(value.collectionFinishedAt) &&
      Date.parse(value.collectionStartedAt) <= Date.parse(value.collectionFinishedAt) &&
      typeof value.device?.status === 'string' &&
      time(value.device.readAt) &&
      (value.device.lastOnlineAt === null || time(value.device.lastOnlineAt)) &&
      ['ACTIVE', 'NORMAL'].includes(value.alarmSummary?.state) &&
      time(value.alarmSummary.observedAt) &&
      Array.isArray(value.properties) &&
      value.properties.length === requested.length &&
      new Set(value.properties.map((p) => p?.key)).size === requested.length &&
      value.properties.every(
        (p) =>
          p &&
          requested.includes(p.key) &&
          Object.hasOwn(availability, p.availability) &&
          Object.hasOwn(p, 'value') &&
          time(p.readAt) &&
          (p.occurredAt === null || time(p.occurredAt)) &&
          (p.reportedRevision === null ||
            (typeof p.reportedRevision === 'string' &&
              /^(0|[1-9][0-9]{0,18})$/.test(p.reportedRevision))) &&
          (p.sourceModelVersionId === null ||
            (typeof p.sourceModelVersionId === 'string' &&
              /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(
                p.sourceModelVersionId
              ))) &&
          (p.availability === 'PRESENT'
            ? p.sourceModelVersionId === version &&
              time(p.occurredAt) &&
              p.reportedRevision !== null
            : p.value === null) &&
          (p.availability !== 'SOURCE_UNKNOWN' || p.sourceModelVersionId === null) &&
          (p.availability !== 'MODEL_MISMATCH' ||
            (p.sourceModelVersionId !== null && p.sourceModelVersionId !== version)) &&
          (p.availability !== 'MISSING' ||
            (p.occurredAt === null &&
              p.reportedRevision === null &&
              p.sourceModelVersionId === null))
      )
    )
  }
  async function read() {
    if (!canRead.value || !metadata.value) return
    const version = metadata.value.model.versionId
    const requested = [...selected.value]
    const current = begin()
    controller = new AbortController()
    try {
      const result = await readDeviceEvidence(
        props.projectId,
        props.deviceId,
        version,
        requested,
        controller.signal
      )
      if (!current()) return
      if (!matches(result, version, requested)) throw new Error('INVALID_SNAPSHOT')
      snapshot.value = result
    } catch (failure) {
      if (!current()) return
      const code = (failure as { code?: number } | null)?.code
      if (code === 409 || code === 10009) {
        metadata.value = undefined
        selected.value = []
      }
      error.value =
        code === 409 || code === 10009
          ? '设备物模型已变化，请重新加载属性目录后读取。'
          : '证据读取不可用，请确认当前权限和设备后手动重试。'
    } finally {
      if (current()) busy.value = false
    }
  }
  function displayValue(row: DeviceEvidenceSnapshot['properties'][number]) {
    if (row.availability !== 'PRESENT') return '不可用'
    const text = JSON.stringify(row.value)
    return text?.length > 2048 ? `${text.slice(0, 2048)}…（显示已截断）` : text
  }
  watch(
    () => [
      props.projectId,
      props.deviceId,
      user.isLogin,
      user.info.userId,
      user.info.tenantId,
      user.info.currentProjectId,
      user.info.roles?.join(','),
      user.accessToken
    ],
    () => {
      cancel()
      metadata.value = undefined
      selected.value = []
    },
    { flush: 'sync' }
  )
  watch(selected, cancel, { flush: 'sync', deep: true })
  onBeforeUnmount(cancel)
</script>

<style scoped lang="scss">
  .agent-evidence {
    display: grid;
    gap: 12px;
    .el-select {
      width: min(100%, 380px);
    }
    &__value {
      overflow-wrap: anywhere;
      white-space: pre-wrap;
    }
  }
</style>
