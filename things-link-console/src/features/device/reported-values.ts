/** ADR0112：接受序号是正 Long 字符串，不能经 Number 舍入。 */
export function reportedRevision(value: unknown): string | undefined {
  if (typeof value !== 'string' || !/^[1-9][0-9]{0,18}$/.test(value)) return undefined
  return value.length === 19 && value > '9223372036854775807' ? undefined : value
}
/** 两个已校验的正 Long 按十进制长度和字典序比较。 */
export function compareReportedRevision(left: string, right: string): number {
  return left.length !== right.length
    ? left.length - right.length
    : left === right
      ? 0
      : left > right
        ? 1
        : -1
}
export interface ReportedFact {
  value: unknown
  occurredAt: string
  reportedRevision?: string
  thingModelVersionId?: string
}
export interface ReportedProjection {
  values?: Record<string, unknown>
  occurredAt?: Record<string, string>
  reportedRevisions?: Record<string, string>
  thingModelVersionIds?: Record<string, string>
}
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
/** 同一详情会话的逐属性事实；缺来源保持未知，不能借当前模型补齐。 */
export class ReportedValues {
  private facts = new Map<string, ReportedFact>()
  clear() {
    this.facts.clear()
  }
  entries(): readonly (readonly [string, ReportedFact])[] {
    return [...this.facts]
  }
  values(): Record<string, unknown> {
    return Object.fromEntries(this.entries().map(([key, fact]) => [key, fact.value]))
  }
  /** REST 可恢复历史未知值；WS 无序号只要求补拉。返回是否存在需补拉的属性。 */
  merge(projection: ReportedProjection, keys: readonly string[], realtime = false): boolean {
    let dirty = false
    for (const key of keys) {
      if (!Object.hasOwn(projection.values ?? {}, key)) continue
      const rawRevision = projection.reportedRevisions?.[key]
      const revision = reportedRevision(rawRevision)
      if ((rawRevision !== undefined && !revision) || (realtime && !revision)) {
        dirty = true
        continue
      }
      const previous = this.facts.get(key)
      if (
        previous?.reportedRevision &&
        (!revision || compareReportedRevision(revision, previous.reportedRevision) <= 0)
      )
        continue
      const source = projection.thingModelVersionIds?.[key]
      if (source !== undefined && (typeof source !== 'string' || !uuid.test(source))) {
        dirty = true
        continue
      }
      this.facts.set(key, {
        value: projection.values![key],
        occurredAt: projection.occurredAt?.[key] ?? '',
        reportedRevision: revision,
        thingModelVersionId: source
      })
    }
    return dirty
  }
}
