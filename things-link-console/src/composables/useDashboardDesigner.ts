import { computed, onBeforeUnmount, shallowReactive, watch, type Ref } from 'vue'
import {
  fetchDashboards,
  fetchDashboardDraft,
  createDashboard,
  saveDashboardDraft,
  type DashboardCatalog
} from '@/api/dashboard'
import { currentIdentityEpoch } from '@/utils/http/identity-scope'
import {
  createDashboardEditor,
  emptyDashboard,
  type EditorSnapshot
} from '@/features/dashboard/designer-model'
export interface DesignerPermissions {
  read: boolean
  create: boolean
  update: boolean
}
/** Console已有身份HTTP端口负责凭据；编辑状态只驻留本页，不创建另一套会话。 */
export function useDashboardDesigner(
  projectId: Readonly<Ref<string>>,
  permissions: () => DesignerPermissions,
  frozen: () => boolean = () => false
) {
  const state = shallowReactive<
    EditorSnapshot & {
      items: DashboardCatalog[]
      nextCursor: string | null
      loading: boolean
      creating: boolean
      offline: boolean
    }
  >({
    dashboardId: null,
    schema: null,
    activePageId: '',
    selectedId: null,
    revision: '',
    saving: false,
    dirty: false,
    conflict: false,
    error: '',
    readonly: true,
    canUndo: false,
    canRedo: false,
    items: [],
    nextCursor: null,
    loading: false,
    creating: false,
    offline: !navigator.onLine
  })
  let epoch = 0
  let disposed = false
  let loadSequence = 0
  let createIntent:
    | { name: string; key: string; content: ReturnType<typeof emptyDashboard> }
    | undefined
  const available = () => !disposed && navigator.onLine
  const editor = createDashboardEditor({
    canEdit: () => !frozen() && permissions().read && permissions().update && !!projectId.value,
    available: () => available() && !frozen(),
    now: () => performance.now(),
    setTimer: (callback, delay) => window.setTimeout(callback, delay),
    clearTimer: (timer) => window.clearTimeout(timer as number),
    changed: (snapshot) => Object.assign(state, snapshot),
    save: async (id, revision, content) => {
      const project = projectId.value
      const identity = currentIdentityEpoch()
      const generation = epoch
      const result = await saveDashboardDraft(project, id, revision, content)
      if (
        disposed ||
        epoch !== generation ||
        projectId.value !== project ||
        currentIdentityEpoch() !== identity
      )
        throw new Error('保存身份已失效')
      return result
    }
  })
  function close() {
    epoch++
    loadSequence++
    editor.reset()
    createIntent = undefined
    Object.assign(state, { loading: false, creating: false, items: [], nextCursor: null })
  }
  const stop = watch(
    () => [projectId.value, permissions().read, permissions().update, currentIdentityEpoch()],
    close,
    {
      flush: 'sync'
    }
  )
  function offline() {
    if (!frozen()) close()
    state.offline = true
    state.error = frozen()
      ? '已离线，删除原意图保留；联网后请显式恢复原请求。'
      : '已离线，未保存内容已丢弃，请联网后重新加载。'
  }
  function online() {
    state.offline = false
  }
  window.addEventListener('offline', offline)
  window.addEventListener('online', online)
  async function list() {
    if (frozen() || !available() || !projectId.value || !permissions().read || state.loading) return
    const generation = epoch
    const sequence = ++loadSequence
    const identity = currentIdentityEpoch()
    const project = projectId.value
    state.loading = true
    state.error = ''
    state.items = []
    state.nextCursor = null
    const active = () =>
      epoch === generation &&
      sequence === loadSequence &&
      currentIdentityEpoch() === identity &&
      available() &&
      !frozen() &&
      permissions().read
    try {
      const items: DashboardCatalog[] = []
      const ids = new Set<string>()
      const cursors = new Set<string>()
      let cursor: string | undefined
      while (active()) {
        const page = await fetchDashboards(project, cursor)
        if (!active()) return
        if (
          !Array.isArray(page.items) ||
          page.items.length > 20 ||
          page.items.some((item) => !item.id || !item.managementName) ||
          typeof page.hasMore !== 'boolean' ||
          (page.hasMore &&
            (!page.items.length ||
              typeof page.nextCursor !== 'string' ||
              !page.nextCursor ||
              cursors.has(page.nextCursor)))
        )
          throw new Error('目录响应不完整')
        for (const item of page.items) {
          if (ids.has(item.id!)) throw new Error('目录响应重复')
          ids.add(item.id!)
          items.push(item)
        }
        if (!page.hasMore) {
          state.items = items
          return
        }
        cursor = page.nextCursor!
        cursors.add(cursor)
      }
    } catch {
      if (epoch === generation && sequence === loadSequence)
        state.error = '目录读取失败，请明确重试。'
    } finally {
      if (epoch === generation && sequence === loadSequence) state.loading = false
    }
  }
  async function open(id: string) {
    if (
      !available() ||
      frozen() ||
      !projectId.value ||
      !permissions().read ||
      !/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/.test(id)
    )
      return
    const project = projectId.value
    const identity = currentIdentityEpoch()
    const generation = ++epoch
    const sequence = ++loadSequence
    editor.reset()
    state.loading = true
    try {
      const draft = await fetchDashboardDraft(project, id)
      if (
        epoch !== generation ||
        sequence !== loadSequence ||
        currentIdentityEpoch() !== identity ||
        disposed
      )
        return
      if (draft.dashboardId !== id) throw new Error('远端看板身份不匹配')
      editor.open(draft)
    } catch {
      if (epoch === generation && sequence === loadSequence)
        state.error = '草稿读取失败，未修改远端内容。'
    } finally {
      if (epoch === generation && sequence === loadSequence) state.loading = false
    }
  }
  async function create(managementName: string) {
    if (
      !available() ||
      frozen() ||
      !projectId.value ||
      !permissions().create ||
      !permissions().read ||
      state.creating
    )
      return
    if (!managementName.trim() || [...managementName].length > 128) {
      state.error = '请输入有效管理名称。'
      return
    }
    if (createIntent && createIntent.name !== managementName) {
      state.error = '上次创建结果待确认，请使用原名称重试；不会自动换幂等键。'
      return
    }
    createIntent ??= { name: managementName, key: crypto.randomUUID(), content: emptyDashboard() }
    const intent = createIntent
    const project = projectId.value
    const identity = currentIdentityEpoch()
    const generation = epoch
    state.creating = true
    state.error = ''
    try {
      const created = await createDashboard(project, intent.name, intent.content, intent.key)
      if (
        epoch !== generation ||
        projectId.value !== project ||
        currentIdentityEpoch() !== identity ||
        disposed
      )
        return
      if (!created.id) throw new Error('创建响应不完整')
      createIntent = undefined
      state.creating = false
      await open(created.id)
    } catch (error) {
      if (epoch === generation) {
        const failure = error as { code?: number; outcomeUnknown?: boolean }
        if (failure.outcomeUnknown === false) createIntent = undefined
        state.error =
          failure.outcomeUnknown === false && failure.code === 60059
            ? '看板数量已达套餐上限，请扩容后再创建。'
            : failure.outcomeUnknown === false && failure.code === 50048
              ? '套餐额度暂不可用，请联系管理员确认套餐配置后重试。'
              : '创建未完成；若结果未知，请用原名称明确重试以恢复同一次创建。'
      }
    } finally {
      if (!disposed && epoch === generation) state.creating = false
    }
  }
  async function reloadRemote() {
    const id = state.dashboardId
    if (id) await open(id)
  }
  function dispose() {
    if (disposed) return
    close()
    disposed = true
    stop()
    window.removeEventListener('offline', offline)
    window.removeEventListener('online', online)
  }
  onBeforeUnmount(dispose)
  return {
    state,
    canUndo: computed(() => !frozen() && state.canUndo),
    canRedo: computed(() => !frozen() && state.canRedo),
    list,
    open,
    create,
    select: (id: string | null) => {
      if (!frozen()) editor.select(id)
    },
    setPage: (id: string) => {
      if (!frozen()) editor.setPage(id)
    },
    add: editor.add,
    addDeviceComponent: editor.addDeviceComponent,
    upsertTimeRange: editor.upsertTimeRange,
    removeTimeRange: editor.removeTimeRange,
    upsertTextEnum: editor.upsertTextEnum,
    removeTextEnum: editor.removeTextEnum,
    addTextComponent: editor.addTextComponent,
    rebindTextComponent: editor.rebindTextComponent,
    addAlarmComponent: editor.addAlarmComponent,
    rebindAlarmComponent: editor.rebindAlarmComponent,
    addHistoryComponent: editor.addHistoryComponent,
    rebindHistoryComponent: editor.rebindHistoryComponent,
    upsertDeviceVariable: editor.upsertDeviceVariable,
    removeDeviceVariable: editor.removeDeviceVariable,
    addVariableComponent: editor.addVariableComponent,
    rebindVariableComponent: editor.rebindVariableComponent,
    rebindSelectedDevice: editor.rebindSelectedDevice,
    updateSelected: editor.updateSelected,
    setPresentation: editor.setPresentation,
    setTheme: editor.setTheme,
    addPage: editor.addPage,
    renamePage: editor.renamePage,
    removePage: editor.removePage,
    removeSelected: editor.removeSelected,
    undo: editor.undo,
    redo: editor.redo,
    retrySave: () => {
      if (!frozen()) editor.retrySave()
    },
    reloadRemote,
    close: () => {
      if (!frozen()) close()
    },
    completeDeletion: close,
    dispose
  }
}
