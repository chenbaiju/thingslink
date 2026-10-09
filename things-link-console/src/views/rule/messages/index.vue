<template>
  <div class="console-page message-rules">
    <ConsoleWorkspaceHeader
      project-style
      title="消息规则"
      description="处理上行数据并配置动作。先保存草稿与调试，再明确启用版本。"
    >
      <template #actions>
        <ElButton v-if="allowed" type="primary" :icon="Plus" :disabled="busy" @click="create">
          创建规则
        </ElButton>
      </template>
    </ConsoleWorkspaceHeader>
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
          <ElTableColumn label="更新时间"
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
      title="消息规则版本管理"
      width="850px"
      destroy-on-close
      :close-on-click-modal="false"
    >
      <p class="console-description" v-if="selected"
        >状态 {{ selected.status }} · CAS {{ selected.version }} · 活动版本
        {{ selected.activeVersionId ?? '无' }}</p
      >
      <ElForm label-position="top">
        <ElFormItem label="名称"
          ><ElInput v-model="form.name" aria-label="规则名称" maxlength="128" :disabled="busy"
        /></ElFormItem>
        <ElFormItem label="说明"
          ><ElInput
            v-model="form.description"
            aria-label="规则说明"
            maxlength="512"
            :disabled="busy"
        /></ElFormItem>
        <ElFormItem label="JavaScript 函数源码"
          ><ElInput
            v-model="form.source"
            aria-label="规则源码"
            type="textarea"
            :rows="6"
            :disabled="busy"
        /></ElFormItem>
        <RuleNodeEditor
          :key="editorKey"
          v-model="actions"
          :descriptors="catalog.actions ?? []"
          :disabled="busy"
          @valid="editorValid = $event"
        />
        <ElButton
          type="primary"
          :disabled="busy || !editorValid || !form.name?.trim()"
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
          <ElTableColumn class-name="console-table-actions-cell" min-width="144" label="操作"
            ><template #default="{ row }"
              ><ConsoleTableAction
                :disabled="busy"
                @click="copyVersion(row)"
                label="载入编辑"
                icon="ri:edit-box-line" /><ConsoleTableAction
                :disabled="busy"
                @click="activate(row)"
                label="发布此版本"
                icon="ri:send-plane-line" /><ConsoleTableAction
                :disabled="busy"
                @click="debug(row)"
                label="调试此版本"
                icon="ri:bug-line" /></template
          ></ElTableColumn>
        </ElTable>
        <div class="console-actions">
          <ElButton v-if="historyNext" :disabled="busy" @click="moreVersions">更多历史</ElButton>
          <ElButton :disabled="busy || selected.status !== 'ACTIVE'" @click="pause"
            >暂停规则</ElButton
          ><ElButton type="danger" :disabled="busy" @click="remove">删除规则</ElButton>
        </div>
        <ElFormItem label="调试样例 JSON（无动作副作用）"
          ><ElInput v-model="inputJson" type="textarea" aria-label="调试样例"
        /></ElFormItem>
        <pre data-testid="debug-result">{{ debugResult }}</pre>
      </template>
      <ElAlert v-if="error" :title="error" type="error" :closable="false" show-icon />
    </ElDialog>
  </div>
</template>
<script setup lang="ts">
  import ConsoleWorkspaceHeader from '@/components/business/ConsoleWorkspaceHeader.vue'
  import ConsoleFilterBar from '@/components/ConsoleFilterBar.vue'
  import { Plus } from '@element-plus/icons-vue'
  import ConsoleTableAction from '@/components/ConsoleTableAction.vue'

  import ConsoleStatusTag from '@/components/ConsoleStatusTag.vue'
  import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
  import { ElMessageBox } from 'element-plus'
  import { useUserStore } from '@/store/modules/user'
  import * as api from '@/api/rule-management'
  import type {
    MessageRule,
    MessageVersion,
    RuleWrite,
    RuleAction,
    RuleCatalog
  } from '@/api/rule-management'
  import RuleNodeEditor from '../components/RuleNodeEditor.vue'
  import { HttpError } from '@/utils/http/error'

  defineOptions({ name: 'MessageRules' })
  const user = useUserStore()
  const project = computed(() => user.info.currentProjectId ?? '')
  const allowed = computed(
    () => user.isLogin && !!project.value && !!user.info.buttons?.includes('rule:manage')
  )
  const items = ref<MessageRule[]>([]),
    versions = ref<MessageVersion[]>([])
  const selected = ref<MessageRule>(),
    next = ref<string>(),
    historyNext = ref<string>()
  const catalog = ref<RuleCatalog>({}),
    actions = ref<RuleAction[]>([])
  const filters = reactive({ name: '', status: '' })
  const form = reactive<RuleWrite>({ name: '', description: '', source: 'input => input' })
  const visible = ref(false),
    busy = ref(false),
    editorValid = ref(true),
    editorKey = ref(0)
  const inputJson = ref('{}'),
    debugResult = ref(''),
    error = ref('')
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
    form.source = ''
    inputJson.value = '{}'
    debugResult.value = ''
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
        if (reason instanceof HttpError && [40033, 403, 10003, 50001].includes(reason.code)) clear()
        else error.value = reason instanceof Error ? reason.message : '操作失败，请刷新后重试'
      }
    } finally {
      if (epoch === generation) busy.value = false
    }
  }
  function load(more = false) {
    return run(async (current, pid) => {
      const page = await api.listRules(pid, {
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
    selected.value = undefined
    versions.value = []
    historyNext.value = undefined
    actions.value = []
    Object.assign(form, { name: '', description: '', source: 'input => input' })
    debugResult.value = ''
    inputJson.value = '{}'
    editorKey.value++
    visible.value = true
  }
  function copyVersion(version: MessageVersion) {
    form.source = version.source ?? ''
    actions.value = JSON.parse(JSON.stringify(version.actions ?? []))
    editorKey.value++
    debugResult.value = ''
  }
  function open(row: MessageRule) {
    return run(async (current, pid) => {
      const [detail, history] = await Promise.all([
        api.getRule(pid, row.id!),
        api.ruleHistory(pid, row.id!)
      ])
      if (!current()) return
      selected.value = detail
      Object.assign(form, { name: detail.name, description: detail.description ?? '' })
      versions.value = history.items ?? []
      historyNext.value = history.nextCursor ?? undefined
      inputJson.value = '{}'
      debugResult.value = ''
      if (versions.value[0]) copyVersion(versions.value[0])
      visible.value = true
    })
  }
  function save() {
    return run(async (current, pid) => {
      const result = await api.saveRule(pid, selected.value?.id ?? '', {
        ...form,
        expectedVersion: selected.value?.version,
        actions: actions.value
      })
      if (!current()) return
      selected.value = result
      const history = await api.ruleHistory(pid, result.id!)
      if (current()) {
        versions.value = history.items ?? []
        historyNext.value = history.nextCursor ?? undefined
        items.value = [result, ...items.value.filter((item) => item.id !== result.id)]
      }
    })
  }
  function activate(version: MessageVersion) {
    return run(async (current, pid) => {
      const row = selected.value!
      await ElMessageBox.confirm(
        `将规则“${row.name}”发布为版本 ${version.versionNumber}？`,
        '确认发布 / 回滚'
      )
      if (!current()) return
      const result = await api.activateRule(pid, row.id!, version.id!, row.version!)
      if (current()) {
        selected.value = result
        items.value = items.value.map((item) => (item.id === result.id ? result : item))
      }
    })
  }
  function pause() {
    return run(async (current, pid) => {
      const row = selected.value!
      const result = await api.pauseRule(pid, row.id!, row.version!)
      if (current()) {
        selected.value = result
        items.value = items.value.map((item) => (item.id === result.id ? result : item))
      }
    })
  }
  function remove() {
    return run(async (current, pid) => {
      const row = selected.value!
      await ElMessageBox.confirm(`删除规则“${row.name}”？历史执行将保留。`, '确认删除')
      if (!current()) return
      await api.deleteRule(pid, row.id!, row.version!)
      if (current()) {
        visible.value = false
        items.value = items.value.filter((item) => item.id !== row.id)
        selected.value = undefined
        versions.value = []
        actions.value = []
        form.source = ''
        debugResult.value = ''
      }
    })
  }
  function moreVersions() {
    return run(async (current, pid) => {
      const page = await api.ruleHistory(pid, selected.value!.id!, historyNext.value)
      if (current()) {
        versions.value.push(...(page.items ?? []))
        historyNext.value = page.nextCursor ?? undefined
      }
    })
  }
  function debug(version: MessageVersion) {
    return run(async (current, pid) => {
      const result = await api.debugRule(pid, selected.value!.id!, version.id!, inputJson.value)
      if (current()) debugResult.value = JSON.stringify(result, null, 2)
    })
  }
  watch(
    () => [project.value, allowed.value, user.info.userId, user.isLogin],
    () => {
      clear()
      if (allowed.value)
        void run(async (current, pid) => {
          const [nodes, page] = await Promise.all([api.ruleCatalog(pid), api.listRules(pid, {})])
          if (current()) {
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
  .message-rules :deep(.workspace-header > .console-actions) {
    margin-bottom: 0;
  }
</style>
