export interface GrantContext {
  projectId: string
  dashboardId: string
  identity: number
  available: boolean
  canManage: boolean
}
export interface GrantUser {
  id: string
  username: string
  displayName: string | null
  status: string
  role: string | null
  roleStatus: string | null
  assignedAt: string | null
}
export interface DashboardGrant {
  appUserId: string
  dashboardId: string
  permission: 'READ'
  status: 'ACTIVE' | 'REVOKED'
  revision: string
  createdAt: string
  updatedAt: string
  revokedAt: string | null
}
export interface GrantWriteIntent {
  projectId: string
  dashboardId: string
  appUserId: string
  key: string
  body: { expectedRevision: string; status: 'ACTIVE' | 'REVOKED' }
}
export interface GrantSnapshot {
  users: GrantUser[]
  nextCursor: string | null
  listLoaded: boolean
  selectedUser: GrantUser | null
  grant: DashboardGrant | null
  retryBlocked: boolean
  missingEligible: boolean
  loading: boolean
  detailLoading: boolean
  writing: boolean
  pending: { intent: GrantWriteIntent; status: 'UNKNOWN' | 'COMPLETED' | 'RECEIVED' } | null
  error: string
  notice: string
}
export interface GrantPorts {
  context(): GrantContext
  users(projectId: string, cursor?: string): Promise<unknown>
  detail(projectId: string, appUserId: string, dashboardId: string): Promise<unknown>
  write(intent: GrantWriteIntent): Promise<unknown>
  newKey(): string
  changed(snapshot: GrantSnapshot): void
}
const uuid = (value: unknown): value is string =>
  typeof value === 'string' && /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(value)
const revision = (value: unknown): value is string =>
  typeof value === 'string' &&
  /^(0|[1-9]\d{0,18})$/.test(value) &&
  BigInt(value) <= 9223372036854775807n
function instant(value: unknown): value is string {
  if (
    typeof value !== 'string' ||
    !/^\d{4}-\d{2}-\d{2}T(?:[01]\d|2[0-3]):[0-5]\d:[0-5]\d(?:\.\d{1,9})?Z$/.test(value)
  )
    return false
  const date = new Date(value)
  return Number.isFinite(date.getTime()) && date.toISOString().slice(0, 10) === value.slice(0, 10)
}
function requireThat(value: unknown): asserts value {
  if (!value) throw new Error('invalid grant response')
}
function object(value: unknown) {
  requireThat(value && typeof value === 'object' && !Array.isArray(value))
  return value as Record<string, unknown>
}
function user(value: unknown): GrantUser {
  const item = object(value)
  requireThat(
    uuid(item.id) &&
      typeof item.username === 'string' &&
      (item.displayName === null || typeof item.displayName === 'string') &&
      typeof item.status === 'string' &&
      (item.role === null || typeof item.role === 'string') &&
      (item.roleStatus === null || typeof item.roleStatus === 'string') &&
      (item.assignedAt === null || instant(item.assignedAt))
  )
  return {
    id: item.id,
    username: item.username,
    displayName: item.displayName,
    status: item.status,
    role: item.role,
    roleStatus: item.roleStatus,
    assignedAt: item.assignedAt
  }
}
function page(value: unknown) {
  const data = object(value)
  requireThat(
    Array.isArray(data.items) && data.items.length <= 20 && typeof data.hasMore === 'boolean'
  )
  const users = data.items.map(user)
  requireThat(new Set(users.map((item) => item.id)).size === users.length)
  requireThat(
    !data.hasMore ||
      (users.length > 0 &&
        typeof data.nextCursor === 'string' &&
        data.nextCursor.length > 0 &&
        data.nextCursor.length <= 2048)
  )
  return { users, nextCursor: data.hasMore ? (data.nextCursor as string) : null }
}
export function parseDashboardGrant(
  value: unknown,
  appUserId: string,
  dashboardId: string
): DashboardGrant {
  const data = object(value)
  requireThat(
    Object.keys(data).sort().join(',') ===
      [
        'appUserId',
        'dashboardId',
        'permission',
        'status',
        'revision',
        'createdAt',
        'updatedAt',
        'revokedAt'
      ]
        .sort()
        .join(',') &&
      data.appUserId === appUserId &&
      data.dashboardId === dashboardId &&
      data.permission === 'READ' &&
      ['ACTIVE', 'REVOKED'].includes(data.status as string) &&
      revision(data.revision) &&
      data.revision !== '0' &&
      instant(data.createdAt) &&
      instant(data.updatedAt) &&
      (data.status === 'ACTIVE' ? data.revokedAt === null : instant(data.revokedAt))
  )
  return {
    appUserId,
    dashboardId,
    permission: 'READ',
    status: data.status as DashboardGrant['status'],
    revision: data.revision,
    createdAt: data.createdAt,
    updatedAt: data.updatedAt,
    revokedAt: data.revokedAt as string | null
  }
}
const active = (value: GrantUser | null) =>
  value?.status === 'ACTIVE' && value.roleStatus === 'ACTIVE' && !!value.role
const initial = (): GrantSnapshot => ({
  users: [],
  nextCursor: null,
  listLoaded: false,
  selectedUser: null,
  grant: null,
  retryBlocked: false,
  missingEligible: false,
  loading: false,
  detailLoading: false,
  writing: false,
  pending: null,
  error: '',
  notice: ''
})
/** 授权历史与运行资格分离；未知写保留原意图，不能由60025推断原请求从未执行。 */
export function createDashboardGrants(ports: GrantPorts) {
  let state = initial(),
    generation = 0,
    readSequence = 0,
    owner: GrantContext | null = null,
    ambiguous = false
  const knownActive = new Map<string, boolean>()
  const snapshot = () => ({
      ...structuredClone(state),
      retryBlocked: !!state.pending && knownActive.get(state.pending.intent.appUserId) === false
    }),
    changed = () => ports.changed(snapshot())
  const matches = (context: GrantContext) => {
    const now = ports.context()
    return (
      now.available &&
      now.canManage &&
      context.identity === now.identity &&
      context.projectId === now.projectId &&
      context.dashboardId === now.dashboardId
    )
  }
  const denied = (error: unknown) => {
    const failure = error as { code?: number; status?: number }
    return (
      [30001, 50001, 60034, 60035, 401, 403, 404].includes(failure.code ?? 0) ||
      [401, 403].includes(failure.status ?? 0) ||
      (failure.status === 404 && failure.code !== 60025)
    )
  }
  function reset() {
    generation++
    readSequence++
    owner = null
    knownActive.clear()
    ambiguous = false
    state = initial()
    changed()
  }
  function suspend() {
    generation++
    readSequence++
    if (state.pending) ambiguous = true
    const target = state.pending?.intent.appUserId
    const activity = target ? knownActive.get(target) : undefined
    knownActive.clear()
    if (target && activity !== undefined) knownActive.set(target, activity)
    state = { ...initial(), pending: state.pending }
    changed()
  }
  function loseScope() {
    suspend()
    state.error = '授权管理目标不可见或权限已变化，旧记录已清除。'
    changed()
  }
  async function open(next = false) {
    const context = ports.context()
    if (
      !context.available ||
      !context.canManage ||
      !uuid(context.projectId) ||
      !uuid(context.dashboardId) ||
      state.loading ||
      state.writing
    )
      return
    if (owner && !matches(owner)) reset()
    const epoch = generation,
      cursor = next ? state.nextCursor : undefined
    if (next && !cursor) return
    owner = { ...context }
    state.loading = true
    state.error = ''
    changed()
    try {
      const result = page(await ports.users(context.projectId, cursor ?? undefined))
      if (epoch !== generation || !matches(context)) return
      if (next) requireThat(result.nextCursor !== cursor)
      const pendingId = state.pending?.intent.appUserId
      const pendingActivity = pendingId ? knownActive.get(pendingId) : undefined
      knownActive.clear()
      if (pendingId && pendingActivity !== undefined) knownActive.set(pendingId, pendingActivity)
      for (const item of result.users) knownActive.set(item.id, !!active(item))
      state.users = result.users
      state.nextCursor = result.nextCursor
      state.listLoaded = true
      const target = state.pending?.intent.appUserId ?? state.selectedUser?.id
      state.selectedUser = target ? (result.users.find((item) => item.id === target) ?? null) : null
      if (!state.selectedUser) {
        state.grant = null
        state.missingEligible = false
        readSequence++
      }
    } catch (error) {
      if (epoch !== generation || !matches(context)) return
      if (denied(error)) {
        loseScope()
        return
      }
      state.users = []
      state.nextCursor = null
      state.listLoaded = false
      state.selectedUser = null
      state.grant = null
      state.missingEligible = false
      readSequence++
      state.error = '用户目录读取失败，请明确刷新。'
    } finally {
      if (epoch === generation && matches(context)) {
        state.loading = false
        changed()
      }
    }
  }
  async function detail(recover = false) {
    const context = ports.context(),
      target = state.pending?.intent.appUserId ?? state.selectedUser?.id
    if (!owner || !matches(owner) || !target || state.loading || state.writing) return
    const epoch = generation,
      sequence = ++readSequence
    state.detailLoading = true
    state.grant = null
    state.missingEligible = false
    state.error = ''
    changed()
    try {
      const result = parseDashboardGrant(
        await ports.detail(context.projectId, target, context.dashboardId),
        target,
        context.dashboardId
      )
      if (epoch !== generation || sequence !== readSequence || !matches(context)) return
      state.grant = result
      if (recover && state.pending && state.pending.status !== 'UNKNOWN') {
        const completed = state.pending.status === 'COMPLETED'
        state.pending = null
        ambiguous = false
        state.notice = completed
          ? '已读取当前授权事实；完成记录不重放原操作响应。'
          : '操作成功回执已确认；这里展示当前授权事实。'
      } else if (state.pending) state.notice = '已读取当前事实；原操作结果仍未知，请重试原操作。'
    } catch (error) {
      if (epoch !== generation || sequence !== readSequence || !matches(context)) return
      const failure = error as { code?: number }
      if (denied(error)) {
        loseScope()
        return
      }
      if (failure.code === 60025) {
        state.missingEligible = !state.pending && active(state.selectedUser)
        state.error = state.pending
          ? '当前授权事实不可读取，原操作仍需恢复；不能据此判断它未执行。'
          : '未能读取授权记录：可能尚无记录，也可能目标不可见。'
      } else state.error = '授权记录读取失败，请关闭弹窗后重新打开。'
    } finally {
      if (epoch === generation && sequence === readSequence && matches(context)) {
        state.detailLoading = false
        changed()
      }
    }
  }
  async function selectUser(id: string) {
    if (
      !owner ||
      !matches(owner) ||
      state.loading ||
      state.writing ||
      (state.pending && state.pending.intent.appUserId !== id)
    )
      return
    const selected = state.users.find((item) => item.id === id)
    if (!selected) return
    state.selectedUser = selected
    state.grant = null
    state.missingEligible = false
    changed()
    await detail(!!state.pending)
  }
  async function execute() {
    const context = ports.context(),
      pending = state.pending,
      epoch = generation
    if (
      !owner ||
      !matches(owner) ||
      !pending ||
      pending.status !== 'UNKNOWN' ||
      state.loading ||
      state.detailLoading ||
      state.writing ||
      knownActive.get(pending.intent.appUserId) === false
    )
      return
    state.writing = true
    state.error = ''
    changed()
    try {
      const raw = await ports.write(structuredClone(pending.intent))
      if (epoch !== generation || !matches(context)) return
      const result = parseDashboardGrant(raw, pending.intent.appUserId, context.dashboardId)
      const before = pending.intent.body.expectedRevision
      requireThat(
        result.status === pending.intent.body.status &&
          result.revision === String(BigInt(before) + 1n)
      )
      state.pending = { ...pending, status: 'RECEIVED' }
      state.grant = result
      state.missingEligible = false
    } catch (error) {
      if (epoch !== generation || !matches(context)) return
      const failure = error as { code?: number; status?: number; outcomeUnknown?: boolean }
      if (failure.code === 10014) state.pending = { ...pending, status: 'COMPLETED' }
      else if (
        ambiguous ||
        failure.code === 10010 ||
        failure.outcomeUnknown ||
        !failure.code ||
        failure.status === undefined ||
        failure.status >= 500
      ) {
        ambiguous = true
        state.error = '授权操作结果未知，请重试原操作或读取当前事实。'
      } else {
        state.pending = null
        state.grant = null
        state.missingEligible = false
        state.error =
          failure.code === 60027
            ? '授权已被其他操作更新，请关闭弹窗后重新打开再决定。'
            : '授权操作被明确拒绝，请关闭弹窗后重新打开并核对目标状态。'
      }
    } finally {
      if (epoch === generation && matches(context)) {
        state.writing = false
        changed()
      }
    }
    if (
      epoch === generation &&
      matches(context) &&
      state.pending &&
      state.pending.status !== 'UNKNOWN'
    )
      await detail(true)
  }
  async function change(status: 'ACTIVE' | 'REVOKED') {
    const context = ports.context()
    if (
      !owner ||
      !matches(owner) ||
      state.loading ||
      state.detailLoading ||
      state.writing ||
      state.pending ||
      !active(state.selectedUser)
    )
      return
    if (!state.grant && (!state.missingEligible || status !== 'ACTIVE')) return
    if (state.grant?.status === status) return
    const intent: GrantWriteIntent = {
      projectId: context.projectId,
      dashboardId: context.dashboardId,
      appUserId: state.selectedUser!.id,
      key: ports.newKey(),
      body: { expectedRevision: state.grant?.revision ?? '0', status }
    }
    state.pending = { intent, status: 'UNKNOWN' }
    ambiguous = false
    state.notice = ''
    await execute()
  }
  return {
    getSnapshot: snapshot,
    open,
    loadMore: () => open(true),
    selectUser,
    refresh: () => detail(!!state.pending),
    change,
    retry: execute,
    reset,
    suspend
  }
}
