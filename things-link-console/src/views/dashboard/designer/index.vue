<script setup lang="ts">
  import ConsoleTableAction from '@/components/ConsoleTableAction.vue'
  import { Plus } from '@element-plus/icons-vue'

  import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
  import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router'
  import { ElMessageBox } from 'element-plus'
  import { useUserStore } from '@/store/modules/user'
  import { useDashboardDesigner } from '@/composables/useDashboardDesigner'
  import DesignerCanvas from './components/DesignerCanvas.vue'
  import DesignerPublication from './components/DesignerPublication.vue'
  import DesignerSharing from './components/DesignerSharing.vue'
  import DesignerGrants from './components/DesignerGrants.vue'
  import DesignerDeviceBinding from './components/DesignerDeviceBinding.vue'
  import DesignerText from './components/DesignerText.vue'
  import DesignerAlarm from './components/DesignerAlarm.vue'
  import DesignerHistory from './components/DesignerHistory.vue'
  import DesignerVariables from './components/DesignerVariables.vue'
  import DesignerDevicePreview from './components/DesignerDevicePreview.vue'
  import { parseGaugeBounds } from '@/features/dashboard/designer-model'
  import { fetchSearchDevices, type DeviceResponse } from '@/api/device'
  import {
    fetchBindingMetadata,
    createDesignerReadScope,
    type BindingMetadata
  } from '@/api/dashboard-binding'

  defineOptions({ name: 'DashboardDesigner' })
  const route = useRoute()
  const router = useRouter()
  const user = useUserStore()
  const projectId = computed(() => user.info.currentProjectId ?? '')
  const canRead = computed(() => user.info.buttons?.includes('dashboard_definition:read') ?? false)
  const canManage = computed(
    () => user.info.buttons?.includes('dashboard_definition:manage') ?? false
  )
  const editor = useDashboardDesigner(projectId, () => ({
    read: canRead.value,
    create: canManage.value,
    update: canManage.value
  }))
  const { state } = editor
  const bindingDeviceId = ref('')
  const bindingDevices = ref<DeviceResponse[]>([])
  const bindingMetadata = ref<BindingMetadata | null>(null)
  const bindingBusy = ref(false)
  const bindingError = ref('')
  const bindingCursor = ref<string | undefined>()
  let bindingEpoch = 0
  let bindingScope: ReturnType<typeof createDesignerReadScope> | undefined
  function resetBinding() {
    bindingEpoch++
    bindingScope?.close()
    bindingScope = undefined
    bindingDeviceId.value = ''
    bindingDevices.value = []
    bindingMetadata.value = null
    bindingBusy.value = false
    bindingError.value = ''
    bindingCursor.value = undefined
  }
  watch(
    () => [projectId.value, state.dashboardId, state.offline, user.info.userId, canManage.value],
    resetBinding,
    { flush: 'sync' }
  )
  async function loadDevices(next = false) {
    if (bindingBusy.value || state.readonly || state.offline) return
    const epoch = ++bindingEpoch
    bindingBusy.value = true
    bindingMetadata.value = null
    bindingDeviceId.value = ''
    bindingDevices.value = []
    bindingError.value = ''
    try {
      const result = await fetchSearchDevices(projectId.value, {
        limit: 20,
        ...(next && bindingCursor.value ? { cursor: bindingCursor.value } : {})
      })
      if (epoch !== bindingEpoch) return
      if (!Array.isArray(result.items) || result.items.length > 20)
        throw new Error('invalid catalog')
      bindingDevices.value = result.items
      bindingCursor.value = result.hasMore ? (result.nextCursor ?? undefined) : undefined
    } catch {
      if (epoch === bindingEpoch) bindingError.value = '设备目录读取失败，请明确重试。'
    } finally {
      if (epoch === bindingEpoch) bindingBusy.value = false
    }
  }
  async function loadBinding() {
    const epoch = ++bindingEpoch
    bindingMetadata.value = null
    bindingError.value = ''
    if (!bindingDeviceId.value || state.readonly || state.offline) return
    bindingBusy.value = true
    try {
      bindingScope?.close()
      const scope = createDesignerReadScope()
      bindingScope = scope
      const result = await fetchBindingMetadata(projectId.value, bindingDeviceId.value, scope)
      if (epoch === bindingEpoch) bindingMetadata.value = result
    } catch (cause) {
      if (epoch === bindingEpoch)
        bindingError.value =
          cause instanceof Error && cause.message.includes('登录已失效')
            ? '登录已失效，请重新登录后重试。'
            : '设备不可读取、未绑定模型或模型已变化，请重新选择。'
    } finally {
      if (epoch === bindingEpoch) bindingBusy.value = false
    }
  }
  onBeforeUnmount(resetBinding)
  const createVisible = ref(false)
  const managementName = ref('')
  const pageTitle = ref('')
  const propertyLayout = reactive({ x: 0, y: 0, w: 1, h: 1 })
  const activePage = computed(() =>
    state.schema?.pages.find((page) => page.id === state.activePageId)
  )
  const selected = computed(() =>
    activePage.value?.components.find((component) => component.id === state.selectedId)
  )
  const editDisabled = computed(() => state.readonly || state.offline)
  const saveStatus = computed(() =>
    state.conflict
      ? 'conflict'
      : state.saving
        ? 'saving'
        : state.error
          ? 'error'
          : state.dirty
            ? 'dirty'
            : 'saved'
  )
  const saveLabel = computed(
    () =>
      ({
        conflict: '保存冲突 · 本地内容已保留',
        saving: '正在保存…',
        error: '保存已暂停',
        dirty: '有未保存修改',
        saved: '已保存'
      })[saveStatus.value]
  )
  const gaugeMinimum = ref('0'),
    gaugeMaximum = ref('100'),
    gaugeInputError = ref('')
  function applyGaugeBounds() {
    const bounds = parseGaugeBounds(gaugeMinimum.value, gaugeMaximum.value)
    if (!bounds) {
      gaugeInputError.value = '量程须满足最小值小于最大值及配置数值限制。'
      return
    }
    gaugeInputError.value = ''
    editor.updateSelected({ props: bounds })
  }
  watch(selected, (component) => {
    if (component) Object.assign(propertyLayout, component.layout)
    gaugeInputError.value = ''
    if (component?.kind === 'GAUGE' && component.props.scaleMode === 'EXPLICIT') {
      gaugeMinimum.value = String(component.props.min)
      gaugeMaximum.value = String(component.props.max)
    }
  })
  watch(activePage, (page) => {
    pageTitle.value = page?.title ?? ''
  })
  watch(
    () => [projectId.value, route.query.dashboardId, canRead.value, canManage.value] as const,
    async ([project, id, readable]) => {
      if (!project || !readable) return
      if (typeof id === 'string' && /^[a-f0-9-]{36}$/.test(id)) {
        if (state.dashboardId !== id) await editor.open(id)
      } else {
        if (state.dashboardId) editor.close()
        await editor.list()
      }
    },
    { immediate: true }
  )
  watch(
    () => state.dashboardId,
    (id) => {
      if (id && route.query.dashboardId !== id)
        void router.replace({ query: { ...route.query, dashboardId: id } })
    }
  )
  watch(
    () => user.info.userId,
    (next, before) => {
      if (next !== before) editor.close()
    }
  )
  async function create() {
    if (!managementName.value.trim()) return
    await editor.create(managementName.value.trim())
    if (state.dashboardId) createVisible.value = false
  }
  async function confirmDiscard() {
    if (!state.dirty && !state.saving) return true
    try {
      await ElMessageBox.confirm(
        '尚有未保存修改。离开会丢弃本地内容，已经发出的保存请求可能仍会完成。',
        '离开草稿编辑',
        { confirmButtonText: '丢弃并离开', cancelButtonText: '继续编辑', type: 'warning' }
      )
      return true
    } catch {
      return false
    }
  }
  async function back() {
    if (!(await confirmDiscard())) return
    editor.close()
    await router.replace({ query: {} })
  }
  onBeforeRouteLeave(confirmDiscard)
  onBeforeRouteUpdate(async (to, from) => {
    if (
      to.query.dashboardId !== from.query.dashboardId &&
      to.query.dashboardId !== state.dashboardId
    )
      return confirmDiscard()
    return true
  })
  function beforeUnload(event: BeforeUnloadEvent) {
    if (state.dirty || state.saving) {
      event.preventDefault()
      event.returnValue = ''
    }
  }
  onMounted(() => window.addEventListener('beforeunload', beforeUnload))
  onBeforeUnmount(() => {
    window.removeEventListener('beforeunload', beforeUnload)
    editor.dispose()
  })
  function updateText(event: Event) {
    editor.updateSelected({ props: { content: (event.target as HTMLTextAreaElement).value } })
  }
  function updateLayout() {
    if (Object.values(propertyLayout).every(Number.isInteger))
      editor.updateSelected({ layout: { ...propertyLayout } })
  }
  function layout(id: string, value: { x: number; y: number; w: number; h: number }) {
    editor.select(id)
    editor.updateSelected({ layout: value })
  }
</script>

<template>
  <div class="console-page dashboard-designer" v-loading="state.loading">
    <header class="designer-header console-toolbar console-page-actions">
      <ElButton v-if="state.dashboardId" @click="back">返回看板列表</ElButton>
      <ElButton
        v-else-if="canManage && projectId"
        type="primary"
        :icon="Plus"
        data-testid="dashboard-create"
        @click="createVisible = true"
        >创建看板</ElButton
      >
    </header>
    <ElAlert
      v-if="state.offline"
      title="已离线，未保存内容已丢弃，请联网后重新加载。"
      type="warning"
      :closable="false"
    />
    <ElButton
      v-if="
        canRead && !state.schema && !state.loading && typeof route.query.dashboardId === 'string'
      "
      :disabled="state.offline"
      data-testid="designer-reload-after-offline"
      @click="editor.open(route.query.dashboardId as string)"
      >重新加载远端草稿</ElButton
    >
    <ElEmpty v-if="!projectId" description="请先进入项目" />
    <ElAlert
      v-else-if="!canRead"
      title="当前身份没有看板读取权限"
      type="warning"
      :closable="false"
    />
    <template v-else-if="!state.dashboardId">
      <ElCard shadow="never" class="console-table-panel">
        <ElTable :data="state.items" row-key="id" border>
          <ElTableColumn
            show-overflow-tooltip
            prop="managementName"
            label="看板名称"
            min-width="220"
          />
          <ElTableColumn show-overflow-tooltip prop="updatedAt" label="最近更新" min-width="190" />
          <ElTableColumn class-name="console-table-actions-cell" label="操作" width="64"
            ><template #default="{ row }"
              ><ConsoleTableAction
                type="primary"
                :data-testid="`list-edit-${row.id}`"
                @click="editor.open(row.id)"
                :label="canManage ? '编辑草稿' : '查看草稿'"
                icon="ri:edit-box-line" /></template
          ></ElTableColumn>
        </ElTable>
      </ElCard>
      <div class="pager"
        ><div class="console-actions">
          <ElButton @click="editor.list()">首页</ElButton
          ><ElButton :disabled="!state.nextCursor" @click="editor.list(state.nextCursor!)"
            >下一页</ElButton
          >
        </div></div
      >
    </template>
    <template v-else-if="state.schema && canRead">
      <div class="toolbar">
        <ElTag
          data-testid="designer-save-state"
          :data-status="saveStatus"
          :type="state.conflict || state.error ? 'danger' : state.dirty ? 'warning' : 'success'"
          >{{ saveLabel }}</ElTag
        >
        <span class="revision">草稿版本 {{ state.revision }}</span>
        <div class="console-actions">
          <ElButton :disabled="editDisabled || !editor.canUndo.value" @click="editor.undo()"
            >撤销</ElButton
          >
          <ElButton :disabled="editDisabled || !editor.canRedo.value" @click="editor.redo()"
            >重做</ElButton
          >
          <ElButton
            v-if="state.error && !state.conflict && !state.readonly"
            @click="editor.retrySave()"
            >明确重试保存</ElButton
          >
          <ElButton
            v-if="state.conflict"
            data-testid="designer-reload-remote"
            type="warning"
            @click="editor.reloadRemote()"
            >丢弃本地并重载远端</ElButton
          >
        </div>
      </div>
      <ElAlert
        v-if="state.readonly"
        title="当前草稿只读：权限不足或包含当前不支持的组件、变量或模型引用。所有原始结构均保留，不会自动覆盖。"
        type="info"
        :closable="false"
      />
      <div class="designer-management">
        <DesignerPublication
          :project-id="projectId"
          :dashboard-id="state.dashboardId!"
          :draft-revision="state.revision"
          :dirty="state.dirty"
          :saving="state.saving"
          :conflict="state.conflict"
          :available="!state.offline"
          :can-read="canRead"
          :can-manage="canManage"
        />
        <DesignerSharing
          :project-id="projectId"
          :dashboard-id="state.dashboardId!"
          :available="!state.offline"
          :can-manage="canManage"
        />
      </div>
      <div class="console-actions designer-grants">
        <DesignerGrants
          :project-id="projectId"
          :dashboard-id="state.dashboardId!"
          :available="!state.offline"
          :can-manage="canManage"
        />
      </div>
      <DesignerDevicePreview
        :schema="state.schema"
        :page-id="state.activePageId"
        :project-id="projectId"
        :available="canRead && !state.offline"
      />
      <div class="workspace-scroll">
        <div class="workspace">
          <aside class="component-panel">
            <h3 class="console-heading">组件</h3>
            <ElButton
              data-testid="designer-add-text"
              :disabled="editDisabled"
              @click="editor.add('TEXT')"
              >＋ 文本</ElButton
            >
            <ElButton
              data-testid="designer-add-image"
              :disabled="editDisabled"
              @click="editor.add('IMAGE')"
              >＋ 内置图片</ElButton
            >
            <h3 class="console-heading">绑定设备</h3>
            <ElButton :disabled="editDisabled || bindingBusy" @click="loadDevices()"
              >读取设备目录</ElButton
            >
            <ElButton
              :disabled="editDisabled || bindingBusy || !bindingCursor"
              @click="loadDevices(true)"
              >下一页设备</ElButton
            >
            <label
              >绑定设备
              <select
                v-model="bindingDeviceId"
                aria-label="绑定设备"
                :disabled="editDisabled || bindingBusy"
                @change="loadBinding"
              >
                <option value="">请选择设备</option>
                <option v-for="device in bindingDevices" :key="device.id" :value="device.id">{{
                  device.name
                }}</option>
              </select>
            </label>
            <DesignerDeviceBinding
              :schema="state.schema"
              :device-id="bindingDeviceId"
              :model="bindingMetadata?.model ?? null"
              :properties="bindingMetadata?.properties ?? []"
              :busy="bindingBusy"
              :error="bindingError"
              :disabled="editDisabled"
              :can-rebind="
                !!selected &&
                ['VALUE_CARD', 'STATUS', 'GAUGE', 'JSON_VIEW', 'TABLE'].includes(selected.kind)
              "
              :selected="selected"
              @add="editor.addDeviceComponent"
              @rebind="editor.rebindSelectedDevice"
            />
            <DesignerVariables
              :schema="state.schema"
              :selected="selected"
              :project-id="projectId"
              :disabled="editDisabled"
              :binding-model="bindingMetadata?.model"
              @upsert="editor.upsertDeviceVariable"
              @remove="editor.removeDeviceVariable"
              @add="editor.addVariableComponent"
              @rebind="editor.rebindVariableComponent"
            />
            <DesignerHistory
              :schema="state.schema"
              :selected="selected"
              :project-id="projectId"
              :disabled="editDisabled"
              @upsert="editor.upsertTimeRange"
              @remove="editor.removeTimeRange"
              @add="editor.addHistoryComponent"
              @rebind="editor.rebindHistoryComponent"
            />
            <DesignerAlarm
              :schema="state.schema"
              :selected="selected"
              :disabled="editDisabled"
              @add="editor.addAlarmComponent"
              @rebind="editor.rebindAlarmComponent"
            />
            <DesignerText
              :schema="state.schema"
              :selected="selected"
              :disabled="editDisabled"
              @upsert="editor.upsertTextEnum"
              @remove="editor.removeTextEnum"
              @add="editor.addTextComponent"
              @rebind="editor.rebindTextComponent"
            />
            <h3 class="console-heading">页面</h3>
            <button
              v-for="page in state.schema.pages"
              :key="page.id"
              class="page-button"
              :class="{ active: page.id === state.activePageId }"
              @click="editor.setPage(page.id)"
              >{{ page.title }}</button
            >
            <ElButton
              :disabled="
                editDisabled ||
                state.schema.presentation.mode === 'FIXED_SCREEN' ||
                state.schema.pages.length >= 5
              "
              @click="editor.addPage(`页面 ${state.schema.pages.length + 1}`)"
              >添加页面</ElButton
            >
            <h3 class="console-heading">层级</h3>
            <button
              v-for="component in activePage?.components"
              :key="component.id"
              class="page-button"
              :class="{ active: state.selectedId === component.id }"
              @click="editor.select(component.id)"
              >{{ component.kind }} · {{ component.id }}</button
            >
          </aside>
          <DesignerCanvas
            :schema="state.schema"
            :page-id="state.activePageId"
            :selected-id="state.selectedId"
            :readonly="editDisabled"
            @select="editor.select"
            @layout="layout"
          />
          <aside class="property-panel">
            <h3 class="console-heading">画布属性</h3>
            <label
              >主题<select
                :value="state.schema.presentation.theme"
                :disabled="editDisabled"
                @change="
                  editor.setTheme(($event.target as HTMLSelectElement).value as 'LIGHT' | 'DARK')
                "
                ><option value="LIGHT">浅色</option
                ><option value="DARK">深色</option></select
              ></label
            >
            <label
              >画布模式<select
                aria-label="画布模式"
                :value="state.schema.presentation.mode"
                :disabled="editDisabled"
                @change="
                  editor.setPresentation(
                    ($event.target as HTMLSelectElement).value as 'RESPONSIVE_GRID' | 'FIXED_SCREEN'
                  )
                "
                ><option value="RESPONSIVE_GRID">响应式网格</option
                ><option value="FIXED_SCREEN">固定大屏</option></select
              ></label
            >
            <p class="hint console-description">固定大屏仅支持一页，切换不允许静默删页或丢组件。</p>
            <label
              >页面标题<input
                v-model="pageTitle"
                maxlength="128"
                :disabled="editDisabled"
                @change="editor.renamePage(pageTitle)"
            /></label>
            <ElButton
              :disabled="editDisabled || state.schema.pages.length === 1"
              @click="editor.removePage()"
              >删除当前页</ElButton
            >
            <template v-if="selected">
              <h3 class="console-heading">组件属性</h3
              ><p class="hint console-description">{{ selected.kind }} · {{ selected.id }}</p>
              <div class="layout-fields"
                ><label v-for="field in ['x', 'y', 'w', 'h'] as const" :key="field"
                  >{{ field
                  }}<input
                    v-model.number="propertyLayout[field]"
                    :data-testid="`designer-${field}`"
                    type="number"
                    step="1"
                    :disabled="editDisabled"
                    @change="updateLayout" /></label
              ></div>
              <template v-if="selected.kind === 'TEXT'">
                <label v-if="'content' in selected.props"
                  >纯文本内容<textarea
                    data-testid="designer-text-content"
                    :value="selected.props.content"
                    rows="6"
                    :disabled="editDisabled"
                    @input="updateText"
                  />
                </label>
                <label
                  >字号<select
                    :value="selected.props.size"
                    :disabled="editDisabled"
                    @change="
                      editor.updateSelected({
                        props: { size: ($event.target as HTMLSelectElement).value }
                      })
                    "
                    ><option value="SMALL">小</option
                    ><option value="MEDIUM">中</option
                    ><option value="LARGE">大</option></select
                  ></label
                >
                <label
                  >色调<select
                    :value="selected.props.tone"
                    :disabled="editDisabled"
                    @change="
                      editor.updateSelected({
                        props: { tone: ($event.target as HTMLSelectElement).value }
                      })
                    "
                    ><option value="REGULAR">正文</option
                    ><option value="SECONDARY">次要</option
                    ><option value="PRIMARY">主题色</option></select
                  ></label
                >
                <label
                  >对齐<select
                    :value="selected.props.align"
                    :disabled="editDisabled"
                    @change="
                      editor.updateSelected({
                        props: { align: ($event.target as HTMLSelectElement).value }
                      })
                    "
                    ><option value="LEFT">左对齐</option
                    ><option value="CENTER">居中</option
                    ><option value="RIGHT">右对齐</option></select
                  ></label
                >
              </template>
              <template v-else-if="selected.kind === 'IMAGE'">
                <p class="hint console-description"
                  >素材：平台内置 device_mark，不接受外部 URL 或上传。</p
                >
                <label
                  >替代文字<input
                    :value="selected.props.alt"
                    :disabled="editDisabled"
                    @change="
                      editor.updateSelected({
                        props: { alt: ($event.target as HTMLInputElement).value }
                      })
                    "
                /></label>
                <label
                  >填充方式<select
                    :value="selected.props.fit"
                    :disabled="editDisabled"
                    @change="
                      editor.updateSelected({
                        props: { fit: ($event.target as HTMLSelectElement).value }
                      })
                    "
                    ><option value="CONTAIN">完整显示</option
                    ><option value="COVER">覆盖裁切</option></select
                  ></label
                >
              </template>
              <template
                v-if="
                  selected.kind === 'VALUE_CARD' ||
                  selected.kind === 'STATUS' ||
                  selected.kind === 'GAUGE' ||
                  selected.kind === 'JSON_VIEW' ||
                  selected.kind === 'TABLE'
                "
              >
                <label
                  >标题<input
                    :value="selected.props.title ?? ''"
                    :disabled="editDisabled"
                    @change="
                      editor.updateSelected({
                        props: { title: ($event.target as HTMLInputElement).value }
                      })
                    "
                /></label>
                <template v-if="selected.kind === 'VALUE_CARD' || selected.kind === 'GAUGE'">
                  <label
                    >显示小数位<input
                      type="number"
                      min="0"
                      max="6"
                      :value="selected.props.precision"
                      :disabled="editDisabled"
                      @change="
                        editor.updateSelected({
                          props: { precision: Number(($event.target as HTMLInputElement).value) }
                        })
                      "
                  /></label>
                  <label
                    >单位<select
                      :value="selected.props.unitMode"
                      :disabled="editDisabled"
                      @change="
                        editor.updateSelected({
                          props: { unitMode: ($event.target as HTMLSelectElement).value }
                        })
                      "
                      ><option value="MODEL">物模型单位</option
                      ><option value="NONE">不显示单位</option></select
                    ></label
                  >
                </template>
                <label v-if="selected.kind === 'STATUS'"
                  ><input
                    type="checkbox"
                    :checked="selected.props.showLastOnlineAt"
                    :disabled="editDisabled"
                    @change="
                      editor.updateSelected({
                        props: { showLastOnlineAt: ($event.target as HTMLInputElement).checked }
                      })
                    "
                  />显示最后在线时间</label
                >
                <template v-if="selected.kind === 'GAUGE'">
                  <p class="hint console-description"
                    >量程：{{
                      selected.props.scaleMode === 'MODEL' ? '物模型边界' : '显式边界'
                    }}；更换量程来源请使用完整绑定表单重新绑定。</p
                  >
                  <template v-if="selected.props.scaleMode === 'EXPLICIT'">
                    <label
                      >最小值<input
                        v-model="gaugeMinimum"
                        :disabled="editDisabled"
                        aria-label="选中仪表最小值"
                    /></label>
                    <label
                      >最大值<input
                        v-model="gaugeMaximum"
                        :disabled="editDisabled"
                        aria-label="选中仪表最大值"
                    /></label>
                    <p class="console-description" v-if="gaugeInputError">{{ gaugeInputError }}</p>
                    <ElButton :disabled="editDisabled" @click="applyGaugeBounds"
                      >应用仪表量程</ElButton
                    >
                  </template>
                </template>
                <label v-if="selected.kind === 'JSON_VIEW'"
                  >初始展开层数<input
                    type="number"
                    min="0"
                    max="2"
                    :value="selected.props.initialExpandDepth"
                    :disabled="editDisabled"
                    @change="
                      editor.updateSelected({
                        props: {
                          initialExpandDepth: Number(($event.target as HTMLInputElement).value)
                        }
                      })
                    "
                /></label>
                <label v-if="selected.kind === 'TABLE'"
                  >每页行数<input
                    type="number"
                    min="1"
                    max="256"
                    :value="selected.props.rowLimit"
                    :disabled="editDisabled"
                    @change="
                      editor.updateSelected({
                        props: { rowLimit: Number(($event.target as HTMLInputElement).value) }
                      })
                    "
                /></label>
              </template>
              <ElButton
                type="danger"
                plain
                :disabled="editDisabled"
                @click="editor.removeSelected()"
                >删除组件</ElButton
              >
            </template>
            <p v-else class="hint console-description">在画布或层级中选择组件后编辑属性。</p>
          </aside>
        </div>
      </div>
    </template>
    <ElAlert v-if="state.error" class="error" :title="state.error" type="error" :closable="false" />
    <ElDialog
      class="console-dialog"
      v-model="createVisible"
      title="创建看板"
      width="420px"
      :close-on-click-modal="false"
    >
      <label class="create-label"
        >看板名称<input
          v-model="managementName"
          :disabled="state.creating"
          data-testid="dashboard-name"
          maxlength="128"
          autocomplete="off"
          @keydown.enter="create"
      /></label>
      <p class="console-description" v-if="state.error" role="alert">{{ state.error }}</p
      ><template #footer
        ><ElButton :disabled="state.creating" @click="createVisible = false">关闭</ElButton
        ><ElButton
          type="primary"
          data-testid="dashboard-create-confirm"
          :loading="state.creating"
          :disabled="!managementName.trim()"
          @click="create"
          >{{ state.error ? '使用原名称明确重试' : '创建' }}</ElButton
        ></template
      >
    </ElDialog>
  </div>
</template>

<style scoped>
  .dashboard-designer {
    min-width: 0;
    padding: 10px;
  }
  .designer-header,
  .toolbar,
  .pager {
    display: flex;
    flex-wrap: wrap;
    gap: 10px;
    align-items: center;
    margin-bottom: 10px;
  }
  .designer-header {
    justify-content: space-between;
  }
  .designer-header h2 {
    margin: 0 0 8px;
    font-size: 18px;
  }
  .designer-header p,
  .hint,
  .revision {
    font-size: 13px;
    line-height: 1.6;
    color: var(--el-text-color-secondary);
  }
  .designer-grants {
    margin-top: 10px;
  }
  .designer-management {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 10px;
    align-items: start;
  }
  .designer-management :deep(.console-editor-section) {
    margin: 0;
  }
  @media (width <= 900px) {
    .designer-management {
      grid-template-columns: minmax(0, 1fr);
    }
  }
  .workspace-scroll {
    margin-top: 10px;
    overflow: auto;
  }
  .workspace {
    display: grid;
    grid-template-columns: 220px minmax(800px, 1fr) 240px;
    gap: 10px;
    align-items: start;
  }
  .component-panel,
  .property-panel {
    min-width: 0;
    padding: 12px;
    background: var(--el-bg-color);
    border: 1px solid var(--el-border-color);
    border-radius: 8px;
  }
  h3 {
    margin: 8px 0 12px;
    font-size: 14px;
  }
  .component-panel .el-button {
    display: block;
    width: 100%;
    margin: 8px 0;
  }
  .page-button {
    display: block;
    width: 100%;
    padding: 8px;
    margin: 6px 0;
    color: var(--el-text-color-primary);
    text-align: left;
    overflow-wrap: anywhere;
    background: transparent;
    border: 1px solid var(--el-border-color);
    border-radius: 5px;
  }
  .page-button.active {
    color: var(--el-color-primary);
    border-color: var(--el-color-primary);
  }
  label {
    display: block;
    margin: 12px 0;
    font-size: 13px;
  }
  input,
  textarea,
  select {
    box-sizing: border-box;
    display: block;
    width: 100%;
    padding: 8px;
    margin-top: 6px;
    color: var(--el-text-color-primary);
    background: var(--el-fill-color-blank);
    border: 1px solid var(--el-border-color);
    border-radius: 5px;
  }
  .layout-fields {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 8px;
  }
  .layout-fields label {
    margin: 4px 0;
  }
  .error {
    margin-top: 10px;
  }
  .pager {
    margin-top: 10px;
  }
</style>
