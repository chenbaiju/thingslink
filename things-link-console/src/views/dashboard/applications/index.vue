<script setup lang="ts">
  import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
  import { onBeforeRouteLeave } from 'vue-router'
  import ApplicationPublication from './components/ApplicationPublication.vue'
  import { ElMessageBox } from 'element-plus'
  import { useUserStore } from '@/store/modules/user'
  import { currentIdentityEpoch } from '@/utils/http/identity-scope'
  import {
    createApplicationEditor,
    deniedApplicationAccess,
    parseContent,
    uuid
  } from '@/features/application/editor-model'
  import {
    fetchApplications,
    fetchApplicationDraft,
    createApplication,
    saveApplicationDraft,
    type ApplicationCatalog
  } from '@/api/application'
  import { fetchDashboards, type DashboardCatalog } from '@/api/dashboard'
  import { fetchDashboardPublicationHistory } from '@/api/dashboard-publication'
  import type { components } from '@/types/api/schema'

  defineOptions({ name: 'ApplicationManager' })
  const user = useUserStore()
  const project = computed(() => user.info.currentProjectId ?? '')
  const canRead = computed(
    () => !!project.value && !!user.info.buttons?.includes('application:read')
  )
  const canManage = computed(
    () => canRead.value && !!user.info.buttons?.includes('application:manage')
  )
  const canChoose = computed(() => !!user.info.buttons?.includes('dashboard_definition:read'))
  const publicationPending = ref(false),
    publicationWorking = ref(false)
  const online = ref(navigator.onLine)
  let disposed = false,
    epoch = 0,
    choiceEpoch = 0
  const context = () =>
    `${project.value}:${user.info.userId}:${currentIdentityEpoch()}:${canRead.value}:${canManage.value}:${online.value}:${epoch}`
  const editor = createApplicationEditor({
    context,
    readable: () => canRead.value && online.value && !disposed,
    writable: () => canManage.value && online.value && !disposed,
    draft: (id) => fetchApplicationDraft(project.value, id),
    create: (name, content, key) => createApplication(project.value, name, content, key),
    save: (id, revision, content, key) =>
      saveApplicationDraft(project.value, id, revision, content, key),
    key: () => crypto.randomUUID(),
    changed: (next) => Object.assign(state, next)
  })
  const state = reactive(editor.snapshot())
  const items = ref<ApplicationCatalog[]>([]),
    cursor = ref<string>(),
    loading = ref(false)
  const pageError = ref(''),
    name = ref(''),
    remote = ref<string>('')
  const dashboards = ref<DashboardCatalog[]>([]),
    dashboardCursor = ref<string>(),
    dashboardId = ref('')
  const versions = ref<components['schemas']['DashboardVersionSummaryResponse'][]>([]),
    versionCursor = ref<string>(),
    versionId = ref(''),
    navTitle = ref('')
  const choosing = ref(false)
  let comparison = 0
  watch(
    () => state.id,
    () => {
      comparison++
      remote.value = ''
    },
    { flush: 'sync' }
  )
  const versionsFor = ref('')
  const disabled = computed(() => !canManage.value || !online.value || state.busy || state.blocked)
  const text = (event: Event) => (event.target as HTMLInputElement).value
  function invalidate() {
    epoch++
    comparison++
    editor.reset()
    items.value = []
    cursor.value = undefined
    loading.value = false
    dashboards.value = []
    versions.value = []
    dashboardId.value = ''
    versionId.value = ''
    dashboardCursor.value = undefined
    versionCursor.value = undefined
    choosing.value = false
    remote.value = ''
    name.value = ''
    pageError.value = ''
  }
  function offline() {
    online.value = false
    invalidate()
    pageError.value = '已离线，编辑内容已清理；联网后请重新读取。'
  }
  function connected() {
    online.value = true
  }
  window.addEventListener('offline', offline)
  window.addEventListener('online', connected)
  watch(
    [
      () => project.value,
      () => user.info.userId,
      () => user.info.tenantId,
      () => canRead.value,
      () => canManage.value
    ],
    invalidate,
    { flush: 'sync' }
  )
  // 看板选择权限变化只清引用候选，不能丢掉仍属当前应用身份的发布恢复键。
  watch(
    canChoose,
    () => {
      choiceEpoch++
      dashboards.value = []
      versions.value = []
      dashboardId.value = ''
      versionId.value = ''
      dashboardCursor.value = undefined
      versionCursor.value = undefined
      versionsFor.value = ''
      choosing.value = false
    },
    { flush: 'sync' }
  )
  function accessDenied() {
    invalidate()
    pageError.value = '应用或读取权限已失效，编辑及发布内容已清理。'
  }
  onBeforeUnmount(() => {
    disposed = true
    invalidate()
    window.removeEventListener('offline', offline)
    window.removeEventListener('online', connected)
  })
  async function discard() {
    if (publicationWorking.value) return false
    if (!state.dirty && !state.creatingUnknown && !publicationPending.value) return true
    try {
      await ElMessageBox.confirm(
        '离开将丢弃本地编辑及尚未确认的创建或发布恢复信息；离开不会撤销已提交的请求。是否继续？',
        '离开应用编辑'
      )
      return true
    } catch {
      return false
    }
  }
  onBeforeRouteLeave(discard)
  function page<T>(value: { items?: T[]; hasMore?: boolean; nextCursor?: string | null }) {
    if (
      !Array.isArray(value.items) ||
      value.items.length > 20 ||
      typeof value.hasMore !== 'boolean' ||
      (value.hasMore && !value.nextCursor)
    )
      throw Error('分页响应不完整')
    return { items: value.items, cursor: value.hasMore ? value.nextCursor! : undefined }
  }
  async function list(next = false) {
    if (!canRead.value || !online.value || loading.value || state.busy || state.creatingUnknown)
      return
    const identity = context()
    loading.value = true
    pageError.value = ''
    items.value = []
    try {
      const result = page(await fetchApplications(project.value, next ? cursor.value : undefined))
      if (identity !== context() || disposed) return
      if (result.items.some((item) => !uuid(item.id) || !item.managementName))
        throw Error('应用目录身份无效')
      items.value = result.items
      cursor.value = result.cursor
    } catch {
      if (identity === context()) {
        cursor.value = undefined
        pageError.value = '应用目录读取失败，请重试。'
      }
    } finally {
      if (identity === context()) loading.value = false
    }
  }
  async function open(id: string) {
    if (state.busy || state.creatingUnknown || !(await discard())) return
    epoch++
    versions.value = []
    versionsFor.value = ''
    choosing.value = false
    editor.reset()
    remote.value = ''
    await editor.open(id)
  }
  async function reload() {
    const id = state.id,
      identity = context()
    if (!id || state.busy) return
    try {
      await ElMessageBox.confirm('将丢弃本地内容并读取最新远端草稿，发布版本不变。', '重载远端')
    } catch {
      return
    }
    if (identity !== context()) return
    remote.value = ''
    await editor.open(id)
  }
  async function compare() {
    if (!state.id || state.busy || !canRead.value || !online.value) return
    const identity = context(),
      id = state.id,
      request = ++comparison
    remote.value = ''
    pageError.value = ''
    try {
      const draft = await fetchApplicationDraft(project.value, id)
      if (
        identity === context() &&
        request === comparison &&
        id === state.id &&
        draft.applicationId === id
      )
        remote.value = JSON.stringify(
          { revision: draft.revision, content: parseContent(draft.content) },
          null,
          2
        )
    } catch (error) {
      if (identity !== context() || request !== comparison) return
      if (deniedApplicationAccess(error)) {
        invalidate()
        pageError.value = '读取权限或应用已失效，编辑内容已清理。'
      } else pageError.value = '远端比较读取失败，本地内容保持不变。'
    }
  }
  async function loadDashboards(next = false) {
    if (disabled.value || !canChoose.value || choosing.value) return
    const identity = context(),
      choice = choiceEpoch
    choosing.value = true
    pageError.value = ''
    dashboards.value = []
    versions.value = []
    versionsFor.value = ''
    dashboardId.value = ''
    versionId.value = ''
    versionCursor.value = undefined
    try {
      const result = page(
        await fetchDashboards(project.value, next ? dashboardCursor.value : undefined)
      )
      if (identity === context() && choice === choiceEpoch && canChoose.value) {
        dashboards.value = result.items
        dashboardCursor.value = result.cursor
      }
    } catch {
      if (identity === context() && choice === choiceEpoch && canChoose.value) {
        dashboardCursor.value = undefined
        pageError.value = '看板目录读取失败。'
      }
    } finally {
      if (identity === context() && choice === choiceEpoch && canChoose.value)
        choosing.value = false
    }
  }
  async function loadVersions(next = false) {
    if (disabled.value || !canChoose.value || choosing.value || !uuid(dashboardId.value)) return
    const identity = context(),
      choice = choiceEpoch,
      id = dashboardId.value
    choosing.value = true
    versions.value = []
    versionId.value = ''
    pageError.value = ''
    navTitle.value = dashboards.value.find((d) => d.id === id)?.managementName ?? ''
    try {
      const result = page(
        await fetchDashboardPublicationHistory(
          project.value,
          id,
          next ? versionCursor.value : undefined
        )
      )
      if (
        identity === context() &&
        choice === choiceEpoch &&
        canChoose.value &&
        id === dashboardId.value
      ) {
        if (result.items.some((v) => !uuid(v.id) || !v.versionNumber)) throw Error('版本目录无效')
        versions.value = result.items
        versionsFor.value = id
        versionCursor.value = result.cursor
      }
    } catch {
      if (identity === context() && choice === choiceEpoch && canChoose.value) {
        versionCursor.value = undefined
        pageError.value = '不可变版本读取失败。'
      }
    } finally {
      if (identity === context() && choice === choiceEpoch && canChoose.value)
        choosing.value = false
    }
  }
  function add() {
    if (
      !canChoose.value ||
      versionsFor.value !== dashboardId.value ||
      !versions.value.some((v) => v.id === versionId.value)
    )
      return
    try {
      const next = parseContent(JSON.parse(JSON.stringify(state.content)))
      const existing = next.dashboardRefs.find((ref) => ref.dashboardId === dashboardId.value)
      const ref = {
        dashboardId: dashboardId.value,
        dashboardVersionId: versionId.value,
        title: navTitle.value
      }
      if (existing) Object.assign(existing, ref)
      else next.dashboardRefs.push(ref)
      // 第一个引用由这次明确添加设为入口；后续删除入口要求先手动改选，绝不暗换。
      if (!next.entryDashboardId) next.entryDashboardId = ref.dashboardId
      parseContent(next)
      editor.change((content) => Object.assign(content, next))
      pageError.value = ''
    } catch (e) {
      pageError.value = (e as Error).message
    }
  }
  function move(index: number, delta: number) {
    editor.change((c) => {
      const target = index + delta
      if (target >= 0 && target < c.dashboardRefs.length)
        [c.dashboardRefs[index], c.dashboardRefs[target]] = [
          c.dashboardRefs[target],
          c.dashboardRefs[index]
        ]
    })
  }
  function remove(index: number) {
    editor.change((c) => {
      if (c.dashboardRefs[index].dashboardId === c.entryDashboardId && c.dashboardRefs.length > 1) {
        pageError.value = '请先选择其他入口，再移除此看板。'
        return
      }
      c.dashboardRefs.splice(index, 1)
      if (!c.dashboardRefs.length) c.entryDashboardId = null
    })
  }
  async function create() {
    if (
      publicationWorking.value ||
      ((state.dirty || publicationPending.value) && !(await discard()))
    )
      return
    await editor.create(name.value)
  }
</script>

<template>
  <main class="console-page application-manager">
    <el-alert
      v-if="!canRead"
      title="请先选择项目并取得应用读取权限。"
      type="info"
      :closable="false"
    />
    <el-alert
      v-if="pageError || state.error"
      :title="pageError || state.error"
      type="error"
      :closable="false"
    />
    <el-alert v-if="state.notice" :title="state.notice" type="success" :closable="false" />
    <template v-if="canRead">
      <section class="application-panel application-catalog" aria-label="应用目录">
        <h3 class="console-heading">应用目录</h3>
        <div class="console-actions">
          <el-button
            :disabled="loading || state.busy || !online || state.creatingUnknown"
            @click="list()"
            >读取应用目录</el-button
          >
          <el-button
            :disabled="loading || state.busy || !online || !cursor || state.creatingUnknown"
            @click="list(true)"
            >下一页应用</el-button
          >
        </div>
        <ul
          ><li v-for="item in items" :key="item.id"
            ><span>{{ item.managementName }}</span>
            <el-button
              :disabled="state.busy || state.creatingUnknown || !online"
              @click="open(item.id!)"
              >{{ canManage ? '编辑' : '查看' }}</el-button
            ></li
          ></ul
        >
        <ElEmpty
          v-if="!loading && !items.length"
          description="当前未显示应用，请读取目录或创建应用。"
          :image-size="88"
        />
      </section>
      <section v-if="canManage" class="application-panel" aria-label="创建应用">
        <h3 class="console-heading">创建应用</h3>
        <label
          >管理名称
          <input
            v-model="name"
            aria-label="管理名称"
            :disabled="state.busy || state.creatingUnknown || !online"
        /></label>
        <el-button type="primary" :disabled="state.busy || !online" @click="create">{{
          state.creatingUnknown ? '重试原创建请求' : '创建应用'
        }}</el-button>
      </section>
      <ApplicationPublication
        v-if="state.id"
        :key="state.id"
        :project-id="project"
        :application-id="state.id"
        :draft-revision="state.revision"
        :dirty="state.dirty"
        :saving="state.busy"
        :conflict="state.blocked"
        :available="online && !disposed"
        :can-read="canRead"
        :can-manage="canManage"
        @pending="publicationPending = $event"
        @working="publicationWorking = $event"
        @access-denied="accessDenied"
      />
      <section
        v-if="state.id"
        class="application-panel"
        aria-label="应用草稿"
        :data-application-id="state.id"
      >
        <h3 class="console-heading">组合编辑</h3>
        <p class="console-description"
          >草稿修订 {{ state.revision }} · {{ state.dirty ? '未保存' : '已读取/保存' }}</p
        >
        <div class="console-actions">
          <el-button :disabled="state.busy || !online" @click="compare">读取远端比较</el-button>
          <el-button :disabled="state.busy || !online" @click="reload"
            >丢弃本地并重载远端</el-button
          >
        </div>
        <details v-if="remote" open
          ><summary>远端草稿（只读比较）</summary><pre>{{ remote }}</pre>
        </details>
        <template v-if="state.content">
          <label
            >公开展示名
            <input
              :value="state.content.displayName"
              aria-label="公开展示名"
              :disabled="disabled"
              @input="
                editor.change((c) => {
                  c.displayName = text($event)
                })
              "
          /></label>
          <label
            >最低宿主版本（含）
            <input
              :value="state.content.hostCompatibility.minInclusive"
              aria-label="最低宿主版本"
              :disabled="disabled"
              @input="
                editor.change((c) => {
                  c.hostCompatibility.minInclusive = text($event)
                })
              "
          /></label>
          <label
            >最高宿主版本（不含）
            <input
              :value="state.content.hostCompatibility.maxExclusive"
              aria-label="最高宿主版本"
              :disabled="disabled"
              @input="
                editor.change((c) => {
                  c.hostCompatibility.maxExclusive = text($event)
                })
              "
          /></label>
          <p class="console-description">宿主范围只是草稿设置，发布时服务端仍会验证实际兼容性。</p>
          <ol
            ><li v-for="(item, index) in state.content.dashboardRefs" :key="item.dashboardId">
              <label
                >导航标题
                <input
                  :value="item.title"
                  :aria-label="`导航标题 ${index + 1}`"
                  :disabled="disabled"
                  @input="
                    editor.change((c) => {
                      c.dashboardRefs[index].title = text($event)
                    })
                  "
              /></label>
              <small>看板 {{ item.dashboardId }} · 固定版本 {{ item.dashboardVersionId }}</small>
              <div class="console-actions">
                <el-button :disabled="disabled || index === 0" @click="move(index, -1)"
                  >上移 {{ index + 1 }}</el-button
                >
                <el-button
                  :disabled="disabled || index === state.content.dashboardRefs.length - 1"
                  @click="move(index, 1)"
                  >下移 {{ index + 1 }}</el-button
                >
                <el-button :disabled="disabled" @click="remove(index)"
                  >移除 {{ index + 1 }}</el-button
                >
              </div>
            </li></ol
          >
          <label
            >入口看板
            <select
              :value="state.content.entryDashboardId ?? ''"
              aria-label="入口看板"
              :disabled="disabled || !state.content.dashboardRefs.length"
              @change="
                editor.change((c) => {
                  c.entryDashboardId = text($event) || null
                })
              "
            >
              <option v-if="!state.content.dashboardRefs.length" value="">暂无引用</option>
              <option
                v-for="item in state.content.dashboardRefs"
                :key="item.dashboardId"
                :value="item.dashboardId"
                >{{ item.title }}</option
              >
            </select></label
          >
          <fieldset v-if="canManage && canChoose" :disabled="disabled || choosing">
            <legend>添加或替换看板版本</legend>
            <div class="console-actions">
              <el-button :disabled="disabled || choosing" @click="loadDashboards()"
                >读取看板目录</el-button
              >
              <el-button
                :disabled="disabled || choosing || !dashboardCursor"
                @click="loadDashboards(true)"
                >下一页看板</el-button
              >
            </div>
            <label
              >看板
              <select v-model="dashboardId" aria-label="看板" @change="loadVersions()"
                ><option value="">请选择</option
                ><option v-for="d in dashboards" :key="d.id" :value="d.id">{{
                  d.managementName
                }}</option></select
              ></label
            >
            <label
              >不可变版本
              <select v-model="versionId" aria-label="不可变版本"
                ><option value="">请选择已发布版本</option
                ><option v-for="v in versions" :key="v.id" :value="v.id"
                  >版本 {{ v.versionNumber }}</option
                ></select
              ></label
            >
            <el-button
              :disabled="disabled || choosing || !versionCursor"
              @click="loadVersions(true)"
              >下一页看板版本</el-button
            >
            <label>新导航标题 <input v-model="navTitle" aria-label="新导航标题" /></label>
            <el-button :disabled="disabled || choosing || !versionId" @click="add"
              >添加或替换引用</el-button
            >
          </fieldset>
          <el-button
            v-if="canManage"
            type="primary"
            :disabled="disabled || !state.dirty"
            @click="editor.save()"
            >保存应用草稿</el-button
          >
          <details v-if="state.blocked"
            ><summary>保留的本地内容</summary
            ><pre>{{ JSON.stringify(state.content, null, 2) }}</pre>
          </details>
        </template>
      </section>
    </template>
  </main>
</template>

<style scoped lang="scss">
  .application-manager {
    padding: 10px;
    section {
      margin-block: 10px;
    }
    .application-panel {
      padding: 10px;
      background: var(--el-bg-color);
      border: 1px solid var(--el-border-color-lighter);
      border-radius: calc(var(--custom-radius) + 4px);
    }
    label {
      display: flex;
      flex-direction: column;
      gap: 6px;
      max-width: 420px;
      margin-block: 10px;
      font-size: 13px;
    }
    input,
    select {
      box-sizing: border-box;
      width: 100%;
      max-width: 100%;
      min-height: 36px;
      padding: 8px 10px;
      color: inherit;
      background: var(--el-bg-color);
      border: 1px solid var(--el-border-color);
      border-radius: var(--el-border-radius-base);
    }
    ul,
    ol {
      padding: 0;
      list-style: none;
    }
    li {
      padding: 10px;
      margin-block: 10px;
      background: var(--el-fill-color-light);
      border: 1px solid var(--el-border-color-lighter);
      border-radius: var(--el-border-radius-base);
    }
    .application-catalog li {
      display: flex;
      gap: 10px;
      align-items: center;
      justify-content: space-between;
      span {
        min-width: 0;
        overflow-wrap: anywhere;
      }
      .el-button {
        flex-shrink: 0;
      }
    }
    small {
      display: block;
      overflow-wrap: anywhere;
    }
    pre {
      max-height: 360px;
      overflow: auto;
      overflow-wrap: anywhere;
      white-space: pre-wrap;
    }
  }
</style>
