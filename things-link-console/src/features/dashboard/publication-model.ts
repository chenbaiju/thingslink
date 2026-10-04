import {
  validateDashboardSchemaV1,
  type DashboardSchemaV1
} from '@things-link/client-contracts/dashboard/v1'

export interface PublicationCatalog {
  id: string
  managementName: string
  publicationRevision: string
  currentVersionId: string | null
  createdAt: string
  updatedAt: string
}
export interface PublicationVersion {
  id: string
  versionNumber: string
  sourceDraftRevision: string
  schemaVersion: string
  schemaDigestAlgorithm: string
  schemaDigest: string
  publishedAt: string
}
export interface PublicationVersionDetail extends PublicationVersion {
  schema: DashboardSchemaV1
  requiredComponents: unknown[]
  requiredResources: unknown[]
}
export interface PublicationContext {
  projectId: string
  dashboardId: string
  identity: number
  canRead: boolean
  canManage: boolean
  available: boolean
  draftRevision: string
  dirty: boolean
  saving: boolean
  conflict: boolean
}
export interface PublicationIntent {
  kind: 'PUBLISH' | 'ROLLBACK' | 'WITHDRAW'
  projectId: string
  dashboardId: string
  targetVersionId?: string
  key: string
  body: Readonly<{ expectedPublicationRevision: string; expectedDraftRevision?: string }>
  status: 'UNKNOWN' | 'COMPLETED' | 'RECEIVED'
}
export interface PublicationSnapshot {
  catalog: PublicationCatalog | null
  history: PublicationVersion[]
  nextCursor: string | null
  loading: boolean
  writing: boolean
  pending: PublicationIntent | null
  error: string
  notice: string
  selectedVersion: PublicationVersionDetail | null
  detailLoading: boolean
}
export interface PublicationPorts {
  context(): PublicationContext
  detail(projectId: string, id: string): Promise<unknown>
  list(projectId: string, id: string, cursor?: string): Promise<unknown>
  version(projectId: string, id: string, versionId: string): Promise<unknown>
  write(intent: PublicationIntent): Promise<unknown>
  newKey(): string
  changed(snapshot: PublicationSnapshot): void
}
const uuid = (value: unknown): value is string =>
  typeof value === 'string' &&
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(value)
const revision = (value: unknown): value is string =>
  typeof value === 'string' &&
  /^(0|[1-9]\d*)$/.test(value) &&
  value.length <= 19 &&
  BigInt(value) <= 9223372036854775807n
const instant = (value: unknown): value is string =>
  typeof value === 'string' &&
  /^\d{4}-\d{2}-\d{2}T(?:[01]\d|2[0-3]):[0-5]\d:[0-5]\d(?:\.\d{1,9})?Z$/.test(value) &&
  Number.isFinite(Date.parse(value)) &&
  new Date(value).toISOString().slice(0, 10) === value.slice(0, 10)
function requireThat(value: unknown): asserts value {
  if (!value) throw new Error('发布响应不符合合同，请重新读取权威事实。')
}
function record(value: unknown): Record<string, unknown> {
  requireThat(value && typeof value === 'object' && !Array.isArray(value))
  return value as Record<string, unknown>
}
function catalog(value: unknown, id: string): PublicationCatalog {
  const data = record(value)
  requireThat(
    data.id === id &&
      typeof data.managementName === 'string' &&
      revision(data.publicationRevision) &&
      (data.currentVersionId === null || uuid(data.currentVersionId)) &&
      instant(data.createdAt) &&
      instant(data.updatedAt)
  )
  return {
    id,
    managementName: data.managementName,
    publicationRevision: data.publicationRevision,
    currentVersionId: data.currentVersionId,
    createdAt: data.createdAt,
    updatedAt: data.updatedAt
  }
}
function version(value: unknown): PublicationVersion {
  const data = record(value)
  requireThat(
    uuid(data.id) &&
      revision(data.versionNumber) &&
      data.versionNumber !== '0' &&
      revision(data.sourceDraftRevision) &&
      data.schemaVersion === 'tc.dashboard/v1' &&
      data.schemaDigestAlgorithm === 'PG_JSONB_TEXT_V1_SHA256' &&
      typeof data.schemaDigest === 'string' &&
      /^[a-f0-9]{64}$/.test(data.schemaDigest) &&
      instant(data.publishedAt)
  )
  return {
    id: data.id,
    versionNumber: data.versionNumber,
    sourceDraftRevision: data.sourceDraftRevision,
    schemaVersion: data.schemaVersion,
    schemaDigestAlgorithm: data.schemaDigestAlgorithm,
    schemaDigest: data.schemaDigest,
    publishedAt: data.publishedAt
  }
}
function versionDetail(value: unknown): PublicationVersionDetail {
  const data = record(value),
    summary = version(value)
  requireThat(Array.isArray(data.requiredComponents) && Array.isArray(data.requiredResources))
  const schema = validateDashboardSchemaV1(
    new TextEncoder().encode(JSON.stringify(data.schema))
  ).schema
  return {
    ...summary,
    schema,
    requiredComponents: structuredClone(data.requiredComponents),
    requiredResources: structuredClone(data.requiredResources)
  }
}
function page(value: unknown) {
  const data = record(value)
  requireThat(
    Array.isArray(data.items) && data.items.length <= 20 && typeof data.hasMore === 'boolean'
  )
  const items = data.items.map(version)
  requireThat(
    new Set(items.map((item) => item.id)).size === items.length &&
      items.every(
        (item, index) =>
          index === 0 || BigInt(items[index - 1]!.versionNumber) > BigInt(item.versionNumber)
      )
  )
  requireThat(
    !data.hasMore ||
      (items.length > 0 &&
        typeof data.nextCursor === 'string' &&
        data.nextCursor.length > 0 &&
        data.nextCursor.length <= 2048)
  )
  return { items, nextCursor: data.hasMore ? (data.nextCursor as string) : null }
}
const initial = (): PublicationSnapshot => ({
  catalog: null,
  history: [],
  nextCursor: null,
  loading: false,
  writing: false,
  pending: null,
  error: '',
  notice: '',
  selectedVersion: null,
  detailLoading: false
})

/** 公共幂等墓碑不重放响应；未知写只能同意图重试，读事实不能证明它未执行。 */
export function createDashboardPublication(ports: PublicationPorts) {
  let state = initial(),
    generation = 0,
    detailSequence = 0,
    readGeneration = 0
  let owner: PublicationContext | null = null
  let ambiguous = false
  const snapshot = () => structuredClone(state)
  const changed = () => ports.changed(snapshot())
  const contextMatches = (context: PublicationContext) => {
    const now = ports.context()
    return (
      now.available &&
      now.canRead &&
      now.identity === context.identity &&
      now.projectId === context.projectId &&
      now.dashboardId === context.dashboardId
    )
  }
  function reset() {
    generation++
    readGeneration++
    detailSequence++
    owner = null
    ambiguous = false
    state = initial()
    changed()
  }
  function fail(error: unknown) {
    state.error = error instanceof Error ? error.message : '发布操作未完成，请检查后重试。'
  }
  /** 读取明确拒绝使旧业务视图失效，但不能证明正在恢复的写请求未执行。 */
  function readFailure(error: unknown) {
    const rejection = error as {
      code?: number
      status?: number
      response?: { status?: number }
    } | null
    if (
      rejection &&
      ([401, 403, 404, 30001, 50001, 60034].includes(rejection.code ?? 0) ||
        rejection.status === 403 ||
        rejection.response?.status === 403)
    ) {
      // 独立于写generation：阻断其他读取迟到200，保留原意图及尚在途写的收尾。
      readGeneration++
      detailSequence++
      state.catalog = null
      state.history = []
      state.nextCursor = null
      state.selectedVersion = null
      state.notice = ''
      state.loading = false
      state.detailLoading = false
    }
    fail(error)
    changed()
  }
  async function facts(unlockCompleted: boolean) {
    const context = ports.context()
    if (
      !context.available ||
      !context.canRead ||
      !uuid(context.projectId) ||
      !uuid(context.dashboardId) ||
      state.loading ||
      state.writing
    )
      return
    if (owner && !contextMatches(owner)) reset()
    const currentGeneration = generation,
      readEpoch = readGeneration
    owner = { ...context }
    state.loading = true
    state.error = ''
    changed()
    try {
      const nextCatalog = catalog(
        await ports.detail(context.projectId, context.dashboardId),
        context.dashboardId
      )
      if (
        generation !== currentGeneration ||
        readEpoch !== readGeneration ||
        !contextMatches(context)
      )
        return
      const nextPage = page(await ports.list(context.projectId, context.dashboardId))
      if (
        generation !== currentGeneration ||
        readEpoch !== readGeneration ||
        !contextMatches(context)
      )
        return
      state.catalog = nextCatalog
      state.history = nextPage.items
      state.nextCursor = nextPage.nextCursor
      if (unlockCompleted && state.pending && state.pending.status !== 'UNKNOWN') {
        const completed = state.pending.status === 'COMPLETED'
        state.pending = null
        state.notice = completed
          ? '原操作已有完成记录；这里只展示当前目录和历史事实，不是首次操作回执。'
          : '已收到操作成功回执，并刷新当前发布事实。'
      } else if (state.pending)
        state.notice = '已刷新当前事实；原操作结果仍未知，请使用原请求重试，不能据此发起新操作。'
    } catch (error) {
      if (
        generation === currentGeneration &&
        readEpoch === readGeneration &&
        contextMatches(context)
      )
        readFailure(error)
    } finally {
      if (generation === currentGeneration && readEpoch === readGeneration) {
        state.loading = false
        changed()
      }
    }
  }
  async function open() {
    await facts(false)
  }
  async function recover() {
    await facts(true)
  }
  async function loadMore() {
    if (!owner || !contextMatches(owner) || state.loading || state.writing || !state.nextCursor)
      return
    const context = { ...owner },
      epoch = generation,
      readEpoch = readGeneration,
      cursor = state.nextCursor
    state.loading = true
    state.error = ''
    changed()
    try {
      const next = page(await ports.list(context.projectId, context.dashboardId, cursor))
      if (generation !== epoch || readEpoch !== readGeneration || !contextMatches(context)) return
      requireThat(
        next.nextCursor !== cursor &&
          next.items.every((item) => !state.history.some((old) => old.id === item.id))
      )
      if (state.history.length && next.items.length)
        requireThat(
          BigInt(state.history.at(-1)!.versionNumber) > BigInt(next.items[0]!.versionNumber)
        )
      state.history = next.items
      state.nextCursor = next.nextCursor
      state.selectedVersion = null
      detailSequence++
      state.detailLoading = false
    } catch (error) {
      if (generation === epoch && readEpoch === readGeneration && contextMatches(context))
        readFailure(error)
    } finally {
      if (generation === epoch && readEpoch === readGeneration) {
        state.loading = false
        changed()
      }
    }
  }
  async function selectVersion(id: string) {
    if (!owner || !contextMatches(owner) || !state.history.some((item) => item.id === id)) return
    const context = { ...owner },
      epoch = generation,
      readEpoch = readGeneration,
      sequence = ++detailSequence
    state.selectedVersion = null
    state.detailLoading = true
    state.error = ''
    changed()
    try {
      const detail = versionDetail(await ports.version(context.projectId, context.dashboardId, id))
      if (
        epoch !== generation ||
        readEpoch !== readGeneration ||
        sequence !== detailSequence ||
        !contextMatches(context)
      )
        return
      requireThat(
        detail.id === id &&
          JSON.stringify(version(detail)) ===
            JSON.stringify(state.history.find((item) => item.id === id))
      )
      state.selectedVersion = detail
    } catch (error) {
      if (
        epoch === generation &&
        readEpoch === readGeneration &&
        sequence === detailSequence &&
        contextMatches(context)
      )
        readFailure(error)
    } finally {
      if (epoch === generation && readEpoch === readGeneration && sequence === detailSequence) {
        state.detailLoading = false
        changed()
      }
    }
  }
  async function execute() {
    const context = ports.context(),
      epoch = generation,
      intent = state.pending
    if (
      !intent ||
      intent.status !== 'UNKNOWN' ||
      state.writing ||
      state.loading ||
      !owner ||
      !contextMatches(owner) ||
      !context.canManage
    )
      return
    state.writing = true
    state.error = ''
    changed()
    try {
      const result = await ports.write(structuredClone(intent))
      if (epoch !== generation || !contextMatches(context) || !ports.context().canManage) return
      if (intent.kind !== 'WITHDRAW') {
        const received = versionDetail(result)
        requireThat(
          intent.kind === 'ROLLBACK'
            ? received.id === intent.targetVersionId
            : received.sourceDraftRevision === intent.body.expectedDraftRevision
        )
      } else requireThat(result === undefined || result === null)
      state.pending = { ...intent, status: 'RECEIVED' }
    } catch (error) {
      if (epoch !== generation || !contextMatches(context) || !ports.context().canManage) return
      const failure = error as { code?: number; status?: number; outcomeUnknown?: boolean }
      if (failure.code === 10014) state.pending = { ...intent, status: 'COMPLETED' }
      else if (
        failure.code === 10010 ||
        failure.outcomeUnknown ||
        failure.status === undefined ||
        failure.status >= 500 ||
        ambiguous
      ) {
        ambiguous = true
        state.notice = '操作结果未知；请重试原操作，或读取当前发布状态。'
        fail(error)
      } else {
        state.pending = null
        state.catalog = null
        fail(error)
        state.notice = '本次操作被明确拒绝；重新读取发布状态后再决定新操作。'
      }
    } finally {
      if (epoch === generation && contextMatches(context)) {
        state.writing = false
        changed()
      }
    }
    if (
      epoch === generation &&
      contextMatches(context) &&
      state.pending &&
      state.pending.status !== 'UNKNOWN'
    )
      await recover()
  }
  async function begin(kind: PublicationIntent['kind'], targetVersionId?: string) {
    const context = ports.context()
    if (
      !owner ||
      !contextMatches(owner) ||
      !context.canManage ||
      state.loading ||
      state.writing ||
      state.pending ||
      !state.catalog
    )
      return
    if (
      kind === 'PUBLISH' &&
      (context.dirty || context.saving || context.conflict || !revision(context.draftRevision))
    ) {
      state.error = '请先保存有效草稿并解决冲突后发布。'
      changed()
      return
    }
    if (
      kind === 'ROLLBACK' &&
      (!targetVersionId ||
        targetVersionId === state.catalog.currentVersionId ||
        !state.history.some((item) => item.id === targetVersionId))
    )
      return
    if (kind === 'WITHDRAW' && state.catalog.currentVersionId === null) return
    const body = Object.freeze({
      expectedPublicationRevision: state.catalog.publicationRevision,
      ...(kind === 'PUBLISH' ? { expectedDraftRevision: context.draftRevision } : {})
    })
    state.pending = {
      kind,
      projectId: context.projectId,
      dashboardId: context.dashboardId,
      ...(targetVersionId ? { targetVersionId } : {}),
      key: ports.newKey(),
      body,
      status: 'UNKNOWN'
    }
    ambiguous = false
    state.notice = ''
    await execute()
  }
  return {
    open,
    loadMore,
    selectVersion,
    publish: () => begin('PUBLISH'),
    rollback: (id: string) => begin('ROLLBACK', id),
    withdraw: () => begin('WITHDRAW'),
    retry: execute,
    recover,
    reset,
    getSnapshot: snapshot
  }
}
