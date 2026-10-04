<template>
  <div class="console-page">
    <ElCard v-if="!allowed" shadow="never"
      ><ElAlert title="需要 OWNER 或 ADMIN 的规则管理权限" type="warning" :closable="false"
    /></ElCard>
    <template v-else>
      <div class="console-toolbar console-page-actions"
        ><ElButton type="primary" :icon="Plus" :disabled="busy" @click="create"
          >创建场景</ElButton
        ></div
      >
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
      title="手动场景版本管理"
      width="850px"
      destroy-on-close
      :close-on-click-modal="false"
      :show-close="!pending"
      :close-on-press-escape="!pending"
    >
      <p class="console-description" v-if="selected"
        >状态 {{ selected.status }} · CAS {{ selected.version }} · 活动版本
        {{ selected.activeVersionId ?? '无' }}</p
      >
      <ElForm label-position="top">
        <ElFormItem label="名称"
          ><ElInput v-model="form.name" aria-label="场景名称" maxlength="128" :disabled="busy"
        /></ElFormItem>
        <ElFormItem label="说明"
          ><ElInput
            v-model="form.description"
            aria-label="场景说明"
            maxlength="512"
            :disabled="busy"
        /></ElFormItem>
        <h3 class="console-heading">条件（全部满足；为空时总是执行）</h3>
        <RuleNodeEditor
          :key="`conditions-${editorKey}`"
          v-model="conditions"
          :descriptors="catalog.conditions ?? []"
          :disabled="busy"
          @valid="conditionsValid = $event"
        />
        <h3 class="console-heading">动作（按顺序受理）</h3>
        <RuleNodeEditor
          :key="editorKey"
          v-model="actions"
          :descriptors="catalog.actions ?? []"
          :disabled="busy"
          @valid="editorValid = $event"
        />
        <ElButton
          type="primary"
          :disabled="busy || !editorValid || !conditionsValid || !form.name?.trim()"
          @click="save"
          >保存新版本</ElButton
        >
      </ElForm>
      <template v-if="selected">
        <h3 class="console-heading">不可变历史（最新版本在前）</h3>
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
            >暂停场景</ElButton
          ><ElButton type="danger" :disabled="busy || !!pending" @click="remove">删除场景</ElButton>
        </div>
        <h3 class="console-heading">手动执行</h3>
        <p class="console-description"
          >DISPATCHED 表示动作已受理，不等于送达。结果未知时请使用同键重试。</p
        >
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
          :disabled="busy || !!pending"
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
        <ElInput
          v-model="inputJson"
          type="textarea"
          aria-label="执行载荷"
          :disabled="busy || !!pending"
        />
        <div class="console-actions">
          <ElButton
            :disabled="busy || !!pending || !deviceId || selected.status !== 'ACTIVE'"
            @click="execute(false)"
            >执行场景</ElButton
          >
          <ElButton v-if="pending" :disabled="busy" @click="execute(true)">同键重试</ElButton>
          <ElButton v-if="pending" :disabled="busy" @click="discardPending">结束本次尝试</ElButton>
        </div>
        <pre data-testid="execution-result">{{ executionResult }}</pre>
        <RouterLink to="/rule/executions">查看执行记录及投递状态</RouterLink>
      </template>
      <ElAlert v-if="error" :title="error" type="error" :closable="false" />
    </ElDialog>
  </div>
</template>
<script setup lang="ts">
  import ConsoleFilterBar from '@/components/ConsoleFilterBar.vue'
  import { Plus } from '@element-plus/icons-vue'
  import ConsoleTableAction from '@/components/ConsoleTableAction.vue'

  import ConsoleStatusTag from '@/components/ConsoleStatusTag.vue'
  import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
  import { ElMessageBox } from 'element-plus'
  import { useUserStore } from '@/store/modules/user'
  import * as api from '@/api/scene-management'
  import { fetchSearchDevices, type DeviceResponse } from '@/api/device'
  import type { SceneInput } from '@/api/scene-management'
  import type { ManagedScene, SceneVersion, SceneWrite } from '@/api/scene-management'
  import type { RuleAction, RuleCatalog } from '@/api/rule-management'
  import RuleNodeEditor from '../components/RuleNodeEditor.vue'
  import { HttpError } from '@/utils/http/error'

  defineOptions({ name: 'RuleScenes' })
  const user = useUserStore()
  const project = computed(() => user.info.currentProjectId ?? '')
  const allowed = computed(
    () => user.isLogin && !!project.value && !!user.info.buttons?.includes('rule:manage')
  )
  const items = ref<ManagedScene[]>([]),
    versions = ref<SceneVersion[]>([])
  const selected = ref<ManagedScene>(),
    next = ref<string>(),
    historyNext = ref<string>()
  const catalog = ref<RuleCatalog>({}),
    actions = ref<RuleAction[]>([])
  const filters = reactive({ name: '', status: '' })
  const form = reactive<SceneWrite>({ name: '', description: '', expectedVersion: 1 })
  const visible = ref(false),
    busy = ref(false),
    editorValid = ref(true),
    editorKey = ref(0)
  const inputJson = ref('{}'),
    executionResult = ref(''),
    error = ref('')
  const conditions = ref<RuleAction[]>([]),
    conditionsValid = ref(true)
  const devices = ref<DeviceResponse[]>([]),
    deviceNext = ref<string>(),
    deviceId = ref(''),
    deviceKeyword = ref('')
  const pending = ref<{ scene: string; key: string; input: SceneInput }>()
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
    conditions.value = []
    devices.value = []
    deviceNext.value = undefined
    deviceId.value = ''
    deviceKeyword.value = ''
    pending.value = undefined
    inputJson.value = '{}'
    executionResult.value = ''
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
        if (reason instanceof HttpError && [40041, 403, 10003, 50001].includes(reason.code)) clear()
        else error.value = reason instanceof Error ? reason.message : '操作失败，请刷新后重试'
      }
    } finally {
      if (epoch === generation) busy.value = false
    }
  }
  function load(more = false) {
    return run(async (current, pid) => {
      const page = await api.listScenes(pid, {
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
    conditions.value = []
    pending.value = undefined
    Object.assign(form, { name: '', description: '', expectedVersion: 1 })
    executionResult.value = ''
    inputJson.value = '{}'
    editorKey.value++
    visible.value = true
  }
  function copyVersion(version: SceneVersion) {
    conditions.value = JSON.parse(JSON.stringify(version.conditions ?? []))
    actions.value = JSON.parse(JSON.stringify(version.actions ?? []))
    editorKey.value++
    executionResult.value = ''
  }
  function open(row: ManagedScene) {
    return run(async (current, pid) => {
      const [detail, history] = await Promise.all([
        api.getScene(pid, row.id!),
        api.sceneHistory(pid, row.id!)
      ])
      if (!current()) return
      pending.value = undefined
      selected.value = detail
      Object.assign(form, { name: detail.name, description: detail.description ?? '' })
      versions.value = history.items ?? []
      historyNext.value = history.nextCursor ?? undefined
      inputJson.value = '{}'
      executionResult.value = ''
      if (versions.value[0]) copyVersion(versions.value[0])
      visible.value = true
    })
  }
  function completeNodes(nodes: RuleAction[]) {
    return nodes.map(({ nodeType, config }) => {
      if (config === undefined) throw new Error('节点配置缺失，请显式重建合规版本')
      return { nodeType, config }
    })
  }
  function save() {
    return run(async (current, pid) => {
      const result = await api.saveScene(pid, selected.value?.id ?? '', {
        ...form,
        expectedVersion: selected.value?.version ?? 1,
        actions: completeNodes(actions.value),
        conditions: completeNodes(conditions.value)
      })
      if (!current()) return
      selected.value = result
      const history = await api.sceneHistory(pid, result.id!)
      if (current()) {
        versions.value = history.items ?? []
        historyNext.value = history.nextCursor ?? undefined
        items.value = [result, ...items.value.filter((item) => item.id !== result.id)]
      }
    })
  }
  function activate(version: SceneVersion) {
    return run(async (current, pid) => {
      const row = selected.value!
      await ElMessageBox.confirm(
        `将规则“${row.name}”发布为版本 ${version.versionNumber}？`,
        '确认发布 / 回滚'
      )
      if (!current()) return
      const result = await api.activateScene(pid, row.id!, version.id!, row.version!)
      if (current()) {
        selected.value = result
        items.value = items.value.map((item) => (item.id === result.id ? result : item))
      }
    })
  }
  function pause() {
    return run(async (current, pid) => {
      const row = selected.value!
      const result = await api.pauseScene(pid, row.id!, row.version!)
      if (current()) {
        selected.value = result
        items.value = items.value.map((item) => (item.id === result.id ? result : item))
      }
    })
  }
  function remove() {
    return run(async (current, pid) => {
      const row = selected.value!
      await ElMessageBox.confirm(`删除场景“${row.name}”？历史执行将保留。`, '确认删除')
      if (!current()) return
      await api.deleteScene(pid, row.id!, row.version!)
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
        pending.value = undefined
        executionResult.value = ''
      }
    })
  }
  function moreVersions() {
    return run(async (current, pid) => {
      const page = await api.sceneHistory(pid, selected.value!.id!, historyNext.value)
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
  function execute(retry: boolean) {
    return run(async (current, pid) => {
      if (!retry) {
        if (pending.value) return
        const payload = JSON.parse(inputJson.value)
        if (!payload || Array.isArray(payload) || typeof payload !== 'object')
          throw new Error('执行载荷必须为 JSON 对象')
        pending.value = {
          scene: selected.value!.id!,
          key: crypto.randomUUID(),
          input: { deviceId: deviceId.value, payload }
        }
      }
      const attempt = pending.value
      if (!attempt) return
      const result = await api.executeScene(pid, attempt.scene, attempt.key, attempt.input)
      if (current()) {
        executionResult.value = JSON.stringify(result, null, 2)
        pending.value = undefined
      }
    })
  }
  function discardPending() {
    return run(async (current) => {
      await ElMessageBox.confirm(
        '本次执行可能已经受理。结束尝试不会取消执行，再次点击将产生新请求，是否继续？',
        '结束结果未知的尝试'
      )
      if (current()) pending.value = undefined
    })
  }
  watch(
    () => [project.value, allowed.value, user.info.userId, user.isLogin],
    () => {
      clear()
      if (allowed.value)
        void run(async (current, pid) => {
          const [nodes, page] = await Promise.all([api.ruleCatalog(pid), api.listScenes(pid, {})])
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
