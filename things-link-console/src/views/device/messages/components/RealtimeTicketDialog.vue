<script setup lang="ts">
  import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
  import { useUserStore } from '@/store/modules/user'
  import { currentIdentityEpoch } from '@/utils/http/identity-scope'
  import { usePagedDeviceCatalog } from '@/composables/usePagedDeviceCatalog'
  import {
    fetchBindingMetadata,
    createDesignerReadScope,
    type BindingMetadata
  } from '@/api/dashboard-binding'
  import {
    issueRealtimeTicket,
    type TicketProtocol,
    type RealtimeTicketRequest
  } from '@/api/realtime-ticket'
  import { createTicketTool } from '@/features/realtime-ticket/model'

  const emit = defineEmits<{ close: [] }>()
  const user = useUserStore()
  const projectId = computed(() => user.info.currentProjectId ?? '')
  const allowed = computed(
    () => user.isLogin && !!projectId.value && !!user.info.buttons?.includes('device:read')
  )
  const identity = () =>
    JSON.stringify([
      projectId.value,
      user.info.userId,
      user.info.tenantId,
      currentIdentityEpoch(),
      allowed.value
    ])
  const protocol = ref<TicketProtocol>('WS')
  const deviceId = ref(''),
    propertyKeys = ref<string[]>([])
  const metadata = ref<BindingMetadata | null>(null)
  const metadataBusy = ref(false),
    readError = ref('')
  let generation = 0
  let readScope: ReturnType<typeof createDesignerReadScope> | undefined
  const catalog = usePagedDeviceCatalog(projectId)
  const { devices, devicesLoading, deviceHasMore } = catalog
  const tool = createTicketTool({
    context: identity,
    allowed: () => allowed.value && navigator.onLine,
    key: () => crypto.randomUUID(),
    issue: (body, key, signal) => issueRealtimeTicket(projectId.value, body, key, signal),
    changed: (next) => Object.assign(state, next)
  })
  const state = reactive(tool.snapshot())
  const canSubmit = computed(
    () =>
      allowed.value &&
      !state.attempted &&
      !metadataBusy.value &&
      !!metadata.value &&
      propertyKeys.value.length > 0 &&
      propertyKeys.value.length <= 50
  )
  function resetReads() {
    generation++
    readScope?.close()
    readScope = undefined
    metadata.value = null
    propertyKeys.value = []
    metadataBusy.value = false
    readError.value = ''
  }
  function close() {
    tool.clear()
    resetReads()
    emit('close')
  }
  watch(identity, close, { flush: 'sync' })
  async function search(keyword = '') {
    if (!allowed.value || state.attempted) return
    const scope = identity()
    readError.value = ''
    try {
      await catalog.searchDevices(keyword)
    } catch {
      if (scope === identity()) readError.value = '设备目录读取失败，请明确重试。'
    }
  }
  async function more() {
    if (!allowed.value || state.attempted) return
    const scope = identity()
    try {
      await catalog.loadMoreDevices()
    } catch {
      if (scope === identity()) readError.value = '下一页设备读取失败，请明确重试。'
    }
  }
  async function loadMetadata() {
    resetReads()
    if (!deviceId.value || !allowed.value || state.attempted) return
    const request = generation,
      scopeIdentity = identity(),
      selected = deviceId.value
    metadataBusy.value = true
    const scope = createDesignerReadScope()
    readScope = scope
    try {
      const result = await fetchBindingMetadata(projectId.value, selected, scope)
      if (request !== generation || scopeIdentity !== identity()) return
      metadata.value = result
      if (!result.properties.length) readError.value = '当前设备物模型没有可订阅属性。'
    } catch {
      if (request === generation && scopeIdentity === identity())
        readError.value = '当前设备不可读或物模型已变化，请重新读取。'
    } finally {
      scope.close()
      if (request === generation) metadataBusy.value = false
    }
  }
  function newIntent() {
    if (state.busy) return
    tool.clear()
    void loadMetadata()
  }
  function submit() {
    if (!canSubmit.value || !metadata.value) return
    const available = new Set(metadata.value.properties.map((property) => property.key))
    if (propertyKeys.value.some((key) => !available.has(key))) return
    const body: RealtimeTicketRequest = {
      protocol: protocol.value,
      eventTypes: ['device.property.report'],
      devices: [
        {
          deviceId: deviceId.value,
          expectedModelVersionId: metadata.value.model.versionId,
          propertyKeys: [...propertyKeys.value]
        }
      ]
    }
    void tool.submit(body)
  }
  onMounted(() => {
    void search()
    window.addEventListener('offline', close)
  })
  onBeforeUnmount(() => {
    tool.clear()
    resetReads()
    window.removeEventListener('offline', close)
  })
</script>
<template>
  <ElDialog
    class="console-dialog"
    :model-value="true"
    title="短期实时订阅票据"
    width="min(720px, 94vw)"
    :close-on-click-modal="false"
    @close="close"
  >
    <ElAlert
      class="realtime-ticket__hint"
      title="选择一个当前项目设备及 1 至 50 个属性，生成仅供调试的连接参数。票据最长 5 分钟，未使用也占用共享额度；本工具不自动连接，现有实时页面不受影响。"
      type="info"
      :closable="false"
      show-icon
    />
    <ElForm label-position="top">
      <ElFormItem label="订阅协议">
        <ElRadioGroup v-model="protocol" aria-label="订阅协议" :disabled="state.attempted">
          <ElRadioButton value="WS">WebSocket</ElRadioButton>
          <ElRadioButton value="MQTT">MQTT 3.1.1</ElRadioButton>
        </ElRadioGroup>
      </ElFormItem>
      <ElFormItem label="设备" class="realtime-ticket__field">
        <ElSelect
          v-model="deviceId"
          aria-label="订阅设备"
          filterable
          remote
          :remote-method="search"
          :loading="devicesLoading"
          :disabled="state.attempted"
          placeholder="搜索并选择设备"
          @change="loadMetadata"
        >
          <ElOption
            v-for="device in devices"
            :key="device.id"
            :value="device.id!"
            :label="`${device.name || device.deviceKey} · ${device.deviceKey}`"
          />
        </ElSelect>
        <ElButton :disabled="state.attempted || devicesLoading" @click="search()"
          >重新读取设备</ElButton
        >
        <ElButton v-if="deviceHasMore" :disabled="state.attempted || devicesLoading" @click="more"
          >下一页设备</ElButton
        >
      </ElFormItem>
      <ElFormItem label="属性范围（最多 50 个）" class="realtime-ticket__field">
        <ElSelect
          v-model="propertyKeys"
          aria-label="订阅属性"
          multiple
          filterable
          :multiple-limit="50"
          :loading="metadataBusy"
          :disabled="state.attempted || !metadata"
          placeholder="选择物模型中的属性"
        >
          <ElOption
            v-for="property in metadata?.properties ?? []"
            :key="property.key"
            :value="property.key"
            :label="`${property.key} · ${property.dataType}`"
          />
        </ElSelect>
        <ElButton :disabled="state.attempted || !deviceId || metadataBusy" @click="loadMetadata"
          >重新读取物模型</ElButton
        >
      </ElFormItem>
    </ElForm>
    <p v-if="metadata" class="console-description"
      >不可变模型版本：{{ metadata.model.versionId }} · 事件：device.property.report</p
    >
    <ElAlert
      v-if="readError || state.error"
      :title="readError || state.error"
      type="warning"
      :closable="false"
      show-icon
    />
    <section v-if="state.ticket" aria-label="本次订阅连接参数">
      <ElAlert
        title="秘密仅本次显示；关闭窗口、切换身份或项目、离线及到期均清除。关闭窗口不会撤销服务端票据。"
        type="warning"
        :closable="false"
        show-icon
      />
      <ElDescriptions :column="1" border>
        <ElDescriptionsItem label="票据 ID">{{ state.ticket.ticketId }}</ElDescriptionsItem>
        <ElDescriptionsItem label="过期时间">{{ state.ticket.expiresAt }}</ElDescriptionsItem>
        <ElDescriptionsItem label="协议">{{ state.ticket.protocol }}</ElDescriptionsItem>
        <ElDescriptionsItem v-if="state.ticket.protocol === 'WS'" label="WS endpoint">{{
          state.ticket.endpoint
        }}</ElDescriptionsItem>
        <ElDescriptionsItem v-if="state.ticket.protocol === 'WS'" label="子协议">{{
          state.ticket.subprotocol
        }}</ElDescriptionsItem>
        <template v-else>
          <ElDescriptionsItem label="MQTT username">{{ state.ticket.username }}</ElDescriptionsItem>
          <ElDescriptionsItem label="MQTT clientId">{{ state.ticket.clientId }}</ElDescriptionsItem>
          <ElDescriptionsItem label="订阅 Topic">{{ state.ticket.topic }}</ElDescriptionsItem>
        </template>
      </ElDescriptions>
      <ElInput
        :model-value="state.ticket.credential"
        type="password"
        show-password
        readonly
        autocomplete="off"
        aria-label="本次短期秘密"
      />
      <ElAlert
        v-if="state.ticket.protocol === 'WS'"
        class="console-hint"
        type="info"
        show-icon
        :closable="false"
        >通过 Sec-WebSocket-Protocol 同时发送上述子协议与短期秘密，不得将秘密放入
        URL。连接地址须使用部署环境允许的安全入口。</ElAlert
      >
      <ElAlert v-else class="console-hint" type="info" show-icon :closable="false"
        >Broker 地址请向管理员获取独立应用 Listener 配置；本接口不返回地址。password
        使用短期秘密，仅订阅上述精确 Topic，QoS 1；不能发布或使用设备 Topic。</ElAlert
      >
    </section>
    <ElAlert v-if="state.attempted" class="console-hint" type="warning" show-icon :closable="false"
      >再次签发属于新意图，旧票据仍可能有效并占用额度；系统不自动重试或恢复首次秘密。</ElAlert
    >
    <template #footer>
      <ElButton @click="close">关闭并清除</ElButton>
      <ElButton v-if="state.attempted" :disabled="state.busy" @click="newIntent"
        >明确创建新意图</ElButton
      >
      <ElButton v-else type="primary" :disabled="!canSubmit" :loading="state.busy" @click="submit"
        >签发一次性票据</ElButton
      >
    </template>
  </ElDialog>
</template>

<style scoped lang="scss">
  .realtime-ticket__hint {
    margin-bottom: 10px;
    font-size: 12px;
    line-height: 20px;

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

  .realtime-ticket__field {
    :deep(.el-form-item__content) {
      gap: 10px;
      align-items: center;
    }

    :deep(.el-select) {
      flex: 1 1 220px;
      width: auto;
      min-width: 0;
    }

    :deep(.el-button) {
      margin: 0;
    }
  }
</style>
