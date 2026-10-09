<template>
  <div class="console-page alarm-page console-page--single-panel">
    <ConsoleWorkspaceHeader
      project-style
      title="告警规则"
      description="定义设备属性异常条件，再关联通知组与通知模板。"
    >
      <template #actions>
        <ElButton v-if="hasAuth('alarm:manage')" type="primary" :icon="Plus" @click="openCreate">
          创建规则
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
        <ElAlert :title="sourceError" type="warning" :closable="false" />
        <ElButton @click="reloadSource">重试来源设备</ElButton>
      </template>
    </section>
    <ElAlert
      class="alarm-page__notice"
      title="规则仅支持固定数值比较；修改规则不会改写已经产生的告警实例和事件。"
      type="info"
      :closable="false"
      show-icon
    />

    <ElCard class="console-page__main-panel" shadow="never">
      <ElTable v-loading="loading" :data="items" row-key="id">
        <ElTableColumn prop="name" label="规则名称" min-width="150" show-overflow-tooltip />
        <ElTableColumn prop="alarmType" label="告警类型" min-width="135" show-overflow-tooltip />
        <ElTableColumn label="设备" min-width="170" show-overflow-tooltip>
          <template #default="{ row }">{{ deviceName(row.deviceId) }}</template>
        </ElTableColumn>
        <ElTableColumn show-overflow-tooltip prop="propertyKey" label="属性" min-width="110" />
        <ElTableColumn label="触发条件" min-width="190">
          <template #default="{ row }">{{ conditionText(row, 'trigger') }}</template>
        </ElTableColumn>
        <ElTableColumn label="恢复条件" min-width="190">
          <template #default="{ row }">{{ conditionText(row, 'clear') }}</template>
        </ElTableColumn>
        <ElTableColumn label="严重度" width="100">
          <template #default="{ row }">
            <ElTag :type="severityTag(row.severity)">{{ severityLabel(row.severity) }}</ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="状态" width="86">
          <template #default="{ row }">
            <ElTag :type="row.enabled ? 'success' : 'info'">
              {{ row.enabled ? '已启用' : '已停用' }}
            </ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="更新时间" width="180">
          <template #default="{ row }">{{ formatTime(row.updatedAt) }}</template>
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
              @click="openBindings(row)"
              label="通知"
              icon="ri:notification-3-line"
            />
            <ConsoleTableAction
              v-if="hasAuth('alarm:manage')"
              type="primary"
              @click="openEdit(row)"
              label="编辑"
              icon="ri:pencil-line"
            />
            <ConsoleTableAction
              v-if="hasAuth('alarm:manage')"
              type="danger"
              @click="removeRule(row)"
              label="删除"
              icon="ri:delete-bin-5-line"
            />
          </template>
        </ElTableColumn>
        <template #empty><ElEmpty description="还没有告警规则" /></template>
      </ElTable>

      <div v-if="hasMore" class="alarm-page__more">
        <ElButton :loading="loading" type="primary" text @click="loadMore">加载更多</ElButton>
      </div>
    </ElCard>

    <ElDialog
      class="console-dialog"
      v-model="formVisible"
      :title="editingId ? '编辑告警规则' : '创建告警规则'"
      width="760px"
      destroy-on-close
    >
      <ElForm ref="formRef" :model="form" :rules="rules" label-position="top">
        <div class="alarm-form-grid">
          <ElFormItem label="规则名称" prop="name">
            <ElInput v-model.trim="form.name" maxlength="128" />
          </ElFormItem>
          <ElFormItem label="告警类型" prop="alarmType">
            <ElInput v-model.trim="form.alarmType" maxlength="64" placeholder="HIGH_TEMPERATURE" />
          </ElFormItem>
          <ElFormItem label="目标设备" prop="deviceId">
            <ElSelect
              v-model="form.deviceId"
              filterable
              remote
              :remote-method="searchDevices"
              :loading="devicesLoading"
              placeholder="输入名称或标识搜索设备"
              @popup-scroll="onDevicePopupScroll"
            >
              <ElOption
                v-for="device in deviceOptions"
                :key="device.id"
                :label="`${device.name}（${device.deviceKey}）`"
                :value="device.id"
              />
            </ElSelect>
          </ElFormItem>
          <ElFormItem label="数值属性标识" prop="propertyKey">
            <ElInput v-model.trim="form.propertyKey" maxlength="64" placeholder="temperature" />
          </ElFormItem>
        </div>

        <ElDivider content-position="left">持续触发条件</ElDivider>
        <div class="alarm-condition-grid">
          <ElFormItem label="比较" prop="triggerOperator">
            <ElSelect v-model="form.triggerOperator">
              <ElOption v-for="option in operatorOptions" :key="option.value" v-bind="option" />
            </ElSelect>
          </ElFormItem>
          <ElFormItem label="阈值" prop="triggerThreshold">
            <ElInputNumber v-model="form.triggerThreshold" :controls="false" />
          </ElFormItem>
          <ElFormItem label="持续秒数" prop="triggerDurationSeconds">
            <ElInputNumber v-model="form.triggerDurationSeconds" :min="0" :max="604800" />
          </ElFormItem>
        </div>

        <ElDivider content-position="left">恢复条件（回差）</ElDivider>
        <div class="alarm-condition-grid">
          <ElFormItem label="比较" prop="clearOperator">
            <ElSelect v-model="form.clearOperator">
              <ElOption v-for="option in operatorOptions" :key="option.value" v-bind="option" />
            </ElSelect>
          </ElFormItem>
          <ElFormItem label="阈值" prop="clearThreshold">
            <ElInputNumber v-model="form.clearThreshold" :controls="false" />
          </ElFormItem>
          <ElFormItem label="持续秒数" prop="clearDurationSeconds">
            <ElInputNumber v-model="form.clearDurationSeconds" :min="0" :max="604800" />
          </ElFormItem>
        </div>

        <div class="alarm-form-grid">
          <ElFormItem label="严重度" prop="severity">
            <ElSelect v-model="form.severity">
              <ElOption v-for="option in severityOptions" :key="option.value" v-bind="option" />
            </ElSelect>
          </ElFormItem>
          <ElFormItem label="启用">
            <ElSwitch v-model="form.enabled" />
          </ElFormItem>
        </div>
      </ElForm>
      <template #footer>
        <ElButton @click="formVisible = false">取消</ElButton>
        <ElButton type="primary" :loading="submitting" @click="submitRule">保存</ElButton>
      </template>
    </ElDialog>

    <ElDrawer
      v-model="bindingsVisible"
      :title="`${selectedRule?.name ?? ''} · 通知路由`"
      size="780px"
      destroy-on-close
    >
      <div class="binding-toolbar">
        <span>每条路由把此告警规则连接到一个通知组和同渠道模板。</span>
        <ElButton
          v-if="hasAuth('alarm:manage')"
          type="primary"
          :icon="Plus"
          @click="openCreateBinding"
        >
          添加路由
        </ElButton>
      </div>
      <ElTable v-loading="bindingsLoading" :data="bindings" row-key="id">
        <ElTableColumn label="通知组" min-width="170" show-overflow-tooltip>
          <template #default="{ row }">{{ groupName(row.groupId) }}</template>
        </ElTableColumn>
        <ElTableColumn label="模板" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">{{ templateName(row.templateId) }}</template>
        </ElTableColumn>
        <ElTableColumn label="渠道" width="105">
          <template #default="{ row }">{{ channelLabel(row.channel) }}</template>
        </ElTableColumn>
        <ElTableColumn label="状态" width="90">
          <template #default="{ row }">
            <ElTag :type="row.enabled ? 'success' : 'info'">
              {{ row.enabled ? '已启用' : '已停用' }}
            </ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn
          class-name="console-table-actions-cell"
          v-if="hasAuth('alarm:manage')"
          label="操作"
          width="104"
          fixed="right"
        >
          <template #default="{ row }">
            <ConsoleTableAction
              type="primary"
              @click="openEditBinding(row)"
              label="编辑"
              icon="ri:pencil-line"
            />
            <ConsoleTableAction
              type="danger"
              @click="removeBinding(row)"
              label="删除"
              icon="ri:delete-bin-5-line"
            />
          </template>
        </ElTableColumn>
        <template #empty><ElEmpty description="该规则还没有通知路由" /></template>
      </ElTable>
    </ElDrawer>

    <ElDialog
      class="console-dialog"
      v-model="bindingFormVisible"
      :title="editingBindingId ? '编辑通知路由' : '添加通知路由'"
      width="620px"
      append-to-body
      destroy-on-close
    >
      <ElForm ref="bindingFormRef" :model="bindingForm" :rules="bindingRules" label-position="top">
        <ElFormItem label="渠道" prop="channel">
          <ElRadioGroup v-model="bindingForm.channel" @change="onBindingChannelChange">
            <ElRadioButton value="EMAIL">邮件</ElRadioButton>
            <ElRadioButton value="WEBHOOK">Webhook</ElRadioButton>
          </ElRadioGroup>
        </ElFormItem>
        <ElFormItem label="通知组" prop="groupId">
          <ElSelect v-model="bindingForm.groupId" filterable placeholder="请选择通知组">
            <ElOption
              v-for="group in notificationGroups"
              :key="group.id ?? ''"
              :label="`${group.name ?? '未命名通知组'}${group.enabled ? '' : '（已停用）'}`"
              :value="group.id ?? ''"
            />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="同渠道模板" prop="templateId">
          <ElSelect v-model="bindingForm.templateId" filterable placeholder="请选择通知模板">
            <ElOption
              v-for="template in bindingTemplateOptions"
              :key="template.id ?? ''"
              :label="`${template.name ?? '未命名模板'}${template.enabled ? '' : '（已停用）'}`"
              :value="template.id ?? ''"
            />
          </ElSelect>
          <div class="form-help">这里只显示与所选渠道一致的模板。</div>
        </ElFormItem>
        <ElFormItem label="启用">
          <ElSwitch v-model="bindingForm.enabled" />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="bindingFormVisible = false">取消</ElButton>
        <ElButton type="primary" :loading="bindingSubmitting" @click="submitBinding">
          保存
        </ElButton>
      </template>
    </ElDialog>
  </div>
</template>

<script setup lang="ts">
  import { useWorkspaceDeviceContext } from '@/composables/useWorkspaceDeviceContext'
  import ConsoleWorkspaceHeader from '@/components/business/ConsoleWorkspaceHeader.vue'
  import ConsoleTableAction from '@/components/ConsoleTableAction.vue'

  import { formatTime } from '@/utils/time'
  import { Plus } from '@element-plus/icons-vue'
  import type { FormInstance, FormRules } from 'element-plus'
  import {
    fetchAlarmNotificationBindings,
    fetchAlarmNotificationGroups,
    fetchAlarmNotificationTemplates,
    fetchAlarmRules,
    fetchCreateAlarmNotificationBinding,
    fetchCreateAlarmRule,
    fetchDeleteAlarmNotificationBinding,
    fetchDeleteAlarmRule,
    fetchUpdateAlarmNotificationBinding,
    fetchUpdateAlarmRule,
    type AlarmNotificationBindingResponse,
    type AlarmNotificationGroupResponse,
    type AlarmNotificationTemplateResponse,
    type AlarmRuleResponse,
    type SaveAlarmNotificationBindingRequest,
    type SaveAlarmRuleRequest
  } from '@/api/alarm'
  import type { DeviceResponse } from '@/api/device'
  import { usePagedDeviceCatalog } from '@/composables/usePagedDeviceCatalog'
  import { useAuth } from '@/hooks/core/useAuth'
  import { useUserStore } from '@/store/modules/user'
  import { HttpError } from '@/utils/http/error'

  const {
    device: sourceDevice,
    loading: sourceLoading,
    error: sourceError,
    requested: sourceRequested,
    reload: reloadSource
  } = useWorkspaceDeviceContext('alarm:manage')

  defineOptions({ name: 'AlarmRules' })

  type Operator = NonNullable<SaveAlarmRuleRequest['triggerOperator']>
  type Severity = NonNullable<SaveAlarmRuleRequest['severity']>
  type NotificationChannel = SaveAlarmNotificationBindingRequest['channel']

  const userStore = useUserStore()
  const { hasAuth } = useAuth()
  const projectId = computed(() => userStore.info.currentProjectId ?? '')
  const loading = ref(false)
  const submitting = ref(false)
  const items = ref<AlarmRuleResponse[]>([])
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
  const formVisible = ref(false)
  const editingId = ref('')
  const formRef = ref<FormInstance>()
  const bindingsVisible = ref(false)
  const bindingsLoading = ref(false)
  const bindings = ref<AlarmNotificationBindingResponse[]>([])
  const selectedRule = ref<AlarmRuleResponse>()
  const notificationGroups = ref<AlarmNotificationGroupResponse[]>([])
  const notificationTemplates = ref<AlarmNotificationTemplateResponse[]>([])
  const bindingFormVisible = ref(false)
  const bindingSubmitting = ref(false)
  const editingBindingId = ref('')
  const bindingFormRef = ref<FormInstance>()

  const defaultForm = (): SaveAlarmRuleRequest => ({
    name: '',
    alarmType: '',
    deviceId: '',
    propertyKey: '',
    triggerOperator: 'GT',
    triggerThreshold: 0,
    triggerDurationSeconds: 0,
    clearOperator: 'LTE',
    clearThreshold: 0,
    clearDurationSeconds: 0,
    severity: 'WARNING',
    enabled: true
  })
  const form = reactive<SaveAlarmRuleRequest>(defaultForm())
  watch(
    sourceDevice,
    (next, previous) => {
      if (previous?.id && !next && form.deviceId === previous.id) form.deviceId = ''
    },
    { flush: 'sync' }
  )
  const rules: FormRules = {
    name: [{ required: true, message: '请输入规则名称', trigger: 'blur' }],
    alarmType: [{ required: true, message: '请输入告警类型', trigger: 'blur' }],
    deviceId: [{ required: true, message: '请选择目标设备', trigger: 'change' }],
    propertyKey: [
      { required: true, message: '请输入属性标识', trigger: 'blur' },
      {
        pattern: /^[A-Za-z][A-Za-z0-9_-]{0,63}$/,
        message: '属性标识须以字母开头，仅含字母、数字、下划线或连字符',
        trigger: 'blur'
      }
    ]
  }
  const defaultBindingForm = (): SaveAlarmNotificationBindingRequest => ({
    groupId: '',
    templateId: '',
    channel: 'EMAIL',
    enabled: true
  })
  const bindingForm = reactive<SaveAlarmNotificationBindingRequest>(defaultBindingForm())
  const bindingRules: FormRules = {
    channel: [{ required: true, message: '请选择渠道', trigger: 'change' }],
    groupId: [{ required: true, message: '请选择通知组', trigger: 'change' }],
    templateId: [{ required: true, message: '请选择同渠道模板', trigger: 'change' }]
  }
  const operatorOptions: Array<{ value: Operator; label: string }> = [
    { value: 'GT', label: '大于（>）' },
    { value: 'GTE', label: '大于等于（≥）' },
    { value: 'LT', label: '小于（<）' },
    { value: 'LTE', label: '小于等于（≤）' },
    { value: 'EQ', label: '等于（=）' },
    { value: 'NE', label: '不等于（≠）' }
  ]
  const severityOptions: Array<{ value: Severity; label: string }> = [
    { value: 'CRITICAL', label: '严重' },
    { value: 'MAJOR', label: '主要' },
    { value: 'MINOR', label: '次要' },
    { value: 'WARNING', label: '警告' },
    { value: 'INFO', label: '提示' }
  ]
  const deviceOptions = computed(() =>
    devices.value
      .filter((device): device is DeviceResponse & { id: string } => Boolean(device.id))
      .map((device) => ({
        id: device.id,
        name: device.name ?? '未命名设备',
        deviceKey: device.deviceKey ?? '—'
      }))
  )
  const deviceMap = computed(
    () => new Map(deviceOptions.value.map((item) => [item.id, `${item.name}（${item.deviceKey}）`]))
  )
  const groupMap = computed(
    () => new Map(notificationGroups.value.map((item) => [item.id, item.name ?? '未命名通知组']))
  )
  const templateMap = computed(
    () =>
      new Map(notificationTemplates.value.map((item) => [item.id, item.name ?? '未命名通知模板']))
  )
  const bindingTemplateOptions = computed(() =>
    notificationTemplates.value.filter((item) => item.channel === bindingForm.channel)
  )

  const deviceName = (id?: string) => (id ? deviceMap.value.get(id) : undefined) ?? id ?? '—'
  const groupName = (id?: string) => (id ? groupMap.value.get(id) : undefined) ?? id ?? '—'
  const templateName = (id?: string) => (id ? templateMap.value.get(id) : undefined) ?? id ?? '—'
  const channelLabel = (value?: string) =>
    value === 'EMAIL' ? '邮件' : value === 'WEBHOOK' ? 'Webhook' : (value ?? '—')
  const operatorLabel = (value?: string) =>
    operatorOptions.find((item) => item.value === value)?.label.replace(/（.*）/, '') ??
    value ??
    '—'
  const durationText = (seconds?: number) => (seconds ? `，持续 ${seconds} 秒` : '，立即')
  const conditionText = (row: AlarmRuleResponse, prefix: 'trigger' | 'clear') => {
    const operator = prefix === 'trigger' ? row.triggerOperator : row.clearOperator
    const threshold = prefix === 'trigger' ? row.triggerThreshold : row.clearThreshold
    const seconds = prefix === 'trigger' ? row.triggerDurationSeconds : row.clearDurationSeconds
    return `${operatorLabel(operator)} ${threshold ?? '—'}${durationText(seconds)}`
  }
  const severityLabel = (value?: string) =>
    severityOptions.find((item) => item.value === value)?.label ?? value ?? '—'
  const severityTag = (value?: string) =>
    ({ CRITICAL: 'danger', MAJOR: 'danger', MINOR: 'warning', WARNING: 'warning', INFO: 'info' })[
      value ?? ''
    ] as 'danger' | 'warning' | 'info' | undefined

  let listGeneration = 0
  const loadRules = async (append = false) => {
    if (!projectId.value || !userStore.isLogin || !hasAuth('alarm:read')) return
    const generation = ++listGeneration
    const pid = projectId.value
    const current = () =>
      generation === listGeneration &&
      pid === projectId.value &&
      userStore.isLogin &&
      hasAuth('alarm:read')
    loading.value = true
    try {
      const page = await fetchAlarmRules(pid, append ? nextCursor.value : undefined)
      if (!current()) return
      items.value = append ? [...items.value, ...(page.items ?? [])] : (page.items ?? [])
      await ensureDevices((page.items ?? []).map((item) => item.deviceId))
      if (!current()) return
      nextCursor.value = page.nextCursor ?? undefined
      hasMore.value = page.hasMore ?? false
    } catch (error) {
      if (current() && !(error instanceof HttpError)) console.error('加载告警规则失败:', error)
    } finally {
      if (current()) loading.value = false
    }
  }
  const loadMore = () => void loadRules(true)
  const resetForm = () => Object.assign(form, defaultForm())
  const openCreate = () => {
    editingId.value = ''
    resetForm()
    form.deviceId = sourceDevice.value?.id ?? ''
    if (sourceDevice.value && !devices.value.some((item) => item.id === sourceDevice.value?.id))
      devices.value.push(sourceDevice.value)
    formVisible.value = true
  }
  const openEdit = (row: AlarmRuleResponse) => {
    editingId.value = row.id ?? ''
    Object.assign(form, {
      name: row.name ?? '',
      alarmType: row.alarmType ?? '',
      deviceId: row.deviceId ?? '',
      propertyKey: row.propertyKey ?? '',
      triggerOperator: row.triggerOperator ?? 'GT',
      triggerThreshold: row.triggerThreshold ?? 0,
      triggerDurationSeconds: row.triggerDurationSeconds ?? 0,
      clearOperator: row.clearOperator ?? 'LTE',
      clearThreshold: row.clearThreshold ?? 0,
      clearDurationSeconds: row.clearDurationSeconds ?? 0,
      severity: row.severity ?? 'WARNING',
      enabled: row.enabled ?? true,
      version: row.version
    })
    formVisible.value = true
  }
  const submitRule = async () => {
    if (!projectId.value || !(await formRef.value?.validate().catch(() => false))) return
    submitting.value = true
    try {
      const body: SaveAlarmRuleRequest = { ...form }
      if (editingId.value) {
        await fetchUpdateAlarmRule(projectId.value, editingId.value, body)
      } else {
        delete body.version
        await fetchCreateAlarmRule(projectId.value, body)
      }
      ElMessage.success(editingId.value ? '告警规则已更新' : '告警规则已创建')
      formVisible.value = false
      await loadRules()
    } catch (error) {
      if (!(error instanceof HttpError)) console.error('保存告警规则失败:', error)
    } finally {
      submitting.value = false
    }
  }
  const removeRule = async (row: AlarmRuleResponse) => {
    if (!projectId.value || !row.id || row.version == null) return
    try {
      await ElMessageBox.confirm(
        `确定删除规则“${row.name ?? ''}”吗？历史告警仍会保留。`,
        '删除规则',
        {
          type: 'warning',
          confirmButtonText: '删除',
          cancelButtonText: '取消'
        }
      )
      await fetchDeleteAlarmRule(projectId.value, row.id, row.version)
      ElMessage.success('告警规则已删除')
      await loadRules()
    } catch (error) {
      if (error !== 'cancel' && error !== 'close' && !(error instanceof HttpError)) {
        console.error('删除告警规则失败:', error)
      }
    }
  }

  const loadAllNotificationGroups = async () => {
    const values: AlarmNotificationGroupResponse[] = []
    let cursor: string | undefined
    do {
      const page = await fetchAlarmNotificationGroups(projectId.value, cursor, 100)
      values.push(...(page.items ?? []))
      cursor = page.hasMore ? (page.nextCursor ?? undefined) : undefined
    } while (cursor)
    notificationGroups.value = values
  }
  const loadAllNotificationTemplates = async () => {
    const values: AlarmNotificationTemplateResponse[] = []
    let cursor: string | undefined
    do {
      const page = await fetchAlarmNotificationTemplates(projectId.value, cursor, 100)
      values.push(...(page.items ?? []))
      cursor = page.hasMore ? (page.nextCursor ?? undefined) : undefined
    } while (cursor)
    notificationTemplates.value = values
  }
  const loadBindings = async () => {
    if (!projectId.value || !selectedRule.value?.id) return
    bindingsLoading.value = true
    try {
      const [bindingValues] = await Promise.all([
        fetchAlarmNotificationBindings(projectId.value, selectedRule.value.id),
        loadAllNotificationGroups(),
        loadAllNotificationTemplates()
      ])
      bindings.value = bindingValues
    } catch (error) {
      if (!(error instanceof HttpError)) console.error('加载告警通知路由失败:', error)
    } finally {
      bindingsLoading.value = false
    }
  }
  const openBindings = async (row: AlarmRuleResponse) => {
    selectedRule.value = row
    bindingsVisible.value = true
    await loadBindings()
  }
  const openCreateBinding = () => {
    editingBindingId.value = ''
    Object.assign(bindingForm, defaultBindingForm())
    bindingFormVisible.value = true
  }
  const openEditBinding = (row: AlarmNotificationBindingResponse) => {
    editingBindingId.value = row.id ?? ''
    Object.assign(bindingForm, {
      groupId: row.groupId ?? '',
      templateId: row.templateId ?? '',
      channel: (row.channel ?? 'EMAIL') as NotificationChannel,
      enabled: row.enabled ?? true,
      version: row.version
    })
    bindingFormVisible.value = true
  }
  const onBindingChannelChange = () => {
    if (!bindingTemplateOptions.value.some((item) => item.id === bindingForm.templateId)) {
      bindingForm.templateId = ''
    }
    void bindingFormRef.value?.validateField('templateId').catch(() => undefined)
  }
  const submitBinding = async () => {
    if (
      !projectId.value ||
      !selectedRule.value?.id ||
      !(await bindingFormRef.value?.validate().catch(() => false))
    )
      return
    bindingSubmitting.value = true
    try {
      const body: SaveAlarmNotificationBindingRequest = { ...bindingForm }
      if (editingBindingId.value) {
        await fetchUpdateAlarmNotificationBinding(projectId.value, editingBindingId.value, body)
      } else {
        delete body.version
        await fetchCreateAlarmNotificationBinding(projectId.value, selectedRule.value.id, body)
      }
      ElMessage.success(editingBindingId.value ? '通知路由已更新' : '通知路由已创建')
      bindingFormVisible.value = false
      await loadBindings()
    } catch (error) {
      if (!(error instanceof HttpError)) console.error('保存告警通知路由失败:', error)
    } finally {
      bindingSubmitting.value = false
    }
  }
  const removeBinding = async (row: AlarmNotificationBindingResponse) => {
    if (!projectId.value || !row.id || row.version == null) return
    try {
      await ElMessageBox.confirm('确定删除这条告警通知路由吗？', '删除通知路由', {
        type: 'warning',
        confirmButtonText: '删除',
        cancelButtonText: '取消'
      })
      await fetchDeleteAlarmNotificationBinding(projectId.value, row.id, row.version)
      ElMessage.success('通知路由已删除')
      await loadBindings()
    } catch (error) {
      if (error !== 'cancel' && error !== 'close' && !(error instanceof HttpError)) {
        console.error('删除告警通知路由失败:', error)
      }
    }
  }

  watch(
    () => [
      projectId.value,
      userStore.info.userId,
      userStore.isLogin,
      userStore.info.buttons?.join(',')
    ],
    () => {
      listGeneration++
      items.value = []
      devices.value = []
      nextCursor.value = undefined
      hasMore.value = false
      loading.value = false
      formVisible.value = false
      bindingsVisible.value = false
      bindingFormVisible.value = false
      selectedRule.value = undefined
      bindings.value = []
      resetForm()
      if (userStore.isLogin && projectId.value && hasAuth('alarm:read'))
        void Promise.allSettled([loadDevices(), loadRules()])
    },
    { immediate: true, flush: 'sync' }
  )
  onBeforeUnmount(() => listGeneration++)
</script>

<style scoped lang="scss">
  .alarm-page {
    padding: 10px;

    :deep(.workspace-header > .console-actions) {
      margin-bottom: 0;
    }

    &__notice {
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
    &__more {
      display: flex;
      justify-content: center;
      padding-top: 12px;
    }
  }

  .alarm-form-grid,
  .alarm-condition-grid {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 0 18px;
  }

  .binding-toolbar {
    display: flex;
    gap: 10px;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 10px;
    color: var(--el-text-color-secondary);
  }

  .form-help {
    margin-top: 6px;
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }

  .alarm-condition-grid {
    grid-template-columns: 1fr 1fr 1fr;
  }
  :deep(.el-select),
  :deep(.el-input-number) {
    width: 100%;
  }

  @media (width <= 768px) {
    .alarm-form-grid,
    .alarm-condition-grid {
      grid-template-columns: 1fr;
    }
  }
</style>
