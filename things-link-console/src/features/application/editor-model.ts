/** S12-4m：内部编辑模型，HTTP外壳类型来自OpenAPI；字段依据发布元数据合同3.1。 */
export interface ApplicationContent {
  formatVersion: 'tc.application/v1'
  displayName: string
  hostCompatibility: { minInclusive: string; maxExclusive: string }
  dashboardRefs: { dashboardId: string; dashboardVersionId: string; title: string }[]
  entryDashboardId: string | null
}
export const uuid = (value: unknown): value is string =>
  typeof value === 'string' && /^[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}$/.test(value)
export const revision = (value: unknown): value is string =>
  typeof value === 'string' &&
  /^(0|[1-9][0-9]{0,18})$/.test(value) &&
  BigInt(value) <= 9223372036854775807n
export function deniedApplicationAccess(error: unknown) {
  const code = (error as { code?: number })?.code
  return (
    [401, 403, 404, 30001, 50001, 50017, 60030, 60031].includes(code ?? 0) ||
    (code !== undefined && code >= 20000 && code < 30000)
  )
}
/** 整体应用不可见，不把单个历史版本60048或认证拒绝解释为删除事实。 */
export function missingApplicationResource(error: unknown) {
  const failure = error as { code?: number; status?: number; response?: { status?: number } } | null
  const status = failure?.status ?? failure?.response?.status
  return (
    !!failure &&
    (status === undefined || status === 404) &&
    (failure.code === 60030 ||
      failure.code === 404 ||
      (failure.code === undefined && status === 404))
  )
}
export function title(value: unknown): value is string {
  if (typeof value !== 'string' || !value.trim() || [...value].length > 80) return false
  return [...value].every((c) => {
    const point = c.codePointAt(0)!
    return point >= 32 && !(point >= 127 && point <= 159) && !(point >= 0xd800 && point <= 0xdfff)
  })
}
const object = (v: unknown): v is Record<string, unknown> =>
  !!v && typeof v === 'object' && !Array.isArray(v)
function keys(v: Record<string, unknown>, names: string[]) {
  return Object.keys(v).length === names.length && names.every((key) => Object.hasOwn(v, key))
}
function semver(value: unknown): number[] {
  if (
    typeof value !== 'string' ||
    !/^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$/.test(value)
  )
    throw Error('宿主版本须为三段非负整数')
  const parts = value.split('.').map(Number)
  if (parts.some((part) => part > 65535)) throw Error('宿主版本每段不得超过65535')
  return parts
}
export function parseContent(value: unknown): ApplicationContent {
  if (
    !object(value) ||
    !keys(value, [
      'formatVersion',
      'displayName',
      'hostCompatibility',
      'dashboardRefs',
      'entryDashboardId'
    ]) ||
    value.formatVersion !== 'tc.application/v1'
  )
    throw Error('应用草稿格式不受支持，未覆盖原文')
  if (!title(value.displayName)) throw Error('公开展示名须为1至80个非空白字符且不含控制字符')
  const host = value.hostCompatibility
  if (!object(host) || !keys(host, ['minInclusive', 'maxExclusive']))
    throw Error('宿主范围字段不完整')
  const min = semver(host.minInclusive),
    max = semver(host.maxExclusive)
  const difference = min.map((part, index) => part - max[index]).find((part) => part !== 0)
  if (difference === undefined || difference >= 0) throw Error('最低宿主版本必须小于上界')
  const refs = value.dashboardRefs
  if (!Array.isArray(refs) || refs.length > 5) throw Error('最多引用5个看板')
  const ids = new Set<string>()
  for (const ref of refs) {
    if (
      !object(ref) ||
      !keys(ref, ['dashboardId', 'dashboardVersionId', 'title']) ||
      !uuid(ref.dashboardId) ||
      !uuid(ref.dashboardVersionId) ||
      !title(ref.title) ||
      ids.has(ref.dashboardId)
    )
      throw Error('看板引用必须具有唯一看板、精确版本和有效导航标题')
    ids.add(ref.dashboardId)
  }
  if (
    refs.length
      ? !uuid(value.entryDashboardId) || !ids.has(value.entryDashboardId)
      : value.entryDashboardId !== null
  )
    throw Error('请选择引用中的入口看板；空应用入口必须为空')
  // 封闭结构只有短字符串，最大合法组合远低于64KiB；仍保留原文上限防御。
  if (new TextEncoder().encode(JSON.stringify(value)).length > 65536)
    throw Error('应用草稿超过64KiB')
  return structuredClone(value) as unknown as ApplicationContent
}
export function emptyContent(displayName: string): ApplicationContent {
  return parseContent({
    formatVersion: 'tc.application/v1',
    displayName,
    hostCompatibility: { minInclusive: '1.0.0', maxExclusive: '2.0.0' },
    dashboardRefs: [],
    entryDashboardId: null
  })
}
export interface EditorState {
  id: string | null
  revision: string
  content: ApplicationContent | null
  dirty: boolean
  busy: boolean
  blocked: boolean
  creatingUnknown: boolean
  error: string
  notice: string
}
interface Ports {
  context(): string
  readable(): boolean
  writable(): boolean
  draft(id: string): Promise<{ applicationId?: string; revision?: string; content?: unknown }>
  create(name: string, content: ApplicationContent, key: string): Promise<{ id: string }>
  save(
    id: string,
    revision: string,
    content: ApplicationContent,
    key: string
  ): Promise<{ applicationId?: string; revision?: string; content?: unknown }>
  key(): string
  changed(state: EditorState): void
}
/** 每次写入捕获完整意图；身份/资源切换使迟到响应失效，不保存在浏览器持久存储。 */
export function createApplicationEditor(ports: Ports) {
  const initial = (): EditorState => ({
    id: null,
    revision: '',
    content: null,
    dirty: false,
    busy: false,
    blocked: false,
    creatingUnknown: false,
    error: '',
    notice: ''
  })
  let state = initial(),
    generation = 0
  let creation: { name: string; content: ApplicationContent; key: string } | undefined
  const emit = () => ports.changed(structuredClone(state))
  function reset() {
    generation++
    creation = undefined
    state = initial()
    emit()
  }
  function suspendView() {
    const id = state.id
    generation++
    creation = undefined
    state = { ...initial(), id, blocked: true }
    emit()
  }
  function capture() {
    const g = generation,
      c = ports.context()
    return () => g === generation && c === ports.context() && ports.readable()
  }
  function accept(draft: Awaited<ReturnType<Ports['draft']>>, id: string) {
    if (draft.applicationId !== id || !revision(draft.revision))
      throw Error('草稿响应身份或修订号无效')
    const content = parseContent(draft.content)
    Object.assign(state, {
      id,
      revision: draft.revision,
      content,
      dirty: false,
      blocked: false,
      error: ''
    })
  }
  async function open(id: string) {
    if (!ports.readable() || state.busy || state.creatingUnknown || !uuid(id)) return
    generation++
    const valid = capture()
    // 显式重载才能舍弃本地；失败时保留它，但不能继续写陈旧revision。
    state.busy = true
    state.blocked = true
    state.error = ''
    emit()
    try {
      const draft = await ports.draft(id)
      if (valid()) {
        accept(draft, id)
        state.notice = '已读取远端草稿；不会改变发布版本。'
      }
    } catch (e) {
      if (valid()) {
        if (deniedApplicationAccess(e)) {
          reset()
          state.error = '资源不可读或权限已变化。'
          emit()
        } else state.error = '草稿读取失败，请显式重试；本地内容未覆盖远端。'
      }
    } finally {
      if (valid()) {
        state.busy = false
        emit()
      }
    }
  }
  function change(update: (content: ApplicationContent) => void) {
    if (!ports.writable() || state.busy || state.blocked || !state.content) return
    const content = structuredClone(state.content)
    update(content)
    state.content = content
    state.dirty = true
    state.error = ''
    state.notice = ''
    emit()
  }
  async function create(name: string) {
    if (!ports.writable() || state.busy) return
    if (!creation) {
      try {
        if (!title(name)) throw Error('管理名称须为1至80个有效字符')
        creation = { name, content: emptyContent(name), key: ports.key() }
      } catch (e) {
        state.error = (e as Error).message
        emit()
        return
      }
    }
    const intent = creation,
      valid = capture()
    state.busy = true
    state.error = ''
    emit()
    try {
      const result = await ports.create(intent.name, structuredClone(intent.content), intent.key)
      if (!valid()) return
      if (!uuid(result.id)) throw Error('创建响应缺少资源身份')
      creation = undefined
      state.creatingUnknown = false
      state.id = result.id
      state.content = null
      // 创建已获资源身份，即使随后GET失败也只重读，不重复创建。
      state.blocked = true
      try {
        const draft = await ports.draft(result.id)
        if (valid()) accept(draft, result.id)
      } catch {
        if (valid()) state.error = '应用已创建，请重新读取草稿。'
      }
    } catch (e) {
      if (!valid()) return
      if (deniedApplicationAccess(e)) {
        reset()
        state.error = '创建被拒绝，权限或资源不可用。'
        emit()
        return
      }
      const code = (e as { code?: number })?.code
      if (code !== undefined && [10001, 10009, 10014, 60031, 60032, 60033].includes(code)) {
        creation = undefined
        state.creatingUnknown = false
        state.error = '创建请求被拒绝，请检查输入后重新创建。'
      } else {
        state.creatingUnknown = true
        state.error = '创建结果未知；请重试原创建请求，不要另建应用。'
      }
    } finally {
      if (valid()) {
        state.busy = false
        emit()
      }
    }
  }
  async function save() {
    if (
      !ports.writable() ||
      state.busy ||
      state.blocked ||
      !state.id ||
      !state.dirty ||
      !state.content
    )
      return
    let content: ApplicationContent
    try {
      content = parseContent(state.content)
    } catch (e) {
      state.error = (e as Error).message
      emit()
      return
    }
    const id = state.id,
      expected = state.revision,
      valid = capture()
    state.busy = true
    state.error = ''
    emit()
    try {
      const draft = await ports.save(id, expected, content, ports.key())
      if (!valid()) return
      if (!revision(draft.revision) || BigInt(draft.revision) !== BigInt(expected) + 1n)
        throw Error('保存返回修订号不符合CAS')
      accept(draft, id)
      state.notice = '草稿已保存，发布版本未改变。'
    } catch (e) {
      if (!valid()) return
      if (deniedApplicationAccess(e)) {
        reset()
        state.error = '资源不可写或权限已变化。'
        emit()
        return
      }
      state.blocked = true
      state.error = '保存冲突或结果未知。本地内容已保留；请读取远端比较，再明确重载，不会自动覆盖。'
    } finally {
      if (valid()) {
        state.busy = false
        emit()
      }
    }
  }
  return { reset, suspendView, open, change, create, save, snapshot: () => structuredClone(state) }
}
