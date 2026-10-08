import {
  isStrictJsonNumber,
  parseDashboardRuntimeResponse,
  type StrictJsonObject,
  type StrictJsonValue
} from '@things-link/client-contracts/dashboard/v1'
import type {
  PropertyHistoryFilters,
  PropertyHistoryItem,
  PropertyHistoryPage
} from '@/api/device-property-history'
import { currentIdentityEpoch } from '@/utils/http/identity-scope'

function fail(): never {
  throw new Error('原始属性历史响应不符合合同。')
}
function object(value: StrictJsonValue): StrictJsonObject {
  if (!value || typeof value !== 'object' || Array.isArray(value) || isStrictJsonNumber(value))
    return fail()
  return value as StrictJsonObject
}
/** 值只作为文本展示，不经过 Number 或 JSON.parse 损失整数和小数词法。 */
export function propertyValueText(value: StrictJsonValue): string {
  if (isStrictJsonNumber(value)) return value.lexical
  if (value === null || typeof value !== 'object') return JSON.stringify(value)
  if (Array.isArray(value)) return `[${value.map(propertyValueText).join(', ')}]`
  return `{${Object.entries(value)
    .map(([key, item]) => `${JSON.stringify(key)}: ${propertyValueText(item)}`)
    .join(', ')}}`
}
export function decodePropertyPage(bytes: Uint8Array, deviceId: string): PropertyHistoryPage {
  const page = parseDashboardRuntimeResponse(bytes)
  if (
    Object.keys(page).length !== 3 ||
    !Array.isArray(page.items) ||
    page.items.length > 20 ||
    typeof page.hasMore !== 'boolean' ||
    (page.nextCursor !== null &&
      (typeof page.nextCursor !== 'string' || !page.nextCursor || page.nextCursor.length > 8192)) ||
    page.hasMore !== (typeof page.nextCursor === 'string')
  )
    fail()
  const fields = [
    'deviceId',
    'propertyKey',
    'ts',
    'value',
    'dataType',
    'thingModelVersionId',
    'modelVersion',
    'quality'
  ]
  const items = page.items.map((raw) => {
    const item = object(raw)
    if (
      Object.keys(item).length !== fields.length ||
      fields.some((key) => !Object.hasOwn(item, key)) ||
      item.deviceId !== deviceId
    )
      fail()
    for (const field of ['propertyKey', 'ts', 'dataType', 'modelVersion'])
      if (typeof item[field] !== 'string' || !item[field]) fail()
    if (item.thingModelVersionId !== null && typeof item.thingModelVersionId !== 'string') fail()
    const value = item.value!
    if (
      (item.dataType === 'NUMBER' && !isStrictJsonNumber(value)) ||
      (['TEXT', 'ENUM'].includes(item.dataType as string) && typeof value !== 'string') ||
      (item.dataType === 'SWITCH' && typeof value !== 'boolean') ||
      (item.dataType === 'OBJECT' &&
        (!value ||
          typeof value !== 'object' ||
          Array.isArray(value) ||
          isStrictJsonNumber(value))) ||
      (item.dataType === 'LIST' && !Array.isArray(value)) ||
      !['NUMBER', 'TEXT', 'ENUM', 'SWITCH', 'OBJECT', 'LIST'].includes(item.dataType as string) ||
      !isStrictJsonNumber(item.quality!) ||
      !/^-?(0|[1-9][0-9]*)$/.test(item.quality.lexical)
    )
      fail()
    const quality = Number(item.quality.lexical)
    if (!Number.isInteger(quality) || quality < -32768 || quality > 32767) fail()
    return {
      deviceId,
      propertyKey: item.propertyKey,
      ts: item.ts,
      dataType: item.dataType,
      thingModelVersionId: item.thingModelVersionId,
      modelVersion: item.modelVersion,
      quality,
      valueText: propertyValueText(value)
    } as PropertyHistoryItem
  })
  return { items, nextCursor: page.nextCursor || undefined, hasMore: page.hasMore }
}
export const emptyPropertyFilters = (): PropertyHistoryFilters => ({
  propertyKey: '',
  from: '',
  to: ''
})
export interface PropertyHistoryPort {
  list(
    project: string,
    device: string,
    filters: PropertyHistoryFilters,
    cursor?: string,
    signal?: AbortSignal
  ): Promise<PropertyHistoryPage>
}
export class PropertyHistoryModel {
  filters = emptyPropertyFilters()
  items: PropertyHistoryItem[] = []
  nextCursor?: string
  loading = false
  error = ''
  private project = ''
  private device = ''
  private active = false
  private generation = 0
  private run = 0
  private identity = -1
  private applied = emptyPropertyFilters()
  private controller?: AbortController
  constructor(
    private port: PropertyHistoryPort,
    private epoch = currentIdentityEpoch
  ) {}
  scope(project: string, device: string, active: boolean) {
    const identity = this.epoch()
    if (
      project === this.project &&
      device === this.device &&
      active === this.active &&
      identity === this.identity
    )
      return
    this.close()
    this.project = project
    this.device = device
    this.active = active
    this.identity = identity
    if (active && project && device) void this.refresh()
  }
  close() {
    this.generation++
    this.run++
    this.controller?.abort()
    this.active = false
    this.filters = emptyPropertyFilters()
    this.applied = emptyPropertyFilters()
    this.items = []
    this.nextCursor = undefined
    this.error = ''
    this.loading = false
  }
  async refresh() {
    this.applied = { ...this.filters }
    await this.load()
  }
  async next() {
    if (this.nextCursor && !this.loading && !this.error) await this.load(this.nextCursor)
  }
  private async load(cursor?: string) {
    if (!this.active) return
    const generation = this.generation,
      identity = this.epoch(),
      run = ++this.run
    const current = () =>
      this.active &&
      generation === this.generation &&
      identity === this.epoch() &&
      identity === this.identity &&
      run === this.run
    this.controller?.abort()
    this.controller = new AbortController()
    this.items = []
    this.nextCursor = undefined
    this.error = ''
    this.loading = true
    try {
      const page = await this.port.list(
        this.project,
        this.device,
        { ...this.applied },
        cursor,
        this.controller.signal
      )
      if (!current()) return
      this.items = page.items
      this.nextCursor = page.hasMore ? page.nextCursor : undefined
    } catch {
      if (current()) this.error = '原始属性历史不可用，请检查筛选条件或刷新重试。'
    } finally {
      if (current()) this.loading = false
    }
  }
}
