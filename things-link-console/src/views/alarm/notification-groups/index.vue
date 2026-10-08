<template>
  <div class="console-page notification-page console-page--single-panel">
    <ConsoleWorkspaceHeader
      title="通知组"
      description="管理告警通知的接收对象，再在告警规则中关联通知路由。"
      :links="[
        { label: '通知模板', path: '/alarm/notification-templates', permission: 'alarm:read' },
        { label: '告警规则', path: '/alarm/rules', permission: 'alarm:read' },
        { label: '告警历史', path: '/alarm/history', permission: 'alarm:read' }
      ]"
    />
    <div class="notification-page__header console-toolbar console-page-actions">
      <ElButton v-if="hasAuth('alarm:manage')" type="primary" :icon="Plus" @click="openCreateGroup">
        创建通知组
      </ElButton>
    </div>

    <ElAlert
      class="notification-page__notice"
      type="info"
      :closable="false"
      show-icon
      title="收件地址由服务端脱敏返回；编辑收件人时不会回填旧地址，必须显式输入完整新地址。"
    />

    <ElCard class="console-page__main-panel" shadow="never">
      <ElTable v-loading="loading" :data="groups" row-key="id">
        <ElTableColumn prop="name" label="组名称" min-width="180" show-overflow-tooltip />
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
          label="操作"
          width="144"
          fixed="right"
        >
          <template #default="{ row }">
            <ConsoleTableAction
              type="primary"
              @click="openRecipients(row)"
              label="收件人"
              icon="ri:group-line"
            />
            <ConsoleTableAction
              v-if="hasAuth('alarm:manage')"
              type="primary"
              @click="openEditGroup(row)"
              label="编辑"
              icon="ri:pencil-line"
            />
            <ConsoleTableAction
              v-if="hasAuth('alarm:manage')"
              type="danger"
              @click="removeGroup(row)"
              label="删除"
              icon="ri:delete-bin-5-line"
            />
          </template>
        </ElTableColumn>
        <template #empty><ElEmpty description="还没有通知组" /></template>
      </ElTable>

      <div v-if="hasMore" class="notification-page__more">
        <ElButton :loading="loading" type="primary" text @click="loadMoreGroups">加载更多</ElButton>
      </div>
    </ElCard>

    <ElDialog
      class="console-dialog"
      v-model="groupFormVisible"
      :title="editingGroupId ? '编辑通知组' : '创建通知组'"
      width="520px"
      destroy-on-close
    >
      <ElForm ref="groupFormRef" :model="groupForm" :rules="groupRules" label-position="top">
        <ElFormItem label="组名称" prop="name">
          <ElInput v-model.trim="groupForm.name" maxlength="128" />
        </ElFormItem>
        <ElFormItem label="启用">
          <ElSwitch v-model="groupForm.enabled" />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="groupFormVisible = false">取消</ElButton>
        <ElButton type="primary" :loading="groupSubmitting" @click="submitGroup">保存</ElButton>
      </template>
    </ElDialog>

    <ElDrawer
      v-model="recipientsVisible"
      :title="`${selectedGroup?.name ?? ''} · 收件人`"
      size="720px"
      destroy-on-close
    >
      <div class="recipient-toolbar">
        <span>地址只展示脱敏值，服务端不会向浏览器返回完整目标。</span>
        <ElButton
          v-if="hasAuth('alarm:manage')"
          type="primary"
          :icon="Plus"
          @click="openCreateRecipient"
        >
          添加收件人
        </ElButton>
      </div>

      <ElTable v-loading="recipientsLoading" :data="recipients" row-key="id">
        <ElTableColumn label="渠道" width="110">
          <template #default="{ row }">{{ channelLabel(row.channel) }}</template>
        </ElTableColumn>
        <ElTableColumn prop="target" label="脱敏目标" min-width="260" show-overflow-tooltip />
        <ElTableColumn label="状态" width="100">
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
              @click="openEditRecipient(row)"
              label="替换"
              icon="ri:edit-box-line"
            />
            <ConsoleTableAction
              type="danger"
              @click="removeRecipient(row)"
              label="删除"
              icon="ri:delete-bin-5-line"
            />
          </template>
        </ElTableColumn>
        <template #empty><ElEmpty description="该组还没有收件人" /></template>
      </ElTable>
    </ElDrawer>

    <ElDialog
      class="console-dialog"
      v-model="recipientFormVisible"
      :title="editingRecipientId ? '替换收件目标' : '添加收件人'"
      width="580px"
      append-to-body
      destroy-on-close
    >
      <ElAlert
        v-if="editingRecipientId"
        class="recipient-replace-alert"
        type="warning"
        :closable="false"
        show-icon
        :title="`当前脱敏目标：${editingRecipientMaskedTarget || '—'}。请重新输入完整地址。`"
      />
      <ElForm
        ref="recipientFormRef"
        :model="recipientForm"
        :rules="recipientRules"
        label-position="top"
      >
        <ElFormItem label="渠道" prop="channel">
          <ElRadioGroup v-model="recipientForm.channel">
            <ElRadioButton value="EMAIL">邮件</ElRadioButton>
            <ElRadioButton value="WEBHOOK">Webhook</ElRadioButton>
          </ElRadioGroup>
        </ElFormItem>
        <ElFormItem
          :label="recipientForm.channel === 'EMAIL' ? '完整邮箱地址' : '完整 Webhook 地址'"
          prop="target"
        >
          <ElInput
            v-model.trim="recipientForm.target"
            maxlength="2048"
            :placeholder="
              recipientForm.channel === 'EMAIL'
                ? 'operator@example.com'
                : 'https://example.com/thingslink/alarm'
            "
          />
          <div class="form-help">
            Webhook 仅接受 HTTPS，且不允许 URL 用户信息、查询参数或片段；认证密钥不属于本配置。
          </div>
        </ElFormItem>
        <ElFormItem label="启用">
          <ElSwitch v-model="recipientForm.enabled" />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="recipientFormVisible = false">取消</ElButton>
        <ElButton type="primary" :loading="recipientSubmitting" @click="submitRecipient">
          {{ editingRecipientId ? '确认替换' : '保存' }}
        </ElButton>
      </template>
    </ElDialog>
  </div>
</template>

<script setup lang="ts">
  import ConsoleWorkspaceHeader from '@/components/business/ConsoleWorkspaceHeader.vue'
  import ConsoleTableAction from '@/components/ConsoleTableAction.vue'

  import { formatTime } from '@/utils/time'
  import { Plus } from '@element-plus/icons-vue'
  import type { FormInstance, FormRules } from 'element-plus'
  import {
    fetchAlarmNotificationGroups,
    fetchAlarmNotificationRecipients,
    fetchCreateAlarmNotificationGroup,
    fetchCreateAlarmNotificationRecipient,
    fetchDeleteAlarmNotificationGroup,
    fetchDeleteAlarmNotificationRecipient,
    fetchUpdateAlarmNotificationGroup,
    fetchUpdateAlarmNotificationRecipient,
    type AlarmNotificationGroupResponse,
    type AlarmNotificationRecipientResponse,
    type SaveAlarmNotificationGroupRequest,
    type SaveAlarmNotificationRecipientRequest
  } from '@/api/alarm'
  import { useAuth } from '@/hooks/core/useAuth'
  import { useUserStore } from '@/store/modules/user'
  import { HttpError } from '@/utils/http/error'

  defineOptions({ name: 'AlarmNotificationGroups' })

  type NotificationChannel = SaveAlarmNotificationRecipientRequest['channel']

  const userStore = useUserStore()
  const { hasAuth } = useAuth()
  const projectId = computed(() => userStore.info.currentProjectId ?? '')
  const loading = ref(false)
  const groups = ref<AlarmNotificationGroupResponse[]>([])
  const nextCursor = ref<string>()
  const hasMore = ref(false)
  const groupFormVisible = ref(false)
  const groupSubmitting = ref(false)
  const editingGroupId = ref('')
  const groupFormRef = ref<FormInstance>()
  const selectedGroup = ref<AlarmNotificationGroupResponse>()
  const recipientsVisible = ref(false)
  const recipientsLoading = ref(false)
  const recipients = ref<AlarmNotificationRecipientResponse[]>([])
  const recipientFormVisible = ref(false)
  const recipientSubmitting = ref(false)
  const editingRecipientId = ref('')
  const editingRecipientMaskedTarget = ref('')
  const recipientFormRef = ref<FormInstance>()

  const defaultGroupForm = (): SaveAlarmNotificationGroupRequest => ({ name: '', enabled: true })
  const groupForm = reactive<SaveAlarmNotificationGroupRequest>(defaultGroupForm())
  const groupRules: FormRules = {
    name: [{ required: true, message: '请输入通知组名称', trigger: 'blur' }]
  }

  const defaultRecipientForm = (): SaveAlarmNotificationRecipientRequest => ({
    channel: 'EMAIL',
    target: '',
    enabled: true
  })
  const recipientForm = reactive<SaveAlarmNotificationRecipientRequest>(defaultRecipientForm())
  const validateTarget = (_rule: unknown, value: unknown, callback: (error?: Error) => void) => {
    const target = typeof value === 'string' ? value.trim() : ''
    if (!target) return callback(new Error('请输入完整收件目标'))
    if (recipientForm.channel === 'EMAIL') {
      return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(target)
        ? callback()
        : callback(new Error('请输入有效邮箱地址'))
    }
    try {
      const url = new URL(target)
      if (url.protocol !== 'https:' || url.username || url.password || url.search || url.hash) {
        return callback(new Error('Webhook 必须是无用户信息、查询参数和片段的 HTTPS 地址'))
      }
      callback()
    } catch {
      callback(new Error('请输入有效 Webhook 地址'))
    }
  }
  const recipientRules: FormRules = {
    channel: [{ required: true, message: '请选择渠道', trigger: 'change' }],
    target: [{ validator: validateTarget, trigger: 'blur' }]
  }

  const channelLabel = (value?: string) =>
    ({ EMAIL: '邮件', WEBHOOK: 'Webhook' })[value ?? ''] ?? value ?? '—'

  const loadGroups = async (append = false) => {
    if (!projectId.value) return
    loading.value = true
    try {
      const page = await fetchAlarmNotificationGroups(
        projectId.value,
        append ? nextCursor.value : undefined
      )
      groups.value = append ? [...groups.value, ...(page.items ?? [])] : (page.items ?? [])
      nextCursor.value = page.nextCursor ?? undefined
      hasMore.value = page.hasMore ?? false
    } catch (error) {
      if (!(error instanceof HttpError)) console.error('加载通知组失败:', error)
    } finally {
      loading.value = false
    }
  }
  const loadMoreGroups = () => void loadGroups(true)

  const openCreateGroup = () => {
    editingGroupId.value = ''
    Object.assign(groupForm, defaultGroupForm())
    groupFormVisible.value = true
  }
  const openEditGroup = (row: AlarmNotificationGroupResponse) => {
    editingGroupId.value = row.id ?? ''
    Object.assign(groupForm, {
      name: row.name ?? '',
      enabled: row.enabled ?? true,
      version: row.version
    })
    groupFormVisible.value = true
  }
  const submitGroup = async () => {
    if (!projectId.value || !(await groupFormRef.value?.validate().catch(() => false))) return
    groupSubmitting.value = true
    try {
      const body: SaveAlarmNotificationGroupRequest = { ...groupForm }
      if (editingGroupId.value) {
        await fetchUpdateAlarmNotificationGroup(projectId.value, editingGroupId.value, body)
      } else {
        delete body.version
        await fetchCreateAlarmNotificationGroup(projectId.value, body)
      }
      ElMessage.success(editingGroupId.value ? '通知组已更新' : '通知组已创建')
      groupFormVisible.value = false
      await loadGroups()
    } catch (error) {
      if (!(error instanceof HttpError)) console.error('保存通知组失败:', error)
    } finally {
      groupSubmitting.value = false
    }
  }
  const removeGroup = async (row: AlarmNotificationGroupResponse) => {
    if (!projectId.value || !row.id || row.version == null) return
    try {
      await ElMessageBox.confirm(
        `确定删除通知组“${row.name ?? ''}”吗？仍被规则绑定时服务端会拒绝删除。`,
        '删除通知组',
        { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
      )
      await fetchDeleteAlarmNotificationGroup(projectId.value, row.id, row.version)
      ElMessage.success('通知组已删除')
      await loadGroups()
    } catch (error) {
      if (error !== 'cancel' && error !== 'close' && !(error instanceof HttpError)) {
        console.error('删除通知组失败:', error)
      }
    }
  }

  const loadRecipients = async () => {
    if (!projectId.value || !selectedGroup.value?.id) return
    recipientsLoading.value = true
    try {
      recipients.value = await fetchAlarmNotificationRecipients(
        projectId.value,
        selectedGroup.value.id
      )
    } catch (error) {
      if (!(error instanceof HttpError)) console.error('加载收件人失败:', error)
    } finally {
      recipientsLoading.value = false
    }
  }
  const openRecipients = async (row: AlarmNotificationGroupResponse) => {
    selectedGroup.value = row
    recipientsVisible.value = true
    await loadRecipients()
  }
  const openCreateRecipient = () => {
    editingRecipientId.value = ''
    editingRecipientMaskedTarget.value = ''
    Object.assign(recipientForm, defaultRecipientForm())
    recipientFormVisible.value = true
  }
  const openEditRecipient = (row: AlarmNotificationRecipientResponse) => {
    editingRecipientId.value = row.id ?? ''
    editingRecipientMaskedTarget.value = row.target ?? ''
    Object.assign(recipientForm, {
      channel: (row.channel ?? 'EMAIL') as NotificationChannel,
      target: '',
      enabled: row.enabled ?? true,
      version: row.version
    })
    recipientFormVisible.value = true
  }
  const submitRecipient = async () => {
    if (
      !projectId.value ||
      !selectedGroup.value?.id ||
      !(await recipientFormRef.value?.validate().catch(() => false))
    )
      return
    recipientSubmitting.value = true
    try {
      const body: SaveAlarmNotificationRecipientRequest = { ...recipientForm }
      if (editingRecipientId.value) {
        await fetchUpdateAlarmNotificationRecipient(projectId.value, editingRecipientId.value, body)
      } else {
        delete body.version
        await fetchCreateAlarmNotificationRecipient(projectId.value, selectedGroup.value.id, body)
      }
      ElMessage.success(editingRecipientId.value ? '收件目标已替换' : '收件人已添加')
      recipientFormVisible.value = false
      await loadRecipients()
    } catch (error) {
      if (!(error instanceof HttpError)) console.error('保存收件人失败:', error)
    } finally {
      recipientSubmitting.value = false
    }
  }
  const removeRecipient = async (row: AlarmNotificationRecipientResponse) => {
    if (!projectId.value || !row.id || row.version == null) return
    try {
      await ElMessageBox.confirm(`确定删除收件目标“${row.target ?? ''}”吗？`, '删除收件人', {
        type: 'warning',
        confirmButtonText: '删除',
        cancelButtonText: '取消'
      })
      await fetchDeleteAlarmNotificationRecipient(projectId.value, row.id, row.version)
      ElMessage.success('收件人已删除')
      await loadRecipients()
    } catch (error) {
      if (error !== 'cancel' && error !== 'close' && !(error instanceof HttpError)) {
        console.error('删除收件人失败:', error)
      }
    }
  }

  onMounted(() => void loadGroups())
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

  .recipient-toolbar {
    display: flex;
    gap: 10px;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 10px;
    color: var(--el-text-color-secondary);
  }

  .recipient-replace-alert {
    margin-bottom: 10px;
  }

  .form-help {
    margin-top: 6px;
    font-size: 12px;
    line-height: 1.5;
    color: var(--el-text-color-secondary);
  }
</style>
