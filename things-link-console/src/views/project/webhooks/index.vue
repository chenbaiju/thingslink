<template>
  <div class="console-page webhooks-page">
    <ConsoleWorkspaceHeader
      project-style
      title="Webhook"
      description="管理事件订阅与投递，轮换签名密钥；响应不确定时先查询原操作结果。"
      :links="[
        { label: 'API Key', path: '/project/api-keys', permission: 'integration:manage' },
        { label: '用量与项目设置', path: '/project/settings', permission: 'quota:read' }
      ]"
    >
      <template #leading-actions>
        <ElButton
          v-if="allowed"
          type="primary"
          :icon="Plus"
          :disabled="busy || !!pending"
          @click="openForm()"
        >
          创建订阅
        </ElButton>
      </template>
    </ConsoleWorkspaceHeader>
    <ElCard v-if="!allowed" shadow="never">
      <ElAlert title="请选择有集成管理权限的项目" :closable="false" />
    </ElCard>
    <template v-else>
      <ElCard shadow="never">
        <ElDivider content-position="left">事件订阅</ElDivider>
        <ElAlert v-if="error" :title="error" type="error" :closable="false" />
        <ElTable :data="items" row-key="id" v-loading="busy">
          <ElTableColumn show-overflow-tooltip prop="name" label="名称" /><ElTableColumn
            show-overflow-tooltip
            prop="targetUrl"
            label="目标 HTTPS"
            min-width="220"
          />
          <ElTableColumn show-overflow-tooltip prop="status" label="状态" /><ElTableColumn
            show-overflow-tooltip
            prop="revision"
            label="修订"
          />
          <ElTableColumn class-name="console-table-actions-cell" label="操作" min-width="224"
            ><template #default="{ row }">
              <ConsoleTableAction
                :disabled="busy || !!pending || row.status === 'REVOKED'"
                @click="openForm(row)"
                label="编辑"
                icon="ri:pencil-line"
              />
              <ConsoleTableAction
                :disabled="busy || !!pending || row.status !== 'ACTIVE'"
                @click="change(row, 'pause')"
                label="暂停"
                icon="ri:pause-circle-line"
              />
              <ConsoleTableAction
                :disabled="busy || !!pending || row.status !== 'PAUSED'"
                @click="change(row, 'resume')"
                label="恢复"
                icon="ri:play-circle-line"
              />
              <ConsoleTableAction
                :disabled="busy || !!pending || row.status === 'REVOKED'"
                @click="change(row, 'rotate')"
                label="轮换"
                icon="ri:key-2-line"
              />
              <ConsoleTableAction
                type="danger"
                :disabled="busy || !!pending || row.status === 'REVOKED'"
                @click="change(row, 'revoke')"
                label="撤销"
                icon="ri:close-circle-line"
              /> </template
          ></ElTableColumn>
        </ElTable>
        <ElButton v-if="next" :disabled="busy" @click="load(true)">更多订阅</ElButton>
      </ElCard>
      <ElCard shadow="never">
        <ElDivider content-position="left">查询原操作</ElDivider>
        <ElAlert
          type="info"
          show-icon
          :closable="false"
          title="响应丢失时先查询。查询不恢复秘密，秘密丢失请显式轮换。结束本地尝试不会取消服务器操作。"
        />
        <p class="console-description" v-if="pending"
          >待确认操作：<code data-testid="pending-operation">{{ pending }}</code></p
        >
        <div class="console-toolbar webhooks-recovery">
          <ElSelect v-model="recoveryKind" aria-label="操作类别" :disabled="busy || !!pending"
            ><ElOption value="subscription" label="订阅操作" /><ElOption
              value="delivery"
              label="交付恢复"
          /></ElSelect>
          <ElInput
            v-model="recoveryId"
            aria-label="原操作 ID"
            placeholder="输入原操作 ID"
            :disabled="busy || !!pending"
          />
          <ElButton :disabled="busy || !(pending || recoveryId)" @click="recoverOperation"
            >查询操作结果</ElButton
          >
          <ElButton v-if="pending" :disabled="busy" @click="discard">结束本地尝试</ElButton>
        </div>
        <ElAlert
          v-if="recovered"
          data-testid="recovered-operation"
          :title="recovered"
          :closable="false"
        />
      </ElCard>
      <ElCard class="webhooks-section" shadow="never">
        <ElDivider content-position="left">投递与尝试</ElDivider>
        <ElAlert
          type="info"
          show-icon
          :closable="false"
          title="终态和拒绝明细保留至少七天。列表是实时分页；找不到明细不代表从未发生，请结合原业务接口查询。"
        />
        <ConsoleFilterBar
          :items="[{ key: 'field0', label: '投递状态' }]"
          :show-expand="false"
          :show-reset="false"
          :show-search="false"
        >
          <template #field0
            ><ElSelect
              v-model="deliveryStatus"
              aria-label="投递状态"
              :disabled="busy"
              @change="loadDeliveries()"
              ><ElOption value="" label="全部状态" /><ElOption
                v-for="value in statuses"
                :key="value"
                :value="value"
                :label="value" /></ElSelect
          ></template>
        </ConsoleFilterBar>
        <ElTable :data="deliveryItems" row-key="id">
          <ElTableColumn
            show-overflow-tooltip
            prop="id"
            label="交付 ID"
            min-width="260"
          /><ElTableColumn show-overflow-tooltip prop="eventType" label="事件" /><ElTableColumn
            show-overflow-tooltip
            prop="status"
            label="状态"
          /><ElTableColumn show-overflow-tooltip prop="attempts" label="累计尝试" /><ElTableColumn
            show-overflow-tooltip
            prop="round"
            label="轮次"
          />
          <ElTableColumn class-name="console-table-actions-cell" min-width="104" label="操作"
            ><template #default="{ row }"
              ><ConsoleTableAction
                :disabled="busy"
                @click="showDetail(row)"
                label="详情"
                icon="ri:eye-line" /><ConsoleTableAction
                :disabled="busy || !!pending || row.status !== 'DEAD' || row.round >= 3"
                @click="recoverDelivery(row)"
                label="恢复投递"
                icon="ri:restart-line" /></template
          ></ElTableColumn>
        </ElTable>
        <ElButton v-if="deliveryNext" :disabled="busy" @click="loadDeliveries(true)"
          >更多投递</ElButton
        >
      </ElCard>
      <ElCard class="webhooks-section" shadow="never">
        <ElDivider content-position="left">事件受理记录</ElDivider>
        <ConsoleFilterBar
          :items="[
            { key: 'field0', label: '事件类型' },
            { key: 'field1', label: '受理结果' }
          ]"
          :show-expand="false"
          :show-reset="false"
          :show-search="false"
        >
          <template #field0
            ><ElSelect
              v-model="eventType"
              aria-label="事件类型"
              :disabled="busy"
              @change="loadEvents()"
              ><ElOption
                v-for="value in eventTypes"
                :key="value"
                :value="value"
                :label="value" /></ElSelect
          ></template>
          <template #field1
            ><ElSelect
              v-model="eventResult"
              aria-label="受理结果"
              :disabled="busy"
              @change="loadEvents()"
              ><ElOption value="" label="全部结果" /><ElOption
                v-for="value in results"
                :key="value"
                :value="value"
                :label="value" /></ElSelect
          ></template>
        </ConsoleFilterBar>
        <ElTable :data="eventItems" row-key="eventId"
          ><ElTableColumn
            show-overflow-tooltip
            prop="eventId"
            label="事件 ID"
            min-width="260"
          /><ElTableColumn show-overflow-tooltip prop="result" label="受理结果" /><ElTableColumn
            min-width="190"
            show-overflow-tooltip
            prop="acceptedAt"
            label="受理时间"
          /><ElTableColumn label="冲突拒绝"
            ><template #default="{ row }">{{
              row.hasConflict ? '已登记冲突' : '无'
            }}</template></ElTableColumn
          ></ElTable
        >
        <ElButton v-if="eventNext" :disabled="busy" @click="loadEvents(true)">更多事件</ElButton>
      </ElCard>
    </template>
    <ElDialog
      class="console-dialog webhooks-dialog"
      v-model="formVisible"
      :title="target ? '编辑 Webhook' : '创建 Webhook'"
      :close-on-click-modal="false"
    >
      <ElAlert v-if="error" :title="error" type="error" :closable="false" />
      <ElForm label-position="top" @submit.prevent="save">
        <ElFormItem label="名称"
          ><ElInput v-model="name" aria-label="Webhook 名称" maxlength="80" :disabled="busy"
        /></ElFormItem>
        <ElFormItem label="目标 HTTPS"
          ><ElInput
            v-model="targetUrl"
            aria-label="目标 HTTPS"
            maxlength="2048"
            :disabled="busy"
          /><p class="form-help">禁止查询串、凭据和片段。</p></ElFormItem
        >
        <ElFormItem label="事件类型"
          ><ElCheckboxGroup v-model="selectedEvents" :disabled="busy"
            ><ElCheckbox v-for="value in eventTypes" :key="value" :value="value">{{
              value
            }}</ElCheckbox></ElCheckboxGroup
          ></ElFormItem
        >
        <ElFormItem label="设备 ID"
          ><ElInput v-model="deviceIds" type="textarea" aria-label="设备 ID" :disabled="busy" /><p
            class="form-help"
            >每行一个设备 ID；留空表示项目全部设备。</p
          ></ElFormItem
        >
      </ElForm>
      <template #footer>
        <div class="console-actions console-dialog-actions">
          <ElButton type="primary" :disabled="busy || !!pending" @click="save">保存订阅</ElButton
          ><ElButton :disabled="busy" @click="formVisible = false">关闭填写窗口</ElButton>
        </div>
      </template>
    </ElDialog>
    <ElDialog
      class="console-dialog webhooks-dialog"
      v-model="secretVisible"
      title="仅本次展示签名秘密"
      :close-on-click-modal="false"
    >
      <ElAlert
        type="info"
        show-icon
        :closable="false"
        title="请安全交给接收端。关闭后无法查询，丢失请显式轮换。"
      />
      <ElInput :model-value="secret" aria-label="签名秘密" readonly />
      <template #footer><ElButton @click="clearSecret">已保存，关闭</ElButton></template>
    </ElDialog>
    <ElDialog
      class="console-dialog webhooks-dialog"
      v-model="detailVisible"
      title="Webhook 投递详情"
    >
      <template v-if="detail"
        ><p class="console-description"
          >冻结目标：{{ detail.targetUrl }}；订阅：{{ detail.name }}；修订：{{
            detail.delivery.subscriptionRevision
          }}</p
        ><p class="console-description"
          >状态：{{ detail.delivery.status }}；原因：{{ detail.delivery.reason ?? '无' }}</p
        >
        <ElTable :data="detail.attempts"
          ><ElTableColumn show-overflow-tooltip prop="attemptNo" label="尝试号" /><ElTableColumn
            show-overflow-tooltip
            prop="round"
            label="轮次" /><ElTableColumn
            show-overflow-tooltip
            prop="result"
            label="结果" /><ElTableColumn
            show-overflow-tooltip
            prop="httpStatus"
            label="HTTP 状态" /><ElTableColumn
            show-overflow-tooltip
            prop="elapsedMillis"
            label="耗时 ms" /><ElTableColumn show-overflow-tooltip prop="reason" label="原因"
        /></ElTable>
      </template>
    </ElDialog>
  </div>
</template>
<script setup lang="ts">
  import ConsoleWorkspaceHeader from '@/components/business/ConsoleWorkspaceHeader.vue'
  import ConsoleFilterBar from '@/components/ConsoleFilterBar.vue'
  import { Plus } from '@element-plus/icons-vue'
  import ConsoleTableAction from '@/components/ConsoleTableAction.vue'

  import { computed, onBeforeUnmount, ref, watch } from 'vue'
  import { ElMessageBox } from 'element-plus'
  import { useUserStore } from '@/store/modules/user'
  import { HttpError } from '@/utils/http/error'
  import * as api from '@/api/webhook'
  defineOptions({ name: 'ProjectWebhooks' })
  const user = useUserStore()
  const project = computed(() => user.info.currentProjectId ?? '')
  const allowed = computed(
    () => user.isLogin && !!project.value && !!user.info.buttons?.includes('integration:manage')
  )
  const eventTypes = [
    'device.online',
    'device.offline',
    'device.property.report',
    'alarm.triggered',
    'alarm.recovered',
    'command.completed',
    'ota.job.completed'
  ]
  const statuses = ['READY', 'IN_FLIGHT', 'SUCCEEDED', 'DEAD', 'CANCELLED']
  const results = ['ACCEPTED', 'OVERSIZE', 'BACKLOG_FULL', 'STALE', 'QUOTA_DEGRADED']
  const items = ref<api.Subscription[]>([]),
    next = ref<string>(),
    deliveryItems = ref<api.Delivery[]>([]),
    deliveryNext = ref<string>(),
    eventItems = ref<api.Event[]>([]),
    eventNext = ref<string>()
  const busy = ref(false),
    error = ref(''),
    formVisible = ref(false),
    secretVisible = ref(false),
    detailVisible = ref(false)
  const secret = ref(''),
    pending = ref(''),
    recoveryId = ref(''),
    recoveryKind = ref<'subscription' | 'delivery'>('subscription'),
    recovered = ref('')
  const target = ref<api.Subscription>(),
    name = ref(''),
    targetUrl = ref(''),
    selectedEvents = ref<string[]>(['device.online']),
    deviceIds = ref('')
  const deliveryStatus = ref(''),
    eventType = ref('device.online'),
    eventResult = ref(''),
    detail = ref<api.Detail>()
  let generation = 0
  const storageKey = () => `tc-webhook-operation:${user.info.userId}:${project.value}`
  function clearSecret() {
    secret.value = ''
    secretVisible.value = false
  }
  function clear() {
    generation++
    clearSecret()
    busy.value = false
    error.value = ''
    formVisible.value = false
    detailVisible.value = false
    detail.value = undefined
    items.value = []
    deliveryItems.value = []
    eventItems.value = []
    next.value = undefined
    deliveryNext.value = undefined
    eventNext.value = undefined
    pending.value = ''
    recoveryId.value = ''
    recovered.value = ''
    target.value = undefined
    name.value = ''
    targetUrl.value = ''
    deviceIds.value = ''
    selectedEvents.value = ['device.online']
    deliveryStatus.value = ''
    eventType.value = 'device.online'
    eventResult.value = ''
  }
  function remember(id: string, kind: 'subscription' | 'delivery') {
    pending.value = id
    recoveryId.value = id
    recoveryKind.value = kind
    sessionStorage.setItem(storageKey(), JSON.stringify({ id, kind }))
  }
  function forget() {
    sessionStorage.removeItem(storageKey())
    pending.value = ''
  }
  function restore() {
    try {
      const value = JSON.parse(sessionStorage.getItem(storageKey()) ?? 'null')
      if (
        value &&
        /^[0-9a-f-]{36}$/.test(value.id) &&
        ['subscription', 'delivery'].includes(value.kind)
      ) {
        pending.value = value.id
        recoveryId.value = value.id
        recoveryKind.value = value.kind
      }
    } catch {
      sessionStorage.removeItem(storageKey())
    }
  }
  async function run(work: (current: () => boolean, pid: string) => Promise<void>) {
    if (!allowed.value || busy.value) return
    const epoch = generation,
      pid = project.value
    const current = () => epoch === generation && pid === project.value && allowed.value
    busy.value = true
    error.value = ''
    try {
      await work(current, pid)
    } catch (reason) {
      if (current() && reason !== 'cancel' && reason !== 'close') {
        if (reason instanceof HttpError && [80001, 401, 403, 10003, 50001].includes(reason.code))
          clear()
        else if (
          reason instanceof HttpError &&
          [10001, 10009, 10029, 80002].includes(reason.code)
        ) {
          forget()
          error.value = reason.message
        } else
          error.value = pending.value
            ? '操作结果尚未确认，请查询原操作；不要重复提交。'
            : reason instanceof Error
              ? reason.message
              : '操作失败'
      }
    } finally {
      if (epoch === generation) busy.value = false
    }
  }
  async function refresh(current: () => boolean, pid: string, more = false) {
    const page = await api.list(pid, more ? next.value : undefined)
    if (current()) {
      items.value = more ? [...items.value, ...(page.items ?? [])] : (page.items ?? [])
      next.value = page.nextCursor ?? undefined
    }
  }
  function load(more = false) {
    return run((current, pid) => refresh(current, pid, more))
  }
  function openForm(row?: api.Subscription) {
    if (!allowed.value || busy.value || pending.value) return
    clearSecret()
    recovered.value = ''
    target.value = row
    name.value = row?.name ?? ''
    targetUrl.value = row?.targetUrl ?? ''
    selectedEvents.value = [...(row?.eventTypes ?? ['device.online'])]
    deviceIds.value = row?.deviceIds.join('\n') ?? ''
    formVisible.value = true
  }
  function save() {
    return run(async (current, pid) => {
      if (pending.value) return
      if (!name.value.trim() || !targetUrl.value.trim() || !selectedEvents.value.length)
        throw new Error('请填写名称、HTTPS目标和事件类型')
      const operationId = crypto.randomUUID()
      const data: api.Create = {
        operationId,
        name: name.value.trim(),
        targetUrl: targetUrl.value.trim(),
        eventTypes: [...selectedEvents.value],
        deviceIds: deviceIds.value.split(/\s+/).filter(Boolean)
      }
      remember(operationId, 'subscription')
      const value = await api.save(pid, data, target.value)
      if (!current()) return
      forget()
      formVisible.value = false
      clearSecret()
      if (value.signingSecret) {
        secret.value = value.signingSecret
        secretVisible.value = true
      }
      await refresh(current, pid)
    })
  }
  function change(row: api.Subscription, action: api.Action) {
    return run(async (current, pid) => {
      if (pending.value) return
      await ElMessageBox.confirm(
        '此操作产生新修订，旧修订的待发交付将失效。是否继续？',
        '确认订阅操作'
      )
      if (!current()) return
      clearSecret()
      const operation = crypto.randomUUID()
      remember(operation, 'subscription')
      const value = await api.change(pid, row, action, operation)
      if (!current()) return
      forget()
      if (value.signingSecret) {
        secret.value = value.signingSecret
        secretVisible.value = true
      }
      await refresh(current, pid)
    })
  }
  function recoverOperation() {
    return run(async (current, pid) => {
      const id = pending.value || recoveryId.value.trim()
      if (!/^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(id))
        throw new Error('请输入有效的操作 ID')
      const kind = recoveryKind.value
      const value =
        kind === 'subscription'
          ? await api.operation(pid, id)
          : await api.recoveryOperation(pid, id)
      if (!current()) return
      clearSecret()
      forget()
      formVisible.value = false
      recovered.value = value.current
        ? `原操作已完成：${id}。查询不恢复秘密；需要时显式轮换。`
        : `原操作已完成：${id}，当前明细已清理。查询不恢复秘密。`
      await refresh(current, pid)
    })
  }
  function discard() {
    return run(async (current) => {
      await ElMessageBox.confirm(
        '不会取消已提交操作。请保留原操作 ID 并先核对服务器结果。',
        '结束本地尝试'
      )
      if (current()) {
        forget()
        formVisible.value = false
      }
    })
  }
  function loadDeliveries(more = false) {
    return run(async (current, pid) => {
      const page = await api.deliveries(
        pid,
        deliveryStatus.value || undefined,
        more ? deliveryNext.value : undefined
      )
      if (current()) {
        deliveryItems.value = more
          ? [...deliveryItems.value, ...(page.items ?? [])]
          : (page.items ?? [])
        deliveryNext.value = page.nextCursor ?? undefined
      }
    })
  }
  function loadEvents(more = false) {
    return run(async (current, pid) => {
      const page = await api.events(
        pid,
        eventType.value,
        eventResult.value || undefined,
        more ? eventNext.value : undefined
      )
      if (current()) {
        eventItems.value = more ? [...eventItems.value, ...(page.items ?? [])] : (page.items ?? [])
        eventNext.value = page.nextCursor ?? undefined
      }
    })
  }
  function showDetail(row: api.Delivery) {
    return run(async (current, pid) => {
      const value = await api.detail(pid, row.id)
      if (current()) {
        detail.value = value
        detailVisible.value = true
      }
    })
  }
  function recoverDelivery(row: api.Delivery) {
    return run(async (current, pid) => {
      if (pending.value) return
      await ElMessageBox.confirm(
        '按原交付身份和正文开始下一轮有限尝试，接收端必须去重。是否继续？',
        '恢复投递'
      )
      if (!current()) return
      const operation = crypto.randomUUID()
      remember(operation, 'delivery')
      await api.recover(pid, row, operation)
      if (!current()) return
      forget()
      recovered.value = `恢复已完成：${operation}`
      const page = await api.deliveries(pid, deliveryStatus.value || undefined)
      if (current()) {
        deliveryItems.value = page.items ?? []
        deliveryNext.value = page.nextCursor ?? undefined
      }
    })
  }
  watch(
    () => [project.value, allowed.value, user.info.userId, user.isLogin],
    () => {
      clear()
      if (allowed.value) {
        restore()
        void load()
      }
    },
    { immediate: true }
  )
  watch(secretVisible, (visible) => {
    if (!visible) secret.value = ''
  })
  onBeforeUnmount(clear)
</script>
<style scoped>
  .webhooks-page :deep(.workspace-header .console-actions) {
    margin-bottom: 0;
  }
  .webhooks-page :deep(.el-alert),
  .webhooks-dialog :deep(.el-alert) {
    margin-bottom: 10px;
  }
  .webhooks-page :deep(.el-alert__title),
  .webhooks-dialog :deep(.el-alert__title) {
    font-size: 12px;
    font-weight: 400;
    line-height: 20px;
  }
  .webhooks-page :deep(.el-alert__icon),
  .webhooks-dialog :deep(.el-alert__icon) {
    width: 14px;
    font-size: 14px;
  }
  .webhooks-recovery {
    display: flex;
    flex-wrap: wrap;
    gap: 10px;
    align-items: center;
  }
  .webhooks-recovery > .el-select {
    width: 180px;
  }
  .webhooks-recovery > .el-input {
    flex: 1 1 240px;
    max-width: 440px;
  }
  .webhooks-recovery > .el-button {
    flex-shrink: 0;
    margin: 0;
  }
</style>
