<template>
  <div class="console-page notification-page console-page--single-panel">
    <div class="notification-page__header console-toolbar console-page-actions">
      <ElButton v-if="hasAuth('alarm:manage')" type="primary" :icon="Plus" @click="openCreate">
        创建通知模板
      </ElButton>
    </div>

    <ElAlert class="notification-page__notice" type="info" :closable="false" show-icon>
      模板不执行脚本或任意表达式；未知的 <code>${...}</code> 变量会在保存时被服务端拒绝。
    </ElAlert>

    <ElCard class="console-page__main-panel" shadow="never">
      <ElTable v-loading="loading" :data="templates" row-key="id">
        <ElTableColumn prop="name" label="模板名称" min-width="180" show-overflow-tooltip />
        <ElTableColumn label="渠道" width="110">
          <template #default="{ row }">{{ channelLabel(row.channel) }}</template>
        </ElTableColumn>
        <ElTableColumn prop="subjectTemplate" label="主题" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">{{ row.subjectTemplate || '—' }}</template>
        </ElTableColumn>
        <ElTableColumn label="状态" width="100">
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
          v-if="hasAuth('alarm:manage')"
          label="操作"
          width="104"
          fixed="right"
        >
          <template #default="{ row }">
            <ConsoleTableAction
              type="primary"
              @click="openEdit(row)"
              label="编辑"
              icon="ri:pencil-line"
            />
            <ConsoleTableAction
              type="danger"
              @click="removeTemplate(row)"
              label="删除"
              icon="ri:delete-bin-5-line"
            />
          </template>
        </ElTableColumn>
        <template #empty><ElEmpty description="还没有通知模板" /></template>
      </ElTable>

      <div v-if="hasMore" class="notification-page__more">
        <ElButton :loading="loading" type="primary" text @click="loadMore">加载更多</ElButton>
      </div>
    </ElCard>

    <ElDialog
      class="console-dialog"
      v-model="formVisible"
      :title="editingId ? '编辑通知模板' : '创建通知模板'"
      width="760px"
      destroy-on-close
    >
      <ElForm ref="formRef" :model="form" :rules="rules" label-position="top">
        <div class="template-form-grid">
          <ElFormItem label="模板名称" prop="name">
            <ElInput v-model.trim="form.name" maxlength="128" />
          </ElFormItem>
          <ElFormItem label="渠道" prop="channel">
            <ElRadioGroup v-model="form.channel">
              <ElRadioButton value="EMAIL">邮件</ElRadioButton>
              <ElRadioButton value="WEBHOOK">Webhook</ElRadioButton>
            </ElRadioGroup>
          </ElFormItem>
        </div>

        <ElFormItem label="可用变量">
          <div class="template-variables">
            <ElTag v-for="variable in templateVariables" :key="variable" type="info">
              {{ variable }}
            </ElTag>
          </div>
          <div class="form-help">变量名和大小写必须完全一致，可用于主题和正文。</div>
        </ElFormItem>

        <ElFormItem v-if="form.channel === 'EMAIL'" label="邮件主题" prop="subjectTemplate">
          <ElInput
            v-model="form.subjectTemplate"
            maxlength="256"
            placeholder="[${alarm.severity}] ${alarm.type}"
          />
        </ElFormItem>
        <ElFormItem label="正文" prop="bodyTemplate">
          <ElInput
            v-model="form.bodyTemplate"
            type="textarea"
            :rows="10"
            maxlength="20000"
            show-word-limit
            placeholder="告警 ${alarm.type} 已触发，当前值 ${alarm.value}。"
          />
        </ElFormItem>
        <ElFormItem label="启用">
          <ElSwitch v-model="form.enabled" />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="formVisible = false">取消</ElButton>
        <ElButton type="primary" :loading="submitting" @click="submitTemplate">保存</ElButton>
      </template>
    </ElDialog>
  </div>
</template>

<script setup lang="ts">
  import ConsoleTableAction from '@/components/ConsoleTableAction.vue'

  import { formatTime } from '@/utils/time'
  import { Plus } from '@element-plus/icons-vue'
  import type { FormInstance, FormRules } from 'element-plus'
  import {
    fetchAlarmNotificationTemplates,
    fetchCreateAlarmNotificationTemplate,
    fetchDeleteAlarmNotificationTemplate,
    fetchUpdateAlarmNotificationTemplate,
    type AlarmNotificationTemplateResponse,
    type SaveAlarmNotificationTemplateRequest
  } from '@/api/alarm'
  import { useAuth } from '@/hooks/core/useAuth'
  import { useUserStore } from '@/store/modules/user'
  import { HttpError } from '@/utils/http/error'

  defineOptions({ name: 'AlarmNotificationTemplates' })

  const userStore = useUserStore()
  const { hasAuth } = useAuth()
  const projectId = computed(() => userStore.info.currentProjectId ?? '')
  const loading = ref(false)
  const submitting = ref(false)
  const templates = ref<AlarmNotificationTemplateResponse[]>([])
  const nextCursor = ref<string>()
  const hasMore = ref(false)
  const formVisible = ref(false)
  const editingId = ref('')
  const formRef = ref<FormInstance>()
  const templateVariables = [
    '${alarm.type}',
    '${alarm.severity}',
    '${alarm.value}',
    '${alarm.activatedAt}',
    '${alarm.instanceId}'
  ]

  const defaultForm = (): SaveAlarmNotificationTemplateRequest => ({
    name: '',
    channel: 'EMAIL',
    subjectTemplate: '',
    bodyTemplate: '',
    enabled: true
  })
  const form = reactive<SaveAlarmNotificationTemplateRequest>(defaultForm())
  const validateSubject = (_rule: unknown, value: unknown, callback: (error?: Error) => void) => {
    if (form.channel === 'EMAIL' && (typeof value !== 'string' || !value.trim())) {
      callback(new Error('邮件模板必须填写主题'))
      return
    }
    callback()
  }
  const rules: FormRules = {
    name: [{ required: true, message: '请输入模板名称', trigger: 'blur' }],
    channel: [{ required: true, message: '请选择渠道', trigger: 'change' }],
    subjectTemplate: [{ validator: validateSubject, trigger: 'blur' }],
    bodyTemplate: [{ required: true, message: '请输入模板正文', trigger: 'blur' }]
  }

  const channelLabel = (value?: string) =>
    ({ EMAIL: '邮件', WEBHOOK: 'Webhook' })[value ?? ''] ?? value ?? '—'

  const loadTemplates = async (append = false) => {
    if (!projectId.value) return
    loading.value = true
    try {
      const page = await fetchAlarmNotificationTemplates(
        projectId.value,
        append ? nextCursor.value : undefined
      )
      templates.value = append ? [...templates.value, ...(page.items ?? [])] : (page.items ?? [])
      nextCursor.value = page.nextCursor ?? undefined
      hasMore.value = page.hasMore ?? false
    } catch (error) {
      if (!(error instanceof HttpError)) console.error('加载通知模板失败:', error)
    } finally {
      loading.value = false
    }
  }
  const loadMore = () => void loadTemplates(true)
  const openCreate = () => {
    editingId.value = ''
    Object.assign(form, defaultForm())
    formVisible.value = true
  }
  const openEdit = (row: AlarmNotificationTemplateResponse) => {
    editingId.value = row.id ?? ''
    Object.assign(form, {
      name: row.name ?? '',
      channel: row.channel ?? 'EMAIL',
      subjectTemplate: row.subjectTemplate ?? '',
      bodyTemplate: row.bodyTemplate ?? '',
      enabled: row.enabled ?? true,
      version: row.version
    })
    formVisible.value = true
  }
  const submitTemplate = async () => {
    if (!projectId.value || !(await formRef.value?.validate().catch(() => false))) return
    submitting.value = true
    try {
      const body: SaveAlarmNotificationTemplateRequest = { ...form }
      if (body.channel === 'WEBHOOK') body.subjectTemplate = ''
      if (editingId.value) {
        await fetchUpdateAlarmNotificationTemplate(projectId.value, editingId.value, body)
      } else {
        delete body.version
        await fetchCreateAlarmNotificationTemplate(projectId.value, body)
      }
      ElMessage.success(editingId.value ? '通知模板已更新' : '通知模板已创建')
      formVisible.value = false
      await loadTemplates()
    } catch (error) {
      if (!(error instanceof HttpError)) console.error('保存通知模板失败:', error)
    } finally {
      submitting.value = false
    }
  }
  const removeTemplate = async (row: AlarmNotificationTemplateResponse) => {
    if (!projectId.value || !row.id || row.version == null) return
    try {
      await ElMessageBox.confirm(
        `确定删除通知模板“${row.name ?? ''}”吗？仍被规则绑定时服务端会拒绝删除。`,
        '删除通知模板',
        { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
      )
      await fetchDeleteAlarmNotificationTemplate(projectId.value, row.id, row.version)
      ElMessage.success('通知模板已删除')
      await loadTemplates()
    } catch (error) {
      if (error !== 'cancel' && error !== 'close' && !(error instanceof HttpError)) {
        console.error('删除通知模板失败:', error)
      }
    }
  }

  onMounted(() => void loadTemplates())
</script>

<style scoped lang="scss">
  .notification-page {
    padding: 10px;

    &__header {
      display: flex;
      gap: 10px;
      align-items: flex-start;
      justify-content: space-between;
      margin-bottom: 10px;

      h3 {
        margin: 0 0 6px;
        font-size: 20px;
      }

      p {
        margin: 0;
        color: var(--el-text-color-secondary);
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

  .template-form-grid {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 0 18px;
  }

  .template-variables {
    display: flex;
    flex-wrap: wrap;
    gap: 8px;
  }

  .form-help {
    margin-top: 6px;
    font-size: 12px;
    color: var(--el-text-color-secondary);
  }

  @media (width <= 768px) {
    .template-form-grid {
      grid-template-columns: 1fr;
    }
  }
</style>
