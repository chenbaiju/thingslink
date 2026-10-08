/** 最近访问只保存对象引用；服务端仍在再次打开时裁决权限和对象存在性。 */
export interface RecentScope {
  userId: string
  tenantId: string
  projectId: string
}
export interface RecentResource {
  kind: 'type' | 'device' | 'application'
  id: string
  label: string
  visitedAt?: number
}
const PREFIX = 'thingslink-recent-v1:'
const TTL = 7 * 24 * 60 * 60 * 1000
const validId = (value: unknown): value is string =>
  typeof value === 'string' && /^[a-zA-Z0-9-]{1,80}$/.test(value)
const key = (scope: RecentScope) =>
  [scope.userId, scope.tenantId, scope.projectId].every(validId)
    ? PREFIX + [scope.userId, scope.tenantId, scope.projectId].join(':')
    : ''
export function readRecentResources(scope: RecentScope, now = Date.now()): RecentResource[] {
  try {
    const storageKey = key(scope)
    if (!storageKey) return []
    const rows: unknown = JSON.parse(localStorage.getItem(storageKey) ?? '[]')
    if (!Array.isArray(rows)) return []
    return rows
      .filter((row): row is RecentResource =>
        Boolean(
          row &&
            ['type', 'device', 'application'].includes(row.kind) &&
            validId(row.id) &&
            typeof row.label === 'string' &&
            row.label.length <= 120 &&
            typeof row.visitedAt === 'number' &&
            row.visitedAt <= now &&
            now - row.visitedAt < TTL
        )
      )
      .slice(0, 6)
  } catch {
    return []
  }
}
export function recordRecentResource(
  scope: RecentScope,
  resource: RecentResource,
  preserveExistingLabel = false
): void {
  const storageKey = key(scope)
  if (!storageKey || !validId(resource.id)) return
  const existing = readRecentResources(scope)
  const previous = existing.find((row) => row.id === resource.id && row.kind === resource.kind)
  const rows = existing.filter((row) => row.id !== resource.id || row.kind !== resource.kind)
  try {
    localStorage.setItem(
      storageKey,
      JSON.stringify(
        [
          {
            kind: resource.kind,
            id: resource.id,
            label: (preserveExistingLabel && previous ? previous.label : resource.label).slice(
              0,
              120
            ),
            visitedAt: Date.now()
          },
          ...rows
        ].slice(0, 6)
      )
    )
  } catch {
    /* 存储不可用不影响业务读取。 */
  }
}
/** 仅更新已访问对象的名称，不增加访问记录，也不改变访问时间和顺序。 */
export function renameRecentResource(
  scope: RecentScope,
  kind: RecentResource['kind'],
  id: string,
  label: string
): void {
  const storageKey = key(scope)
  if (!storageKey) return
  const rows = readRecentResources(scope)
  if (!rows.some((row) => row.kind === kind && row.id === id)) return
  try {
    localStorage.setItem(
      storageKey,
      JSON.stringify(
        rows.map((row) =>
          row.kind === kind && row.id === id ? { ...row, label: label.slice(0, 120) } : row
        )
      )
    )
  } catch {
    /* 存储不可用不影响服务端重命名结果。 */
  }
}
export function clearRecentResources(scope?: RecentScope): void {
  try {
    if (scope) {
      const storageKey = key(scope)
      if (storageKey) localStorage.removeItem(storageKey)
      return
    }
    for (const storageKey of Array.from(
      { length: localStorage.length },
      (_, i) => localStorage.key(i) ?? ''
    ))
      if (storageKey.startsWith(PREFIX)) localStorage.removeItem(storageKey)
  } catch {
    /* 隐私模式下不阻断退出。 */
  }
}
