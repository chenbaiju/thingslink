<template>
  <div class="console-fragment">
    <ElCard class="console-list-filter" shadow="never">
      <slot name="tabs" />
      <ConsoleFilterBar
        :items="[
          { key: 'field0', label: '自动化 ID', labelWidth: 90, span: 8 },
          { key: 'field1', label: '执行状态', labelWidth: 90, span: 8 },
          { key: 'field2', label: '发生时间', labelWidth: 90, span: 16 }
        ]"
        :show-expand="true"
        :show-reset="true"
        :show-search="true"
        @search="load(false)"
        @reset="resetFilters"
        :loading="busy"
      >
        <template #field0
          ><ElInput
            v-model="automationId"
            aria-label="自动化ID筛选"
            placeholder="自动化 ID（可选）"
        /></template>
        <template #field1
          ><ElSelect v-model="status" clearable aria-label="自动化执行状态" placeholder="执行状态">
            <ElOption
              v-for="value in statuses"
              :key="value"
              :value="value"
              :label="value"
            /> </ElSelect
        ></template>
        <template #field2
          ><ElDatePicker
            v-model="range"
            type="datetimerange"
            start-placeholder="开始时间"
            end-placeholder="结束时间"
            range-separator="至"
        /></template>
      </ConsoleFilterBar>
    </ElCard>
    <ElCard class="console-list-data" shadow="never">
      <ElAlert
        class="automation-executions__notice"
        title="按冻结属性事件或时间发生点独立求值。DISPATCHED 表示动作已受理，不等于外部送达。"
        type="info"
        :closable="false"
        show-icon
      />
      <ElAlert v-if="error" :title="error" type="error" :closable="false" show-icon />
      <ElTable :data="items" v-loading="busy" row-key="id">
        <ElTableColumn min-width="190" show-overflow-tooltip prop="automationId" label="自动化" />
        <ElTableColumn
          min-width="190"
          show-overflow-tooltip
          prop="automationVersionId"
          label="冻结版本"
        />
        <ElTableColumn show-overflow-tooltip prop="status" label="状态" />
        <ElTableColumn show-overflow-tooltip prop="reasonCode" label="原因" />
        <ElTableColumn show-overflow-tooltip prop="triggerType" label="触发类型" />
        <ElTableColumn min-width="190" show-overflow-tooltip prop="acceptedAt" label="受理时间" />
        <ElTableColumn show-overflow-tooltip prop="attemptCount" label="尝试次数" />
        <ElTableColumn class-name="console-table-actions-cell" min-width="64" label="详情"
          ><template #default="{ row }"
            ><ConsoleTableAction
              :disabled="busy"
              @click="open(row.id)"
              label="运行详情"
              icon="ri:eye-line" /></template
        ></ElTableColumn>
      </ElTable>
      <ElButton v-if="next" :loading="busy" @click="load(true)">更多自动化日志</ElButton>
    </ElCard>
    <ElDialog class="console-dialog" v-model="visible" title="自动化运行详情" destroy-on-close>
      <ElAlert class="console-hint" type="info" show-icon :closable="false"
        >只读状态摘要；不包含原始输入和配置。</ElAlert
      >
      <pre data-testid="automation-execution-detail">{{ JSON.stringify(detail, null, 2) }}</pre>
    </ElDialog>
  </div>
</template>
<script setup lang="ts">
  import ConsoleFilterBar from '@/components/ConsoleFilterBar.vue'
  import ConsoleTableAction from '@/components/ConsoleTableAction.vue'

  import { computed, onBeforeUnmount, ref, watch } from 'vue'
  import { useUserStore } from '@/store/modules/user'
  import { HttpError } from '@/utils/http/error'
  import { listAutomationExecutions, getAutomationExecution } from '@/api/automation-management'
  import type { AutomationExecution, AutomationExecutionDetail } from '@/api/automation-management'
  const user = useUserStore()
  const project = computed(() => user.info.currentProjectId ?? '')
  const allowed = computed(
    () => user.isLogin && !!project.value && !!user.info.buttons?.includes('rule:read')
  )
  const items = ref<AutomationExecution[]>([]),
    detail = ref<AutomationExecutionDetail>()
  const automationId = ref(''),
    status = ref(''),
    range = ref<[Date, Date]>()
  const next = ref<string>(),
    busy = ref(false),
    visible = ref(false),
    error = ref('')
  const statuses = [
    'QUEUED',
    'RUNNING',
    'RETRY_WAIT',
    'DISPATCHED',
    'SKIPPED',
    'FAILED',
    'REJECTED'
  ]
  let generation = 0
  let query: { automationId?: string; status?: string; from?: string; to?: string } = {}
  function clear() {
    generation++
    items.value = []
    detail.value = undefined
    next.value = undefined
    visible.value = false
    busy.value = false
    error.value = ''
    query = {}
    automationId.value = ''
    status.value = ''
    range.value = undefined
  }
  async function run(work: (pid: string, current: () => boolean) => Promise<void>) {
    if (!allowed.value || busy.value) return
    const epoch = generation,
      pid = project.value
    const current = () => epoch === generation && pid === project.value && allowed.value
    busy.value = true
    error.value = ''
    try {
      await work(pid, current)
    } catch (reason) {
      if (current()) {
        if (reason instanceof HttpError && [403, 10003, 50001].includes(reason.code)) clear()
        else error.value = reason instanceof Error ? reason.message : '查询失败'
      }
    } finally {
      if (epoch === generation) busy.value = false
    }
  }
  function load(more = false) {
    return run(async (pid, current) => {
      if (!more)
        query = {
          automationId: automationId.value.trim() || undefined,
          status: status.value || undefined,
          from: range.value?.[0].toISOString(),
          to: range.value?.[1].toISOString()
        }
      const page = await listAutomationExecutions(pid, {
        ...query,
        cursor: more ? next.value : undefined
      })
      if (current()) {
        items.value = more ? [...items.value, ...(page.items ?? [])] : (page.items ?? [])
        next.value = page.nextCursor ?? undefined
      }
    })
  }
  function resetFilters() {
    clear()
    return load(false)
  }
  function open(id: string) {
    return run(async (pid, current) => {
      const value = await getAutomationExecution(pid, id)
      if (current()) {
        detail.value = value
        visible.value = true
      }
    })
  }
  watch(
    () => [project.value, allowed.value, user.info.userId, user.isLogin],
    () => {
      clear()
      void load()
    },
    { immediate: true }
  )
  onBeforeUnmount(clear)
</script>

<style scoped lang="scss">
  .automation-executions__notice {
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
</style>
