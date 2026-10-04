<!--
  项目任务的真实操作界面。

  一次性任务的 runAt 已经是 UTC 时刻；周期任务的 cron 由服务端按项目时区计算 nextRunAt。
  界面不自行计算 cron，避免浏览器时区、DST 规则或 cron 方言差异造成“看起来正确、实际错发”。
-->
<template>
  <div class="console-page task-jobs console-page--single-panel">
    <div class="task-jobs__header console-toolbar console-page-actions">
      <ElButton v-if="hasAuth('task:manage')" type="primary" :icon="Plus" @click="openCreate">
        创建任务
      </ElButton>
    </div>

    <ElAlert class="task-jobs__notice" type="info" :closable="false" show-icon>
      <template #title>调度与历史的时区口径</template>
      周期 cron 使用项目时区“{{ projectTimezone || '加载中' }}”解释，服务端保存下一次 UTC
      触发时刻。修改设备组或设备状态不会改写已经创建的执行记录。
    </ElAlert>

    <ElCard class="console-page__main-panel" shadow="never">
      <ElTable v-loading="loading" :data="jobs" row-key="id">
        <ElTableColumn prop="name" label="任务名称" min-width="155" show-overflow-tooltip />
        <ElTableColumn label="调度" min-width="245" show-overflow-tooltip>
          <template #default="{ row }">{{ scheduleSummary(row) }}</template>
        </ElTableColumn>
        <ElTableColumn label="目标" min-width="145" show-overflow-tooltip>
          <template #default="{ row }">{{ targetSummary(row) }}</template>
        </ElTableColumn>
        <ElTableColumn prop="commandKey" label="命令标识" min-width="145" show-overflow-tooltip />
        <ElTableColumn label="状态" width="95">
          <template #default="{ row }">
            <ElTag :type="isEnabled(row) ? 'success' : 'info'">
              {{ isEnabled(row) ? '已启用' : '已停用' }}
            </ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="下次执行" width="180">
          <template #default="{ row }">{{ formatTime(row.nextRunAt) }}</template>
        </ElTableColumn>
        <ElTableColumn label="更新时间" width="180">
          <template #default="{ row }">{{ formatTime(row.updatedAt) }}</template>
        </ElTableColumn>
        <ElTableColumn
          class-name="console-table-actions-cell"
          label="操作"
          width="184"
          fixed="right"
        >
          <template #default="{ row }">
            <ConsoleTableAction
              type="primary"
              @click="openExecutions(row)"
              label="执行记录"
              icon="ri:history-line"
            />
            <ConsoleTableAction
              v-if="hasAuth('task:run')"
              type="success"
              @click="runNow(row)"
              label="立即执行"
              icon="ri:play-circle-line"
            />
            <ConsoleTableAction
              v-if="hasAuth('task:manage')"
              type="primary"
              @click="openEdit(row)"
              label="编辑"
              icon="ri:pencil-line"
            />
            <ElDropdown
              v-if="hasAuth('task:manage')"
              trigger="click"
              @command="handleAction(row, $event)"
            >
              <ElButton
                link
                type="primary"
                class="console-table-action"
                aria-label="更多操作"
                title="更多操作"
                ><ArtSvgIcon icon="ri:more-2-fill"
              /></ElButton>
              <template #dropdown>
                <ElDropdownMenu>
                  <ElDropdownItem :command="isEnabled(row) ? 'disable' : 'enable'">
                    {{ isEnabled(row) ? '停用' : '启用' }}
                  </ElDropdownItem>
                  <ElDropdownItem command="delete" divided>删除</ElDropdownItem>
                </ElDropdownMenu>
              </template>
            </ElDropdown>
          </template>
        </ElTableColumn>
        <template #empty><ElEmpty description="还没有任务" /></template>
      </ElTable>
    </ElCard>

    <ElDialog
      class="console-dialog"
      v-model="formVisible"
      :title="editing ? '编辑任务' : '创建任务'"
      width="760px"
      destroy-on-close
    >
      <ElForm ref="formRef" :model="form" :rules="rules" label-position="top">
        <div class="task-form-grid">
          <ElFormItem label="任务名称" prop="name">
            <ElInput v-model.trim="form.name" maxlength="128" />
          </ElFormItem>
          <ElFormItem label="启用状态">
            <ElSwitch v-model="form.enabled" active-text="启用" inactive-text="停用" />
          </ElFormItem>
        </div>
        <ElFormItem label="说明">
          <ElInput v-model.trim="form.description" type="textarea" :rows="2" maxlength="500" />
        </ElFormItem>

        <ElDivider content-position="left">调度</ElDivider>
        <ElFormItem label="调度类型" prop="scheduleType">
          <ElRadioGroup v-model="form.scheduleType" @change="resetSchedule">
            <ElRadioButton value="ONCE">一次性</ElRadioButton>
            <ElRadioButton value="CRON">周期</ElRadioButton>
          </ElRadioGroup>
        </ElFormItem>
        <ElFormItem v-if="form.scheduleType === 'ONCE'" label="执行时刻" prop="runAt">
          <ElDatePicker
            v-model="form.runAt"
            type="datetime"
            class="task-form-control"
            placeholder="请选择执行时刻"
          />
          <div class="form-help"
            >提交时会转换为 RFC3339 UTC；请按当前浏览器显示的本地时间选择。</div
          >
        </ElFormItem>
        <ElFormItem v-else label="Cron 表达式（Spring 六字段）" prop="cronExpression">
          <ElInput v-model.trim="form.cronExpression" placeholder="0 0 9 * * *" maxlength="128" />
          <div class="form-help">
            依次为秒、分、时、日、月、星期；后端按项目时区 {{ projectTimezone || '—' }} 解释。
          </div>
        </ElFormItem>

        <ElDivider content-position="left">目标与命令</ElDivider>
        <ElFormItem label="目标范围" prop="targetType">
          <ElRadioGroup v-model="form.targetType">
            <ElRadioButton value="ALL_DEVICES">全部设备</ElRadioButton>
            <ElRadioButton value="DEVICE_GROUP">设备组</ElRadioButton>
          </ElRadioGroup>
        </ElFormItem>
        <ElFormItem v-if="form.targetType === 'DEVICE_GROUP'" label="设备组" prop="targetGroupId">
          <ElSelect
            v-model="form.targetGroupId"
            class="task-form-control"
            filterable
            placeholder="请选择设备组"
          >
            <ElOption
              v-for="group in groups"
              :key="group.id ?? ''"
              :label="`${group.name ?? '未命名设备组'}（${group.type === 'DYNAMIC' ? '动态' : '静态'}）`"
              :value="group.id ?? ''"
            />
          </ElSelect>
          <div class="form-help">动态组会在每次创建执行时按当时事实冻结匹配设备。</div>
        </ElFormItem>
        <ElFormItem label="命令标识" prop="commandKey">
          <ElInput v-model.trim="form.commandKey" maxlength="64" placeholder="reboot" />
        </ElFormItem>
        <ElFormItem label="命令输入（JSON）" prop="inputText">
          <ElInput
            v-model="form.inputText"
            type="textarea"
            :rows="5"
            placeholder="{}"
            class="task-form__json"
          />
          <div class="form-help">只校验 JSON 格式；命令参数结构仍由服务端按目标设备类型校验。</div>
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="formVisible = false">取消</ElButton>
        <ElButton type="primary" :loading="submitting" @click="submit">保存</ElButton>
      </template>
    </ElDialog>

    <ElDrawer
      v-model="executionsVisible"
      :title="`${selectedJob?.name ?? ''} · 执行记录`"
      size="900px"
      destroy-on-close
    >
      <ElAlert type="info" :closable="false" show-icon class="task-executions__notice">
        成功、失败和跳过数量以设备命令最终状态聚合；执行中的记录会在刷新后更新。
      </ElAlert>
      <ElTable v-loading="executionsLoading" :data="executions" row-key="id">
        <ElTableColumn label="触发方式" width="100">
          <template #default="{ row }">{{ triggerLabel(row.triggerType) }}</template>
        </ElTableColumn>
        <ElTableColumn label="状态" width="100">
          <template #default="{ row }"
            ><ElTag :type="executionTag(row.status)">{{
              executionLabel(row.status)
            }}</ElTag></template
          >
        </ElTableColumn>
        <ElTableColumn label="计划触发" width="180">
          <template #default="{ row }">{{ formatTime(row.scheduledFireAt) }}</template>
        </ElTableColumn>
        <ElTableColumn
          show-overflow-tooltip
          label="目标"
          width="78"
          align="right"
          prop="totalTargets"
        />
        <ElTableColumn
          show-overflow-tooltip
          label="已受理"
          width="88"
          align="right"
          prop="acceptedTargets"
        />
        <ElTableColumn
          show-overflow-tooltip
          label="成功"
          width="78"
          align="right"
          prop="succeededTargets"
        />
        <ElTableColumn
          show-overflow-tooltip
          label="失败"
          width="78"
          align="right"
          prop="failedTargets"
        />
        <ElTableColumn
          show-overflow-tooltip
          label="跳过"
          width="78"
          align="right"
          prop="skippedTargets"
        />
        <ElTableColumn label="开始时间" width="180">
          <template #default="{ row }">{{ formatTime(row.startedAt) }}</template>
        </ElTableColumn>
        <ElTableColumn label="结束时间" width="180">
          <template #default="{ row }">{{ formatTime(row.finishedAt) }}</template>
        </ElTableColumn>
        <ElTableColumn label="失败摘要" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">{{ row.failureSummary || '—' }}</template>
        </ElTableColumn>
        <template #empty><ElEmpty description="还没有执行记录" /></template>
      </ElTable>
    </ElDrawer>
  </div>
</template>

<script setup lang="ts">
  import ConsoleTableAction from '@/components/ConsoleTableAction.vue'

  import { formatTime } from '@/utils/time'
  import { Plus } from '@element-plus/icons-vue'
  import type { FormInstance, FormRules } from 'element-plus'
  import {
    fetchCreateTaskJob,
    fetchDeleteTaskJob,
    fetchDisableTaskJob,
    fetchEnableTaskJob,
    fetchRunTaskJob,
    fetchTaskExecutions,
    fetchTaskJobs,
    fetchUpdateTaskJob,
    type SaveTaskJobRequest,
    type TaskExecutionResponse,
    type TaskJobResponse
  } from '@/api/task'
  import { fetchDeviceGroups, type DeviceGroupResponse } from '@/api/device-group'
  import { fetchProjects } from '@/api/project'
  import { useAuth } from '@/hooks/core/useAuth'
  import { useUserStore } from '@/store/modules/user'
  import { HttpError } from '@/utils/http/error'

  defineOptions({ name: 'TaskJobs' })

  type ScheduleType = 'ONCE' | 'CRON'
  type TargetType = 'ALL_DEVICES' | 'DEVICE_GROUP'

  const userStore = useUserStore()
  const { hasAuth } = useAuth()
  const projectId = computed(() => userStore.info.currentProjectId ?? '')
  const loading = ref(false)
  const submitting = ref(false)
  const jobs = ref<TaskJobResponse[]>([])
  const groups = ref<DeviceGroupResponse[]>([])
  const projectTimezone = ref('')
  const formVisible = ref(false)
  const formRef = ref<FormInstance>()
  const editing = ref<TaskJobResponse>()
  const form = reactive({
    name: '',
    description: '',
    enabled: true,
    scheduleType: 'ONCE' as ScheduleType,
    runAt: undefined as Date | undefined,
    cronExpression: '',
    targetType: 'ALL_DEVICES' as TargetType,
    targetGroupId: '',
    commandKey: '',
    inputText: '{}'
  })

  const executionsVisible = ref(false)
  const executionsLoading = ref(false)
  const selectedJob = ref<TaskJobResponse>()
  const executions = ref<TaskExecutionResponse[]>([])

  const rules = computed<FormRules<typeof form>>(() => ({
    name: [{ required: true, message: '请输入任务名称', trigger: 'blur' }],
    scheduleType: [{ required: true, message: '请选择调度类型', trigger: 'change' }],
    runAt: [
      {
        validator: (_rule, value, callback) =>
          form.scheduleType !== 'ONCE' || value
            ? callback()
            : callback(new Error('请选择执行时刻')),
        trigger: 'change'
      }
    ],
    cronExpression: [
      {
        validator: (_rule, value, callback) => {
          if (form.scheduleType !== 'CRON') return callback()
          const fields = String(value ?? '')
            .trim()
            .split(/\s+/)
            .filter(Boolean)
          return fields.length === 6
            ? callback()
            : callback(new Error('请输入 Spring cron 的六个字段'))
        },
        trigger: 'blur'
      }
    ],
    targetType: [{ required: true, message: '请选择目标范围', trigger: 'change' }],
    targetGroupId: [
      {
        validator: (_rule, value, callback) =>
          form.targetType !== 'DEVICE_GROUP' || value
            ? callback()
            : callback(new Error('请选择设备组')),
        trigger: 'change'
      }
    ],
    commandKey: [{ required: true, message: '请输入命令标识', trigger: 'blur' }],
    inputText: [
      {
        validator: (_rule, value, callback) => {
          try {
            JSON.parse(value || '{}')
            callback()
          } catch {
            callback(new Error('命令输入必须是有效 JSON'))
          }
        },
        trigger: 'blur'
      }
    ]
  }))

  /** 后端的状态枚举保持扩展兼容，未知状态仍应展示原值而不是伪装成停用。 */
  const isEnabled = (job: TaskJobResponse) => job.status === 'ACTIVE'
  const scheduleSummary = (job: TaskJobResponse) => {
    if (job.scheduleType === 'ONCE') return `一次性 · ${formatTime(job.runAt)}`
    return `周期 · ${job.cronExpression || '—'} · ${job.timezone || projectTimezone.value || '—'}`
  }
  const targetSummary = (job: TaskJobResponse) => {
    if (job.targetType === 'ALL_DEVICES') return '全部设备'
    const group = groups.value.find((item) => item.id === job.targetGroupId)
    return group?.name ?? '设备组'
  }
  const triggerLabel = (value?: string) =>
    ({ SCHEDULE: '计划触发', MANUAL: '手动触发' })[value ?? ''] ?? value ?? '—'
  const executionLabel = (value?: string) =>
    ({
      EXPANDING: '展开目标中',
      STOPPING: '停止中',
      DISPATCHING: '下发中',
      RUNNING: '执行中',
      SUCCEEDED: '已完成',
      PARTIAL_FAILED: '部分失败',
      FAILED: '失败'
    })[value ?? ''] ??
    value ??
    '—'
  const executionTag = (value?: string) =>
    ({
      EXPANDING: 'info',
      STOPPING: 'warning',
      DISPATCHING: 'warning',
      RUNNING: 'warning',
      SUCCEEDED: 'success',
      PARTIAL_FAILED: 'warning',
      FAILED: 'danger'
    })[value ?? ''] as 'info' | 'warning' | 'success' | 'danger' | undefined

  const load = async () => {
    if (!projectId.value) return
    loading.value = true
    try {
      const [taskResult, groupResult, projectResult] = await Promise.all([
        fetchTaskJobs(projectId.value),
        fetchDeviceGroups(projectId.value),
        fetchProjects()
      ])
      jobs.value = taskResult
      groups.value = groupResult
      projectTimezone.value =
        projectResult.find((item) => item.id === projectId.value)?.timezone ?? ''
    } catch (error) {
      if (!(error instanceof HttpError)) console.error('加载任务列表失败:', error)
    } finally {
      loading.value = false
    }
  }

  const resetForm = () => {
    Object.assign(form, {
      name: '',
      description: '',
      enabled: true,
      scheduleType: 'ONCE' as ScheduleType,
      runAt: undefined,
      cronExpression: '',
      targetType: 'ALL_DEVICES' as TargetType,
      targetGroupId: '',
      commandKey: '',
      inputText: '{}'
    })
    formRef.value?.clearValidate()
  }

  const openCreate = () => {
    editing.value = undefined
    resetForm()
    formVisible.value = true
  }

  const openEdit = (job: TaskJobResponse) => {
    editing.value = job
    Object.assign(form, {
      name: job.name ?? '',
      description: job.description ?? '',
      enabled: isEnabled(job),
      scheduleType: (job.scheduleType ?? 'ONCE') as ScheduleType,
      runAt: job.runAt ? new Date(job.runAt) : undefined,
      cronExpression: job.cronExpression ?? '',
      targetType: (job.targetType ?? 'ALL_DEVICES') as TargetType,
      targetGroupId: job.targetGroupId ?? '',
      commandKey: job.commandKey ?? '',
      inputText: JSON.stringify(job.input ?? {}, null, 2)
    })
    formRef.value?.clearValidate()
    formVisible.value = true
  }

  const resetSchedule = () => {
    form.runAt = undefined
    form.cronExpression = ''
  }

  const submit = async () => {
    if (!formRef.value || !projectId.value) return
    try {
      await formRef.value.validate()
      const body: SaveTaskJobRequest = {
        name: form.name,
        description: form.description || undefined,
        enabled: form.enabled,
        scheduleType: form.scheduleType,
        runAt: form.scheduleType === 'ONCE' ? form.runAt?.toISOString() : undefined,
        cronExpression: form.scheduleType === 'CRON' ? form.cronExpression : undefined,
        targetType: form.targetType,
        targetGroupId: form.targetType === 'DEVICE_GROUP' ? form.targetGroupId : undefined,
        commandKey: form.commandKey,
        input: JSON.parse(form.inputText || '{}'),
        expectedVersion: editing.value?.version
      }
      submitting.value = true
      if (editing.value?.id) {
        await fetchUpdateTaskJob(projectId.value, editing.value.id, body)
      } else {
        await fetchCreateTaskJob(projectId.value, body)
      }
      ElMessage.success(editing.value ? '任务已更新' : '任务已创建')
      formVisible.value = false
      await load()
    } catch (error) {
      if (error instanceof Error && !(error instanceof HttpError) && error.message) {
        ElMessage.warning(error.message)
      }
    } finally {
      submitting.value = false
    }
  }

  const runNow = async (job: TaskJobResponse) => {
    if (!projectId.value || !job.id) return
    try {
      await ElMessageBox.confirm(
        `将立即为任务“${job.name ?? ''}”创建一次执行快照，并按配额限速下发。是否继续？`,
        '立即执行',
        { type: 'warning' }
      )
      await fetchRunTaskJob(projectId.value, job.id)
      ElMessage.success('执行已创建，可在执行记录中查看进度')
      await openExecutions(job)
    } catch (error) {
      if (error !== 'cancel' && error !== 'close' && !(error instanceof HttpError)) {
        console.error('立即执行任务失败:', error)
      }
    }
  }

  const handleAction = async (job: TaskJobResponse, action: 'enable' | 'disable' | 'delete') => {
    if (!projectId.value || !job.id || job.version == null) return
    try {
      if (action === 'delete') {
        await ElMessageBox.confirm(
          `确定删除任务“${job.name ?? ''}”吗？既有执行记录仍会保留。`,
          '删除任务',
          { type: 'warning' }
        )
        await fetchDeleteTaskJob(projectId.value, job.id, job.version)
        ElMessage.success('任务已删除')
      } else if (action === 'enable') {
        await fetchEnableTaskJob(projectId.value, job.id)
        ElMessage.success('任务已启用')
      } else {
        await fetchDisableTaskJob(projectId.value, job.id)
        ElMessage.success('任务已停用')
      }
      await load()
    } catch (error) {
      if (error !== 'cancel' && error !== 'close' && !(error instanceof HttpError)) {
        console.error('更新任务状态失败:', error)
      }
    }
  }

  const openExecutions = async (job: TaskJobResponse) => {
    if (!projectId.value || !job.id) return
    selectedJob.value = job
    executions.value = []
    executionsVisible.value = true
    executionsLoading.value = true
    try {
      executions.value = await fetchTaskExecutions(projectId.value, job.id)
    } catch (error) {
      if (!(error instanceof HttpError)) console.error('加载任务执行记录失败:', error)
    } finally {
      executionsLoading.value = false
    }
  }

  onMounted(() => void load())
</script>

<style lang="scss" scoped>
  .task-jobs {
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

    &__more-icon {
      margin-left: 3px;
    }
  }

  .task-form-grid {
    display: grid;
    grid-template-columns: minmax(0, 1fr) 150px;
    gap: 10px;
  }

  .task-form-control {
    width: 100%;
  }

  .task-form__json :deep(textarea) {
    font-family: var(--art-font-family-mono, monospace);
  }

  .form-help {
    margin-top: 4px;
    font-size: 12px;
    color: var(--art-text-gray-600);
  }

  .task-executions__notice {
    margin-bottom: 10px;
  }

  @media screen and (width <= 640px) {
    .task-jobs {
      padding: 16px;

      &__header {
        flex-direction: column;
        align-items: stretch;
      }

      &__header .el-button {
        align-self: flex-start;
      }
    }

    .task-form-grid {
      grid-template-columns: 1fr;
    }
  }
</style>
