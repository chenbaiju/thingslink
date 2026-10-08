import { dashboardV1Contract } from '@things-link/client-contracts/dashboard/v1'
import type { components } from '@/types/api/schema'
import { missingApplicationResource, parseContent, title } from './editor-model'

export interface PublicationCatalog {
  id: string
  managementName: string
  appKey: string
  publicationRevision: string
  currentVersionId: string | null
  createdAt: string
  updatedAt: string
}
export interface PublicationVersion {
  id: string
  versionNumber: string
  sourceDraftRevision: string
  snapshotDigestAlgorithm: string
  snapshotDigest: string
  publishedAt: string
}
export interface PublicationVersionDetail extends PublicationVersion {
  snapshot: components['schemas']['ApplicationSnapshotResponse']
}
export interface PublicationContext {
  projectId: string
  applicationId: string
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
  kind: 'PUBLISH' | 'ROLLBACK' | 'WITHDRAW' | 'SOFT_DELETE'
  projectId: string
  applicationId: string
  targetVersionId?: string
  key: string
  body: Readonly<{ expectedPublicationRevision: string; expectedDraftRevision?: string }>
  status: 'UNKNOWN' | 'COMPLETED' | 'RECEIVED'
}
export interface PublicationSnapshot {
  deleted: null | {
    projectId: string
    applicationId: string
    identity: number
    receipt: 'NO_CONTENT' | 'COMPLETION_MARKER'
  }
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
  accessDenied?(code?: number): void
  deleteResourceUnavailable?(): void
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
      title(data.managementName) &&
      typeof data.appKey === 'string' &&
      /^app_[a-f0-9]{32}$/.test(data.appKey) &&
      revision(data.publicationRevision) &&
      (data.currentVersionId === null || uuid(data.currentVersionId)) &&
      instant(data.createdAt) &&
      instant(data.updatedAt)
  )
  return {
    id,
    managementName: data.managementName,
    appKey: data.appKey,
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
      data.snapshotDigestAlgorithm === 'PG_JSONB_TEXT_V1_SHA256' &&
      typeof data.snapshotDigest === 'string' &&
      /^[a-f0-9]{64}$/.test(data.snapshotDigest) &&
      instant(data.publishedAt)
  )
  return {
    id: data.id,
    versionNumber: data.versionNumber,
    sourceDraftRevision: data.sourceDraftRevision,
    snapshotDigestAlgorithm: data.snapshotDigestAlgorithm,
    snapshotDigest: data.snapshotDigest,
    publishedAt: data.publishedAt
  }
}
/** 元数据合同3.2：只读快照完整验证，不从当前草稿重建或仿算PG摘要。 */
function versionDetail(value: unknown): PublicationVersionDetail {
  const data = record(value)
  return { ...version(value), snapshot: parseSnapshot(data.snapshot) }
}
function exact(value: unknown, fields: string[]) {
  const data = record(value)
  requireThat(
    Object.keys(data).length === fields.length && fields.every((key) => Object.hasOwn(data, key))
  )
  return data
}
const digest = (value: unknown) => typeof value === 'string' && /^[a-f0-9]{64}$/.test(value)
const localKey = (value: unknown) =>
  typeof value === 'string' && /^[a-z][a-z0-9_]{0,63}$/.test(value)
export function parseSnapshot(value: unknown): PublicationVersionDetail['snapshot'] {
  const data = exact(value, [
    'formatVersion',
    'displayName',
    'hostCompatibility',
    'dashboardRefs',
    'entryDashboardId',
    'requiredSchemas',
    'requiredComponents',
    'requiredResources'
  ])
  requireThat(
    Array.isArray(data.dashboardRefs) &&
      data.dashboardRefs.length >= 1 &&
      data.dashboardRefs.length <= 5
  )
  const references = data.dashboardRefs.map((value) => {
    const ref = exact(value, [
      'dashboardId',
      'dashboardVersionId',
      'dashboardVersionNumber',
      'title',
      'schemaVersion',
      'schemaDigestAlgorithm',
      'schemaDigest',
      'pages'
    ])
    requireThat(
      revision(ref.dashboardVersionNumber) &&
        ref.dashboardVersionNumber !== '0' &&
        ref.schemaVersion === 'tc.dashboard/v1' &&
        ref.schemaDigestAlgorithm === 'PG_JSONB_TEXT_V1_SHA256' &&
        digest(ref.schemaDigest)
    )
    requireThat(Array.isArray(ref.pages) && ref.pages.length >= 1 && ref.pages.length <= 5)
    const ids = ref.pages.map((value) => {
      const page = exact(value, ['id', 'title'])
      requireThat(localKey(page.id) && title(page.title))
      return page.id
    })
    requireThat(new Set(ids).size === ids.length)
    return {
      dashboardId: ref.dashboardId,
      dashboardVersionId: ref.dashboardVersionId,
      title: ref.title
    }
  })
  parseContent({
    formatVersion: data.formatVersion,
    displayName: data.displayName,
    hostCompatibility: data.hostCompatibility,
    dashboardRefs: references,
    entryDashboardId: data.entryDashboardId
  })
  requireThat(
    Array.isArray(data.requiredSchemas) &&
      data.requiredSchemas.length === 1 &&
      data.requiredSchemas[0] === 'tc.dashboard/v1'
  )
  requireThat(Array.isArray(data.requiredComponents) && data.requiredComponents.length <= 10)
  const kinds = data.requiredComponents.map((value) => {
    const component = exact(value, ['kind', 'componentVersion'])
    requireThat(
      dashboardV1Contract.components.some((item) => item.kind === component.kind) &&
        (component.componentVersion === '1.0.0' || component.componentVersion === '1.0.1')
    )
    return component.kind as string
  })
  requireThat(kinds.every((kind, i) => i === 0 || kinds[i - 1]! < kind))
  requireThat(Array.isArray(data.requiredResources) && data.requiredResources.length <= 50)
  const resources = data.requiredResources.map((value) => {
    const resource = exact(value, ['resourceId', 'digest'])
    requireThat(localKey(resource.resourceId) && digest(resource.digest))
    return resource.resourceId as string
  })
  requireThat(resources.every((id, i) => i === 0 || resources[i - 1]! < id))
  requireThat(new TextEncoder().encode(JSON.stringify(data)).length <= 65536)
  return structuredClone(data) as unknown as PublicationVersionDetail['snapshot']
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
  deleted: null,
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
export function createApplicationPublication(ports: PublicationPorts) {
  let state = initial(),
    generation = 0,
    detailSequence = 0,
    readGeneration = 0
  let owner: PublicationContext | null = null
  let ambiguous = false
  const snapshot = () => structuredClone(state)
  const changed = () => ports.changed(snapshot())
  const identityMatches = (context: PublicationContext) => {
    const now = ports.context()
    return (
      now.identity === context.identity &&
      now.projectId === context.projectId &&
      now.applicationId === context.applicationId
    )
  }
  const contextMatches = (context: PublicationContext) => {
    const now = ports.context()
    return (
      now.available &&
      now.canRead &&
      now.identity === context.identity &&
      now.projectId === context.projectId &&
      now.applicationId === context.applicationId
    )
  }
  function retainUnobservedDeletion(context: PublicationContext, epoch: number) {
    const now = ports.context()
    if (
      epoch === generation &&
      identityMatches(context) &&
      now.canRead &&
      now.canManage &&
      !now.available &&
      state.pending?.kind === 'SOFT_DELETE'
    ) {
      ambiguous = true
      state.notice = '删除响应未被采纳，原请求结果仍未知；联网后请只恢复原操作。'
    }
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
  function deleted(context: PublicationContext, receipt: 'NO_CONTENT' | 'COMPLETION_MARKER') {
    readGeneration++
    detailSequence++
    state = {
      ...initial(),
      deleted: {
        projectId: context.projectId,
        applicationId: context.applicationId,
        identity: context.identity,
        receipt
      },
      notice:
        receipt === 'NO_CONTENT'
          ? '应用已软删除（收到204无正文回执）。'
          : '原软删除请求已完成；完成标记不重放原204回执。'
    }
    ambiguous = false
  }
  /** 读取明确拒绝使旧业务视图失效，但不能证明正在恢复的写请求未执行。 */
  function readFailure(error: unknown, preserveDeletion = true) {
    const rejection = error as {
      code?: number
      status?: number
      response?: { status?: number }
    } | null
    if (
      rejection &&
      ([401, 403, 404, 30001, 50001, 60030].includes(rejection.code ?? 0) ||
        missingApplicationResource(error) ||
        rejection.status === 401 ||
        rejection.response?.status === 401 ||
        rejection.status === 403 ||
        rejection.response?.status === 403 ||
        (rejection.code !== undefined && rejection.code >= 20000 && rejection.code < 30000))
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
      if (state.pending?.kind === 'SOFT_DELETE') {
        if (
          preserveDeletion &&
          state.pending.status === 'UNKNOWN' &&
          missingApplicationResource(error)
        )
          ports.deleteResourceUnavailable?.()
        else {
          reset()
          ports.accessDenied?.(rejection.code)
        }
      } else ports.accessDenied?.(rejection.code)
    }
    fail(error)
    changed()
  }
  async function facts(unlockCompleted: boolean) {
    if (state.deleted) return
    const context = ports.context()
    if (
      !context.available ||
      !context.canRead ||
      !uuid(context.projectId) ||
      !uuid(context.applicationId) ||
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
        await ports.detail(context.projectId, context.applicationId),
        context.applicationId
      )
      if (
        generation !== currentGeneration ||
        readEpoch !== readGeneration ||
        !contextMatches(context)
      )
        return
      const nextPage = page(await ports.list(context.projectId, context.applicationId))
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
      const next = page(await ports.list(context.projectId, context.applicationId, cursor))
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
      const detail = versionDetail(
        await ports.version(context.projectId, context.applicationId, id)
      )
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
    const context = { ...ports.context() },
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
      retainUnobservedDeletion(context, epoch)
      if (epoch !== generation || !contextMatches(context) || !ports.context().canManage) return
      if (intent.kind === 'SOFT_DELETE') {
        requireThat(result === undefined)
        deleted(context, 'NO_CONTENT')
      } else if (intent.kind !== 'WITHDRAW') {
        const received = versionDetail(result)
        requireThat(
          intent.kind === 'ROLLBACK'
            ? received.id === intent.targetVersionId
            : received.sourceDraftRevision === intent.body.expectedDraftRevision
        )
      } else requireThat(result === undefined || result === null)
      if (intent.kind !== 'SOFT_DELETE') state.pending = { ...intent, status: 'RECEIVED' }
    } catch (error) {
      retainUnobservedDeletion(context, epoch)
      if (epoch !== generation || !contextMatches(context) || !ports.context().canManage) return
      const failure = error as { code?: number; status?: number; outcomeUnknown?: boolean }
      // 管理403不等于失去读取权；确认身份/项目/应用不可读则立即清视图并通知编辑区。
      if (
        intent.kind === 'SOFT_DELETE' &&
        !failure.outcomeUnknown &&
        (failure.status === 401 ||
          failure.status === 403 ||
          missingApplicationResource(error) ||
          [401, 30001, 50001].includes(failure.code ?? 0) ||
          (failure.code !== undefined && failure.code >= 20000 && failure.code < 30000))
      ) {
        readFailure(error, ambiguous)
        if (!missingApplicationResource(error) || !ambiguous) return
      } else if (
        intent.kind !== 'SOFT_DELETE' &&
        ([401, 30001, 50001, 60030].includes(failure.code ?? 0) ||
          (failure.code !== undefined && failure.code >= 20000 && failure.code < 30000))
      )
        readFailure(error)
      if (
        failure.code === 10014 &&
        (intent.kind !== 'SOFT_DELETE' ||
          (failure.status === 409 && failure.outcomeUnknown !== true))
      ) {
        if (intent.kind === 'SOFT_DELETE') deleted(context, 'COMPLETION_MARKER')
        else state.pending = { ...intent, status: 'COMPLETED' }
      } else if (
        failure.code === 10010 ||
        (intent.kind === 'SOFT_DELETE' && failure.code === 10014) ||
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
      if (epoch === generation && identityMatches(context)) {
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
      state.deleted ||
      state.loading ||
      state.writing ||
      state.pending ||
      !state.catalog
    )
      return
    if (
      (kind === 'PUBLISH' || kind === 'SOFT_DELETE') &&
      (context.dirty || context.saving || context.conflict || !revision(context.draftRevision))
    ) {
      state.error = '请先保存有效草稿并解决冲突，再发布或软删除。'
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
      applicationId: context.applicationId,
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
    softDelete: () => begin('SOFT_DELETE'),
    retry: execute,
    recover,
    reset,
    getSnapshot: snapshot
  }
}
