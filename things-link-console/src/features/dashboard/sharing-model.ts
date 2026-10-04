import {
  validateDashboardSchemaV1,
  type DashboardSchemaV1
} from '@things-link/client-contracts/dashboard/v1'
import type { BindingMetadata } from '@/api/dashboard-binding'
export interface SharingContext {
  projectId: string
  dashboardId: string
  identity: number
  available: boolean
  canManage: boolean
}
export interface ShareConfiguration {
  available: boolean
  hostOrigin: string | null
  hostVersion: string | null
  hostCompatibility: { minInclusive: string; maxExclusive: string } | null
}
export interface ShareCreateIntent {
  projectId: string
  dashboardId: string
  key: string
  body: {
    dashboardVersionId: string
    expectedDashboardPublicationRevision: string
    expiresInSeconds: number
    refererPolicy: 'HOST_ORIGIN'
    hostCompatibility: { minInclusive: string; maxExclusive: string }
    variables: { variableKey: string; deviceIds: string[] }[]
  }
}
export interface ShareSummary {
  shareId: string
  dashboardVersionId: string
  dashboardVersionNumber: string
  status: 'ACTIVE' | 'EXPIRED' | 'REVOKED'
  refererPolicy: 'NONE' | 'HOST_ORIGIN'
  hostCompatibility: { minInclusive: string; maxExclusive: string }
  expiresAt: string
  createdAt: string
  revokedAt: string | null
}
export interface ShareVariable {
  variableKey: string
  title: string
  modelKey: string
  deviceIds: string[]
  defaultDeviceIds: string[]
}
export interface SharingSnapshot {
  configuration: ShareConfiguration | null
  items: ShareSummary[]
  nextCursor: string | null
  listLoaded: boolean
  scopeDenied: boolean
  loading: boolean
  writing: boolean
  pending: ShareCreateIntent | null
  revokePending: string | null
  recoverShareId: string | null
  hasSecret: boolean
  maskedLink: string
  createdShareId: string | null
  createdExpiresAt: string | null
  variables: ShareVariable[]
  versionId: string | null
  versionNumber: string | null
  pageCount: number
  error: string
  notice: string
}
export interface SharingPorts {
  context(): SharingContext
  configuration(projectId: string, dashboardId: string): Promise<unknown>
  list(projectId: string, dashboardId: string, cursor?: string): Promise<unknown>
  create(intent: ShareCreateIntent): Promise<unknown>
  revoke(projectId: string, dashboardId: string, shareId: string): Promise<unknown>
  deviceMetadata(projectId: string, deviceId: string): Promise<BindingMetadata>
  newKey(): string
  changed(snapshot: SharingSnapshot): void
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
  if (!value) throw new Error('分享内容不符合合同。')
}
function object(value: unknown): Record<string, unknown> {
  requireThat(value && typeof value === 'object' && !Array.isArray(value))
  return value as Record<string, unknown>
}
function semver(value: unknown): value is string {
  return (
    typeof value === 'string' &&
    /^(0|[1-9]\d{0,8})\.(0|[1-9]\d{0,8})\.(0|[1-9]\d{0,8})$/.test(value)
  )
}
function compareVersion(a: string, b: string) {
  const x = a.split('.').map(Number),
    y = b.split('.').map(Number)
  for (let i = 0; i < 3; i++) if (x[i] !== y[i]) return x[i]! < y[i]! ? -1 : 1
  return 0
}
function range(value: unknown) {
  const data = object(value)
  requireThat(
    semver(data.minInclusive) &&
      semver(data.maxExclusive) &&
      compareVersion(data.minInclusive, data.maxExclusive) < 0
  )
  return { minInclusive: data.minInclusive, maxExclusive: data.maxExclusive }
}
function configuration(value: unknown): ShareConfiguration {
  const data = object(value)
  requireThat(typeof data.available === 'boolean')
  if (!data.available) {
    requireThat(
      data.hostOrigin === null && data.hostVersion === null && data.hostCompatibility === null
    )
    return { available: false, hostOrigin: null, hostVersion: null, hostCompatibility: null }
  }
  requireThat(
    typeof data.hostOrigin === 'string' &&
      typeof data.hostVersion === 'string' &&
      /^\d+\.\d+\.\d+$/.test(data.hostVersion)
  )
  const url = new URL(data.hostOrigin)
  requireThat(
    !url.username &&
      !url.password &&
      url.pathname === '/' &&
      !url.search &&
      !url.hash &&
      (url.protocol === 'https:' ||
        (url.protocol === 'http:' && ['localhost', '127.0.0.1', '[::1]'].includes(url.hostname)))
  )
  const compatibility = range(data.hostCompatibility)
  requireThat(
    semver(data.hostVersion) &&
      compareVersion(compatibility.minInclusive, data.hostVersion) <= 0 &&
      compareVersion(data.hostVersion, compatibility.maxExclusive) < 0
  )
  return {
    available: true,
    hostOrigin: url.origin,
    hostVersion: data.hostVersion,
    hostCompatibility: compatibility
  }
}
function page(value: unknown) {
  const data = object(value)
  requireThat(
    Array.isArray(data.items) && data.items.length <= 20 && typeof data.hasMore === 'boolean'
  )
  const items = data.items.map((raw): ShareSummary => {
    const item = object(raw)
    requireThat(
      uuid(item.shareId) &&
        uuid(item.dashboardVersionId) &&
        revision(item.dashboardVersionNumber) &&
        item.dashboardVersionNumber !== '0' &&
        ['ACTIVE', 'EXPIRED', 'REVOKED'].includes(item.status as string) &&
        ['NONE', 'HOST_ORIGIN'].includes(item.refererPolicy as string) &&
        instant(item.expiresAt) &&
        instant(item.createdAt) &&
        (item.revokedAt === null || instant(item.revokedAt))
    )
    return {
      shareId: item.shareId,
      dashboardVersionId: item.dashboardVersionId,
      dashboardVersionNumber: item.dashboardVersionNumber,
      status: item.status as ShareSummary['status'],
      refererPolicy: item.refererPolicy as ShareSummary['refererPolicy'],
      hostCompatibility: range(item.hostCompatibility),
      expiresAt: item.expiresAt,
      createdAt: item.createdAt,
      revokedAt: item.revokedAt
    }
  })
  requireThat(new Set(items.map((item) => item.shareId)).size === items.length)
  requireThat(
    !data.hasMore ||
      (items.length > 0 &&
        typeof data.nextCursor === 'string' &&
        data.nextCursor.length > 0 &&
        data.nextCursor.length <= 2048)
  )
  return { items, nextCursor: data.hasMore ? (data.nextCursor as string) : null }
}
const initial = (): SharingSnapshot => ({
  configuration: null,
  items: [],
  nextCursor: null,
  listLoaded: false,
  scopeDenied: false,
  loading: false,
  writing: false,
  pending: null,
  revokePending: null,
  recoverShareId: null,
  hasSecret: false,
  maskedLink: '',
  createdShareId: null,
  createdExpiresAt: null,
  variables: [],
  versionId: null,
  versionNumber: null,
  pageCount: 0,
  error: '',
  notice: ''
})
/** secret只留闭包；快照、错误和页面标识均不包含完整链接。 */
export function createDashboardSharing(ports: SharingPorts) {
  let state = initial(),
    generation = 0,
    owner: SharingContext | null = null,
    secretLink: string | undefined,
    schema: DashboardSchemaV1 | null = null,
    publicationRevision = '',
    ambiguous = false
  const snapshot = () => structuredClone(state),
    changed = () => ports.changed(snapshot())
  const matches = (context: SharingContext) => {
    const now = ports.context()
    return (
      now.available &&
      now.canManage &&
      context.identity === now.identity &&
      context.projectId === now.projectId &&
      context.dashboardId === now.dashboardId
    )
  }
  const writable = () =>
    owner &&
    matches(owner) &&
    !state.loading &&
    !state.writing &&
    !state.pending &&
    !state.revokePending &&
    !state.recoverShareId
  function clearSecret() {
    secretLink = undefined
    state.hasSecret = false
    state.maskedLink = ''
    state.createdShareId = null
    state.createdExpiresAt = null
  }
  function reset() {
    generation++
    owner = null
    schema = null
    publicationRevision = ''
    ambiguous = false
    secretLink = undefined
    state = initial()
    changed()
  }
  function suspend() {
    if (state.pending) ambiguous = true
    generation++
    schema = null
    publicationRevision = ''
    secretLink = undefined
    state = {
      ...initial(),
      pending: state.pending,
      revokePending: state.revokePending,
      recoverShareId: state.recoverShareId
    }
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
      const denied = (error: unknown) => {
        const failure = error as { code?: number; status?: number }
        return (
          [401, 403, 404].includes(failure.status ?? 0) ||
          [401, 403, 404, 30001, 50001, 60034, 60035].includes(failure.code ?? 0)
        )
      }
      const immediateDenial = (error: unknown): never => {
        if (epoch === generation && matches(context) && denied(error)) {
          suspend()
          state.scopeDenied = true
          state.error = '分享状态读取失败，请明确刷新。'
          changed()
        }
        throw error
      }
      const results = await Promise.allSettled([
        ports.configuration(context.projectId, context.dashboardId).catch(immediateDenial),
        ports
          .list(context.projectId, context.dashboardId, cursor ?? undefined)
          .catch(immediateDenial)
      ])
      if (epoch !== generation || !matches(context)) return
      state.scopeDenied = results.some(
        (result) => result.status === 'rejected' && denied(result.reason)
      )
      if (state.scopeDenied) throw new Error('scope denied')
      const [configResult, listResult] = results
      if (listResult.status === 'rejected') throw listResult.reason
      const result = page(listResult.value)
      if (next) requireThat(result.nextCursor !== cursor)
      state.configuration = null
      try {
        if (configResult.status === 'rejected') throw configResult.reason
        state.configuration = configuration(configResult.value)
      } catch {
        state.error = '创建配置暂不可用，已有分享仍可查看和撤销。'
      }
      state.items = result.items
      state.nextCursor = result.nextCursor
      state.listLoaded = true
    } catch {
      if (epoch === generation && matches(context)) {
        clearSecret()
        schema = null
        publicationRevision = ''
        state.configuration = null
        state.items = []
        state.listLoaded = false
        state.nextCursor = null
        state.variables = []
        state.versionId = null
        state.versionNumber = null
        state.pageCount = 0
        state.error = '分享状态读取失败，请明确刷新。'
      }
    } finally {
      if (epoch === generation && matches(context)) {
        state.loading = false
        changed()
      }
    }
  }
  function selectVersion(
    value: { id: string; versionNumber: string; schema: unknown },
    revisionValue: string
  ) {
    if (!writable()) return
    try {
      requireThat(
        uuid(value.id) &&
          revision(value.versionNumber) &&
          value.versionNumber !== '0' &&
          revision(revisionValue)
      )
      const normalized = validateDashboardSchemaV1(
        new TextEncoder().encode(JSON.stringify(value.schema))
      ).schema
      const variables = normalized.variables.flatMap((variable) =>
        variable.type === 'DEVICE_SINGLE' || variable.type === 'DEVICE_MULTI'
          ? [
              {
                variableKey: variable.key,
                title: variable.title,
                modelKey: variable.modelKey,
                deviceIds:
                  variable.type === 'DEVICE_SINGLE'
                    ? variable.defaultDeviceId
                      ? [variable.defaultDeviceId]
                      : []
                    : [...variable.defaultDeviceIds],
                defaultDeviceIds:
                  variable.type === 'DEVICE_SINGLE'
                    ? variable.defaultDeviceId
                      ? [variable.defaultDeviceId]
                      : []
                    : [...variable.defaultDeviceIds]
              }
            ]
          : []
      )
      clearSecret()
      schema = normalized
      publicationRevision = revisionValue
      state.versionId = value.id
      state.versionNumber = value.versionNumber
      state.pageCount = normalized.pages.length
      state.variables = variables
      state.error = ''
      state.notice = ''
    } catch {
      state.error = '版本内容读取无效，未采用该版本。'
    }
    changed()
  }
  function candidate(variableKey: string, deviceId: string, add = true) {
    if (!writable()) return
    const variable = state.variables.find((entry) => entry.variableKey === variableKey)
    if (!variable || !uuid(deviceId)) return
    clearSecret()
    if (add && !variable.deviceIds.includes(deviceId)) {
      if (new Set(state.variables.flatMap((entry) => entry.deviceIds).concat(deviceId)).size > 20) {
        state.error = '全部候选设备合计不能超过20台。'
        changed()
        return
      }
      variable.deviceIds.push(deviceId)
    } else if (!add) {
      if (variable.defaultDeviceIds.includes(deviceId)) {
        state.error = '默认设备必须保留在授权范围内。'
        changed()
        return
      }
      variable.deviceIds = variable.deviceIds.filter((id) => id !== deviceId)
    }
    state.error = ''
    changed()
  }
  async function execute() {
    const intent = state.pending,
      context = ports.context(),
      epoch = generation
    if (!intent || state.writing || state.loading || !owner || !matches(owner)) return
    state.writing = true
    state.error = ''
    changed()
    try {
      const response = await ports.create(structuredClone(intent))
      if (epoch !== generation || !matches(context)) return
      const data = object(response)
      requireThat(
        uuid(data.shareId) &&
          typeof data.secret === 'string' &&
          /^sh_[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]$/.test(data.secret) &&
          instant(data.expiresAt)
      )
      requireThat(state.configuration?.available && state.configuration.hostOrigin)
      secretLink = `${state.configuration.hostOrigin}/app/share/${data.shareId}#token=${data.secret}`
      state.hasSecret = true
      state.maskedLink = `${state.configuration.hostOrigin}/app/share/${data.shareId}#token=••••••`
      state.createdShareId = data.shareId
      state.createdExpiresAt = data.expiresAt
      state.pending = null
      state.notice = '分享已创建；完整链接仅本次可复制，请妥善保管。'
    } catch (error) {
      if (epoch !== generation || !matches(context)) return
      const failure = error as {
        code?: number
        status?: number
        outcomeUnknown?: boolean
        details?: unknown
      }
      if (
        failure.code === 60052 &&
        failure.status === 409 &&
        Array.isArray(failure.details) &&
        failure.details.length === 1 &&
        uuid(failure.details[0])
      ) {
        clearSecret()
        state.recoverShareId = failure.details[0]
        state.pending = null
        state.notice = '分享已创建，但链接无法恢复。可撤销这个分享，再明确创建新分享。'
      } else if (
        failure.code === 60052 ||
        failure.outcomeUnknown ||
        !failure.code ||
        failure.status === undefined ||
        failure.status >= 500 ||
        failure.code === 10010 ||
        ambiguous
      ) {
        ambiguous = true
        state.error = '创建结果尚未确认，请重试原操作；刷新列表不能证明它未执行。'
      } else {
        state.pending = null
        state.error = '分享创建被明确拒绝，请核对权限、版本、设备范围与期限后再试。'
      }
    } finally {
      if (epoch === generation && matches(context)) {
        state.writing = false
        changed()
      }
    }
  }
  async function create(expiresInSeconds: number) {
    if (
      !writable() ||
      !schema ||
      !state.configuration?.available ||
      !state.configuration.hostCompatibility ||
      !state.versionId
    )
      return
    const context = ports.context(),
      epoch = generation
    let body: ShareCreateIntent['body']
    try {
      requireThat(
        Number.isInteger(expiresInSeconds) && expiresInSeconds >= 300 && expiresInSeconds <= 86400
      )
      requireThat(
        state.variables.every(
          (variable) =>
            variable.deviceIds.length >= 1 &&
            variable.deviceIds.length <= 20 &&
            variable.defaultDeviceIds.every((id) => variable.deviceIds.includes(id))
        ) && new Set(state.variables.flatMap((variable) => variable.deviceIds)).size <= 20
      )
      body = {
        dashboardVersionId: state.versionId,
        expectedDashboardPublicationRevision: publicationRevision,
        expiresInSeconds,
        refererPolicy: 'HOST_ORIGIN',
        hostCompatibility: { ...state.configuration.hostCompatibility },
        variables: state.variables.map((variable) => ({
          variableKey: variable.variableKey,
          deviceIds: [...variable.deviceIds]
        }))
      }
    } catch {
      state.error = '请检查期限、全部变量的候选设备与默认设备覆盖。'
      changed()
      return
    }
    state.writing = true
    state.error = ''
    clearSecret()
    changed()
    try {
      for (const id of new Set(body.variables.flatMap((variable) => variable.deviceIds))) {
        const meta = await ports.deviceMetadata(context.projectId, id)
        if (epoch !== generation || !matches(context)) return
        for (const variable of state.variables.filter((variable) =>
          variable.deviceIds.includes(id)
        )) {
          const model = schema.models.find((model) => model.key === variable.modelKey)
          requireThat(
            model &&
              meta.model.versionId === model.versionId &&
              meta.model.digest === model.digest &&
              meta.model.digestAlgorithm === model.digestAlgorithm &&
              meta.model.profile === model.profile
          )
        }
      }
      if (epoch !== generation || !matches(context)) return
      state.pending = {
        projectId: context.projectId,
        dashboardId: context.dashboardId,
        key: ports.newKey(),
        body
      }
      ambiguous = false
    } catch {
      if (epoch === generation && matches(context))
        state.error = '候选设备不可用或物模型已变化，未发出创建请求。'
    } finally {
      if (epoch === generation && matches(context)) {
        state.writing = false
        changed()
      }
    }
    if (epoch === generation && matches(context) && state.pending) await execute()
  }
  async function revoke(shareId: string) {
    const context = ports.context(),
      epoch = generation
    if (
      !owner ||
      !matches(owner) ||
      state.loading ||
      state.writing ||
      !uuid(shareId) ||
      state.pending ||
      (state.revokePending && state.revokePending !== shareId)
    )
      return
    if (
      !state.items.some((item) => item.shareId === shareId) &&
      state.recoverShareId !== shareId &&
      state.createdShareId !== shareId &&
      state.revokePending !== shareId
    )
      return
    state.revokePending = shareId
    state.writing = true
    state.error = ''
    changed()
    try {
      const result = await ports.revoke(context.projectId, context.dashboardId, shareId)
      if (epoch !== generation || !matches(context)) return
      requireThat(result === null || result === undefined)
      state.revokePending = null
      if (state.recoverShareId === shareId) state.recoverShareId = null
      if (state.createdShareId === shareId) clearSecret()
      state.items = state.items.map((item) =>
        item.shareId === shareId ? { ...item, status: 'REVOKED' } : item
      )
      state.notice = '撤销已完成；如需分享，请明确创建新的链接。'
    } catch {
      if (epoch === generation && matches(context))
        state.error = '撤销尚未确认，可重试撤销同一分享。'
    } finally {
      if (epoch === generation && matches(context)) {
        state.writing = false
        changed()
      }
    }
  }
  async function copyLink(writeText: (value: string) => Promise<void>) {
    const context = ports.context(),
      epoch = generation
    if (!owner || !matches(owner) || !secretLink) return
    try {
      await writeText(secretLink)
      if (epoch === generation && matches(context)) {
        state.notice = '完整分享链接已复制。'
        changed()
      }
    } catch {
      if (epoch === generation && matches(context)) {
        state.error = '复制失败，请在当前页面重试复制。'
        changed()
      }
    }
  }
  return {
    getSnapshot: snapshot,
    open,
    loadMore: () => open(true),
    selectVersion,
    addCandidate: (key: string, id: string) => candidate(key, id),
    removeCandidate: (key: string, id: string) => candidate(key, id, false),
    create,
    retry: execute,
    revoke,
    copyLink,
    reset,
    suspend
  }
}
