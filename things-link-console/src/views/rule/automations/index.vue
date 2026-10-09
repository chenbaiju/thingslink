<template>
  <div class="console-page automation-page">
    <ConsoleWorkspaceHeader
      project-style
      title="自动化"
      description="选择设备与触发条件，编排动作；保存草稿后再明确启用。"
    >
      <template #actions>
        <ElButton v-if="allowed" type="primary" :icon="Plus" :disabled="busy" @click="create">
          创建自动化
        </ElButton>
      </template>
    </ConsoleWorkspaceHeader>
    <section v-if="sourceRequested" class="console-editor-section" aria-label="来源设备">
      <p v-if="sourceLoading" role="status">正在核对来源设备…</p>
      <template v-else-if="sourceDevice">
        <p class="console-description"
          >来源设备：{{
            sourceDevice.name || sourceDevice.deviceKey || sourceDevice.id
          }}。点击创建后预选此设备，保存前仍可调整。</p
        >
      </template>
      <template v-else-if="sourceError">
        <ElAlert :title="sourceError" type="warning" :closable="false" show-icon />
        <ElButton @click="reloadSource">重试来源设备</ElButton>
      </template>
    </section>
    <ElCard v-if="!allowed" shadow="never"
      ><ElAlert
        title="需要 OWNER 或 ADMIN 的规则管理权限"
        type="warning"
        :closable="false"
        show-icon
    /></ElCard>
    <template v-else>
      <ElCard class="console-list-filter" shadow="never"
        ><ConsoleFilterBar
          :items="[
            { key: 'field0', label: '名称' },
            { key: 'field1', label: '状态' }
          ]"
          :show-expand="false"
          :show-reset="false"
          :show-search="true"
          @search="load(false)"
          :loading="busy"
        >
          <template #field0
            ><ElInput v-model="filters.name" placeholder="按名称搜索" aria-label="按名称搜索"
          /></template>
          <template #field1
            ><ElSelect v-model="filters.status" clearable placeholder="状态" aria-label="状态"
              ><ElOption
                v-for="status in ['DRAFT', 'ACTIVE', 'PAUSED']"
                :key="status"
                :value="status"
                :label="
                  ({ DRAFT: '草稿', ACTIVE: '已启用', PAUSED: '已暂停' } as Record<string, string>)[
                    status
                  ] || status
                " /></ElSelect
          ></template> </ConsoleFilterBar
      ></ElCard>
      <ElCard class="console-list-data" shadow="never"
        ><ElTable :data="items" row-key="id" v-loading="busy">
          <ElTableColumn show-overflow-tooltip prop="name" label="名称" /><ElTableColumn
            show-overflow-tooltip
            prop="status"
            label="状态"
            ><template #default="{ row }"
              ><ConsoleStatusTag :status="row.status" /></template></ElTableColumn
          ><ElTableColumn
            min-width="190"
            show-overflow-tooltip
            prop="activeVersionId"
            label="活动版本"
          />
          <ElTableColumn show-overflow-tooltip label="下次执行"
            ><template #default="{ row }">{{ nextDescription(row) }}</template></ElTableColumn
          >
          <ElTableColumn show-overflow-tooltip label="更新时间"
            ><template #default="{ row }">{{
              row.updatedAt ? new Date(row.updatedAt).toLocaleString() : '—'
            }}</template></ElTableColumn
          >
          <ElTableColumn class-name="console-table-actions-cell" min-width="64" label="操作"
            ><template #default="{ row }"
              ><ConsoleTableAction
                :disabled="busy"
                @click="open(row)"
                label="管理"
                icon="ri:settings-3-line" /></template
          ></ElTableColumn>
        </ElTable>
        <ElButton v-if="next" :loading="busy" @click="load(true)">加载更多</ElButton></ElCard
      >
    </template>
    <ElDialog
      class="console-dialog"
      v-model="visible"
      title="自动化版本管理"
      width="850px"
      destroy-on-close
      :close-on-click-modal="false"
      :show-close="!busy"
      :close-on-press-escape="!busy"
    >
      <p class="console-description" v-if="selected"
        >状态 {{ selected.status }} · CAS {{ selected.version }} · 活动版本
        {{ selected.activeVersionId ?? '无' }}</p
      >
      <ElForm label-position="top">
        <ElFormItem class="automation-form-field" label="名称"
          ><ElInput v-model="form.name" aria-label="自动化名称" maxlength="128" :disabled="busy"
        /></ElFormItem>
        <ElFormItem class="automation-form-field" label="说明"
          ><ElInput
            v-model="form.description"
            aria-label="自动化说明"
            maxlength="512"
            :disabled="busy"
        /></ElFormItem>
        <ElFormItem label="触发方式">
          <ElSelect v-model="form.triggerType" aria-label="触发方式" :disabled="busy">
            <ElOption label="设备属性上报" value="PROPERTY_REPORTED" />
            <ElOption label="一次性时间" value="ONE_SHOT" />
            <ElOption label="周期时间" value="CRON" />
          </ElSelect>
        </ElFormItem>
        <template v-if="form.triggerType === 'ONE_SHOT'">
          <ElFormItem :label="`执行时间（${browserTimezone}）`">
            <ElInput
              v-model="runAt"
              type="datetime-local"
              aria-label="一次性执行时间"
              :disabled="busy"
            />
          </ElFormItem>
          <ElAlert class="console-hint" type="warning" show-icon :closable="false"
            >必须为未来时间；已受理或已过期版本不能再次发布，请保存新的未来版本。</ElAlert
          >
        </template>
        <template v-if="form.triggerType === 'CRON'">
          <ElFormItem label="周期表达式（秒 分 时 日 月 周，每分钟最多一次）">
            <ElInput v-model="cronExpression" aria-label="周期表达式" :disabled="busy" />
          </ElFormItem>
          <ElFormItem :label="`IANA 时区（留空采用项目时区 ${projectTimezone || '保存时确定'}）`">
            <ElInput
              v-model="timezone"
              aria-label="调度时区"
              placeholder="例如 Asia/Shanghai"
              :disabled="busy"
            />
          </ElFormItem>
          <ElAlert class="console-hint" type="info" show-icon :closable="false"
            >时区随版本冻结。恢复不补暂停期间；停机只补一个已持久化的发生点。夏令时缺失时间跳过，重复时间按两个
            UTC 发生点执行。</ElAlert
          >
        </template>
        <ElFormItem
          v-if="form.triggerType !== 'PROPERTY_REPORTED'"
          label="显式输入对象（JSON，最多16KiB）"
        >
          <ElInput v-model="payload" type="textarea" aria-label="时间触发输入" :disabled="busy" />
        </ElFormItem>
        <p class="console-description" v-if="selected" data-testid="automation-next-fire"
          >下次执行：{{ nextDescription(selected) }}</p
        >
        <ElDivider content-position="left">目标设备</ElDivider>
        <ElInput
          v-model="deviceKeyword"
          aria-label="搜索设备"
          placeholder="设备名称或标识"
          :disabled="busy"
        />
        <ElButton :disabled="busy" @click="searchDevices(false)">搜索设备</ElButton>
        <ElSelect
          v-model="deviceId"
          aria-label="目标设备"
          placeholder="选择目标设备"
          :disabled="busy"
        >
          <ElOption
            v-for="device in devices"
            :key="device.id"
            :value="device.id!"
            :label="device.name ?? device.id"
          />
        </ElSelect>
        <ElButton v-if="deviceNext" :disabled="busy" @click="searchDevices(true)"
          >更多设备</ElButton
        >
        <ElAlert
          class="automation-form-notice"
          :title="
            (form.triggerType === 'PROPERTY_REPORTED'
              ? '每次属性上报独立判断，条件持续成立可能重复执行；'
              : '') + '属性设置仅支持 MQTT，其他协议将拒绝整组动作。'
          "
          type="info"
          :closable="false"
          show-icon
        />
        <ElDivider content-position="left">条件（全部满足；为空时总是执行）</ElDivider>
        <RuleNodeEditor
          :key="`conditions-${editorKey}`"
          v-model="conditions"
          :descriptors="catalog.conditions ?? []"
          :disabled="busy"
          @valid="conditionsValid = $event"
        />
        <ElDivider content-position="left">动作（按顺序受理）</ElDivider>
        <RuleNodeEditor
          :key="editorKey"
          v-model="actions"
          :descriptors="catalog.actions ?? []"
          :disabled="busy"
          @valid="editorValid = $event"
        />
        <ElButton
          type="primary"
          :disabled="busy || !editorValid || !conditionsValid || !form.name?.trim() || !deviceId"
          @click="save"
          >保存新版本</ElButton
        >
      </ElForm>
      <template v-if="selected">
        <ElDivider content-position="left">不可变历史（最新版本在前）</ElDivider>
        <ElTable :data="versions" row-key="id"
          ><ElTableColumn show-overflow-tooltip prop="versionNumber" label="版本号" /><ElTableColumn
            show-overflow-tooltip
            prop="id"
            label="版本 ID"
          />
          <ElTableColumn class-name="console-table-actions-cell" min-width="104" label="操作"
            ><template #default="{ row }"
              ><ConsoleTableAction
                :disabled="busy"
                @click="copyVersion(row)"
                label="载入编辑"
                icon="ri:edit-box-line" /><ConsoleTableAction
                :disabled="busy"
                @click="activate(row)"
                label="发布此版本"
                icon="ri:send-plane-line" /></template
          ></ElTableColumn>
        </ElTable>
        <div class="console-actions">
          <ElButton v-if="historyNext" :disabled="busy" @click="moreVersions">更多历史</ElButton>
          <ElButton :disabled="busy || selected.status !== 'ACTIVE'" @click="pause"
            >暂停自动化</ElButton
          ><ElButton type="danger" :disabled="busy" @click="remove">删除自动化</ElButton>
        </div>
        <RouterLink to="/rule/executions">查看执行记录及投递状态</RouterLink>
      </template>
      <ElAlert v-if="error" :title="error" type="error" :closable="false" show-icon />
    </ElDialog>
  </div>
</template>
<script setup lang="ts">
  import { useWorkspaceDeviceContext } from '@/composables/useWorkspaceDeviceContext'
  import ConsoleWorkspaceHeader from '@/components/business/ConsoleWorkspaceHeader.vue'
  import ConsoleFilterBar from '@/components/ConsoleFilterBar.vue'
  import { Plus } from '@element-plus/icons-vue'
  import ConsoleTableAction from '@/components/ConsoleTableAction.vue'

  import ConsoleStatusTag from '@/components/ConsoleStatusTag.vue'
  import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
  import { ElMessageBox } from 'element-plus'
  import { useUserStore } from '@/store/modules/user'
  import * as api from '@/api/automation-management'
  import { fetchProjects } from '@/api/project'
  import { fetchSearchDevices, type DeviceResponse } from '@/api/device'
  import type {
    ManagedAutomation,
    AutomationVersion,
    AutomationWrite
  } from '@/api/automation-management'
  import type { RuleAction, RuleCatalog } from '@/api/rule-management'
  import RuleNodeEditor from '../components/RuleNodeEditor.vue'
  import { HttpError } from '@/utils/http/error'

  const {
    device: sourceDevice,
    loading: sourceLoading,
    error: sourceError,
    requested: sourceRequested,
    reload: reloadSource
  } = useWorkspaceDeviceContext('rule:manage')

  defineOptions({ name: 'RuleAutomations' })
  const user = useUserStore()
  const project = computed(() => user.info.currentProjectId ?? '')
  const allowed = computed(
    () => user.isLogin && !!project.value && !!user.info.buttons?.includes('rule:manage')
  )
  const items = ref<ManagedAutomation[]>([]),
    versions = ref<AutomationVersion[]>([])
  const selected = ref<ManagedAutomation>(),
    next = ref<string>(),
    historyNext = ref<string>()
  const catalog = ref<RuleCatalog>({}),
    actions = ref<RuleAction[]>([])
  const filters = reactive({ name: '', status: '' })
  const form = reactive<AutomationWrite>({
    name: '',
    description: '',
    expectedVersion: 1,
    triggerType: 'PROPERTY_REPORTED',
    triggerConfig: { deviceId: '' },
    actions: []
  })
  const visible = ref(false),
    busy = ref(false),
    editorValid = ref(true),
    editorKey = ref(0)
  const error = ref('')
  const conditions = ref<RuleAction[]>([]),
    conditionsValid = ref(true)
  const devices = ref<DeviceResponse[]>([]),
    deviceNext = ref<string>(),
    deviceId = ref(''),
    deviceKeyword = ref('')
  const runAt = ref(''),
    cronExpression = ref('0 * * * * *'),
    timezone = ref(''),
    payload = ref('{}'),
    projectTimezone = ref('')
  const browserTimezone = Intl.DateTimeFormat().resolvedOptions().timeZone
  watch(
    sourceDevice,
    (next, previous) => {
      if (previous?.id && !next && deviceId.value === previous.id) deviceId.value = ''
    },
    { flush: 'sync' }
  )
  function resetTrigger() {
    form.triggerType = 'PROPERTY_REPORTED'
    form.triggerConfig = { deviceId: '' }
    runAt.value = ''
    cronExpression.value = '0 * * * * *'
    timezone.value = ''
    payload.value = '{}'
  }
  function nextDescription(row: ManagedAutomation) {
    if (!row.nextFireAt) return '暂无未来计划（草稿、暂停或已结束）'
    const value = new Date(row.nextFireAt),
      zone = row.scheduleTimezone || 'UTC'
    const parts = Object.fromEntries(
      new Intl.DateTimeFormat('zh-CN', {
        timeZone: zone,
        year: 'numeric',
        month: '2-digit',
        day: '2-digit',
        hour: '2-digit',
        minute: '2-digit',
        second: '2-digit',
        hourCycle: 'h23'
      })
        .formatToParts(value)
        .map(({ type, value }) => [type, value])
    )
    return `${parts.year}-${parts.month}-${parts.day} ${parts.hour}:${parts.minute}:${parts.second}`
  }
  function triggerConfig(): AutomationWrite['triggerConfig'] {
    if (form.triggerType === 'PROPERTY_REPORTED') return { deviceId: deviceId.value }
    let input: unknown
    try {
      input = JSON.parse(payload.value)
    } catch {
      throw new Error('时间触发输入必须是合法 JSON 对象')
    }
    if (!input || typeof input !== 'object' || Array.isArray(input))
      throw new Error('时间触发输入必须是对象')
    if (new TextEncoder().encode(JSON.stringify(input)).length > 16384)
      throw new Error('时间触发输入不能超过16KiB')
    if (form.triggerType === 'ONE_SHOT') {
      const at = new Date(runAt.value)
      if (!runAt.value || !Number.isFinite(at.getTime()))
        throw new Error('请选择有效的未来执行时间')
      return {
        deviceId: deviceId.value,
        runAt: at.toISOString(),
        payload: input as Record<string, unknown>
      }
    }
    return {
      deviceId: deviceId.value,
      cronExpression: cronExpression.value,
      ...(timezone.value.trim() ? { timezone: timezone.value.trim() } : {}),
      payload: input as Record<string, unknown>
    }
  }
  let generation = 0
  function clear() {
    generation++
    visible.value = false
    items.value = []
    versions.value = []
    selected.value = undefined
    actions.value = []
    catalog.value = {}
    form.name = ''
    form.description = ''
    resetTrigger()
    projectTimezone.value = ''
    conditions.value = []
    devices.value = []
    deviceNext.value = undefined
    deviceId.value = ''
    deviceKeyword.value = ''
    error.value = ''
    next.value = undefined
    historyNext.value = undefined
    busy.value = false
    editorKey.value++
  }
  async function run(work: (current: () => boolean, projectId: string) => Promise<void>) {
    if (!allowed.value || busy.value) return
    const epoch = generation,
      pid = project.value
    busy.value = true
    error.value = ''
    const current = () => epoch === generation && pid === project.value && allowed.value
    try {
      await work(current, pid)
    } catch (reason) {
      if (current() && reason !== 'cancel' && reason !== 'close') {
        if (reason instanceof HttpError && [40050, 403, 10003, 50001].includes(reason.code)) clear()
        else error.value = reason instanceof Error ? reason.message : '操作失败，请刷新后重试'
      }
    } finally {
      if (epoch === generation) busy.value = false
    }
  }
  function load(more = false) {
    return run(async (current, pid) => {
      const page = await api.listAutomations(pid, {
        ...filters,
        status: filters.status || undefined,
        cursor: more ? next.value : undefined
      })
      if (current()) {
        items.value = more ? [...items.value, ...(page.items ?? [])] : (page.items ?? [])
        next.value = page.nextCursor ?? undefined
      }
    })
  }
  function create() {
    resetTrigger()
    selected.value = undefined
    deviceId.value = sourceDevice.value?.id ?? ''
    if (sourceDevice.value && !devices.value.some((item) => item.id === sourceDevice.value?.id))
      devices.value.push(sourceDevice.value)
    versions.value = []
    historyNext.value = undefined
    actions.value = []
    conditions.value = []
    Object.assign(form, { name: '', description: '', expectedVersion: 1 })
    editorKey.value++
    visible.value = true
  }
  function copyVersion(version: AutomationVersion) {
    resetTrigger()
    form.triggerType = version.triggerType ?? 'PROPERTY_REPORTED'
    const config = version.triggerConfig
    if (config && 'runAt' in config && config.runAt) {
      const at = new Date(config.runAt)
      runAt.value = new Date(at.getTime() - at.getTimezoneOffset() * 60000)
        .toISOString()
        .slice(0, 19)
    }
    if (config && 'cronExpression' in config) cronExpression.value = config.cronExpression
    if (config && 'timezone' in config) timezone.value = config.timezone ?? ''
    if (config && 'payload' in config) payload.value = JSON.stringify(config.payload ?? {}, null, 2)
    deviceId.value =
      typeof version.triggerConfig?.deviceId === 'string' ? version.triggerConfig.deviceId : ''
    conditions.value = JSON.parse(JSON.stringify(version.conditions ?? []))
    actions.value = JSON.parse(JSON.stringify(version.actions ?? []))
    editorKey.value++
  }
  function open(row: ManagedAutomation) {
    return run(async (current, pid) => {
      const [detail, history] = await Promise.all([
        api.getAutomation(pid, row.id!),
        api.automationHistory(pid, row.id!)
      ])
      if (!current()) return
      selected.value = detail
      Object.assign(form, { name: detail.name, description: detail.description ?? '' })
      versions.value = history.items ?? []
      historyNext.value = history.nextCursor ?? undefined
      if (versions.value[0]) copyVersion(versions.value[0])
      visible.value = true
    })
  }
  function completeNodes(nodes: RuleAction[]) {
    return nodes.map(({ nodeType, config }) => {
      if (config === undefined || !nodeType) throw new Error('节点配置缺失，请显式重建合规版本')
      return { nodeType, config }
    })
  }
  function save() {
    return run(async (current, pid) => {
      const result = await api.saveAutomation(pid, selected.value?.id ?? '', {
        ...form,
        triggerType: form.triggerType,
        triggerConfig: triggerConfig(),
        expectedVersion: selected.value?.version ?? 1,
        actions: completeNodes(actions.value),
        conditions: completeNodes(conditions.value)
      })
      if (!current()) return
      selected.value = result
      const history = await api.automationHistory(pid, result.id!)
      if (current()) {
        versions.value = history.items ?? []
        historyNext.value = history.nextCursor ?? undefined
        items.value = [result, ...items.value.filter((item) => item.id !== result.id)]
      }
    })
  }
  function activate(version: AutomationVersion) {
    return run(async (current, pid) => {
      const row = selected.value!
      await ElMessageBox.confirm(
        `将规则“${row.name}”发布为版本 ${version.versionNumber}？`,
        '确认发布 / 回滚'
      )
      if (!current()) return
      const result = await api.activateAutomation(pid, row.id!, version.id!, row.version!)
      if (current()) {
        selected.value = result
        items.value = items.value.map((item) => (item.id === result.id ? result : item))
      }
    })
  }
  function pause() {
    return run(async (current, pid) => {
      const row = selected.value!
      const result = await api.pauseAutomation(pid, row.id!, row.version!)
      if (current()) {
        selected.value = result
        items.value = items.value.map((item) => (item.id === result.id ? result : item))
      }
    })
  }
  function remove() {
    return run(async (current, pid) => {
      const row = selected.value!
      await ElMessageBox.confirm(`删除自动化“${row.name}”？历史执行将保留。`, '确认删除')
      if (!current()) return
      await api.deleteAutomation(pid, row.id!, row.version!)
      if (current()) {
        visible.value = false
        items.value = items.value.filter((item) => item.id !== row.id)
        selected.value = undefined
        versions.value = []
        actions.value = []
        conditions.value = []
        devices.value = []
        deviceNext.value = undefined
        deviceId.value = ''
        deviceKeyword.value = ''
        resetTrigger()
      }
    })
  }
  function moreVersions() {
    return run(async (current, pid) => {
      const page = await api.automationHistory(pid, selected.value!.id!, historyNext.value)
      if (current()) {
        versions.value.push(...(page.items ?? []))
        historyNext.value = page.nextCursor ?? undefined
      }
    })
  }
  function searchDevices(more = false) {
    return run(async (current, pid) => {
      const page = await fetchSearchDevices(pid, {
        keyword: deviceKeyword.value || undefined,
        cursor: more ? deviceNext.value : undefined,
        limit: 50
      })
      if (current()) {
        devices.value = more ? [...devices.value, ...(page.items ?? [])] : (page.items ?? [])
        deviceNext.value = page.nextCursor ?? undefined
      }
    })
  }
  watch(
    () => [project.value, allowed.value, user.info.userId, user.isLogin],
    () => {
      clear()
      if (allowed.value)
        void run(async (current, pid) => {
          const [nodes, page, projects] = await Promise.all([
            api.ruleCatalog(pid),
            api.listAutomations(pid, {}),
            fetchProjects()
          ])
          if (current()) {
            projectTimezone.value = projects.find((item) => item.id === pid)?.timezone ?? ''
            catalog.value = nodes
            items.value = page.items ?? []
            next.value = page.nextCursor ?? undefined
          }
        })
    },
    { immediate: true }
  )
  onBeforeUnmount(clear)
</script>

<style scoped lang="scss">
  .automation-page :deep(.workspace-header > .console-actions) {
    margin-bottom: 0;
  }

  .automation-form-field :deep(.el-form-item__label) {
    height: 20px !important;
    margin-bottom: 8px;
    line-height: 20px !important;
  }

  .automation-form-notice {
    margin: 10px 0;

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
