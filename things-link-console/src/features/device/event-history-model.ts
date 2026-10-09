import {
  isStrictJsonNumber,
  parseDashboardRuntimeResponse,
  type StrictJsonObject,
  type StrictJsonValue
} from '@things-link/client-contracts/dashboard/v1'
import type {
  DeviceEventItem,
  DeviceEventPage,
  EventHistoryFilters
} from '@/api/device-event-history'
import { currentIdentityEpoch } from '@/utils/http/identity-scope'

const fields = [
  'messageId',
  'deviceId',
  'deviceTypeId',
  'eventKey',
  'level',
  'thingModelVersionId',
  'modelVersion',
  'eligibility',
  'occurredAt',
  'receivedAt',
  'acceptedAt',
  'params',
  'paramsRedacted'
]
const encoder = new TextEncoder()
function fail(): never {
  throw new Error('事件历史响应不符合只读合同。')
}
function object(value: StrictJsonValue): StrictJsonObject {
  if (!value || typeof value !== 'object' || Array.isArray(value) || isStrictJsonNumber(value))
    return fail()
  return value as StrictJsonObject
}
function closed(value: StrictJsonObject, keys: string[]) {
  if (Object.keys(value).length !== keys.length || keys.some((key) => !Object.hasOwn(value, key)))
    fail()
}
function render(value: StrictJsonValue): string {
  if (isStrictJsonNumber(value)) return value.lexical
  if (value === null || typeof value === 'boolean' || typeof value === 'string')
    return JSON.stringify(value)
  if (Array.isArray(value)) return fail()
  return `{\n${Object.entries(value)
    .map(([key, item]) => `  ${JSON.stringify(key)}: ${render(item)}`)
    .join(',\n')}\n}`
}
function row(value: StrictJsonObject, deviceId: string, messageId?: string): DeviceEventItem {
  closed(value, fields)
  for (const key of fields.filter((key) => !['params', 'paramsRedacted'].includes(key)))
    if (typeof value[key] !== 'string' || value[key] === '') fail()
  if (
    value.deviceId !== deviceId ||
    (messageId && value.messageId !== messageId) ||
    !['INFO', 'WARNING', 'ERROR'].includes(value.level as string) ||
    !['CURRENT', 'HISTORY_ONLY'].includes(value.eligibility as string) ||
    typeof value.paramsRedacted !== 'boolean'
  )
    fail()
  const params = object(value.params!)
  if (Object.keys(params).length > 100) fail()
  for (const item of Object.values(params))
    if (!(typeof item === 'string' || typeof item === 'boolean' || isStrictJsonNumber(item))) fail()
  const { params: _params, ...metadata } = value
  void _params
  return Object.freeze({ ...metadata, paramsText: render(params) }) as unknown as DeviceEventItem
}
export function decodeEvent(bytes: Uint8Array, deviceId: string, messageId?: string) {
  return row(parseDashboardRuntimeResponse(bytes), deviceId, messageId)
}
/** 仅分离顶层items原词法；结构、重复键、UTF8和数字仍交共享strict-json逐段复核。 */
function pageFrames(bytes: Uint8Array) {
  if (
    bytes.byteLength > 8 * 1024 * 1024 ||
    (bytes[0] === 0xef && bytes[1] === 0xbb && bytes[2] === 0xbf)
  )
    fail()
  const text = new TextDecoder('utf-8', { fatal: true }).decode(bytes)
  const skip = (start: number) => {
    while (/[ \n\r\t]/.test(text[start] ?? '') && start < text.length) start++
    return start
  }
  const endValue = (start: number) => {
    const brackets: string[] = []
    let quoted = false,
      escaped = false
    for (let index = start; index < text.length; index++) {
      const c = text[index]!
      if (quoted) {
        if (escaped) escaped = false
        else if (c === '\\') escaped = true
        else if (c === '"') quoted = false
        continue
      }
      if (c === '"') quoted = true
      else if (c === '{' || c === '[') {
        brackets.push(c)
        if (brackets.length > 16) fail()
      } else if (c === '}' || c === ']') {
        if (!brackets.length) return index
        const opening = brackets.pop()
        if ((c === '}' && opening !== '{') || (c === ']' && opening !== '[')) fail()
        if (!brackets.length) return index + 1
      } else if (c === ',' && !brackets.length) return index
    }
    return fail()
  }
  let index = skip(0)
  if (text[index++] !== '{') fail()
  let arrayStart = -1,
    arrayEnd = -1
  while (true) {
    index = skip(index)
    if (text[index] === '}') break
    if (text[index] !== '"') fail()
    let finish = index + 1,
      escaped = false
    for (; finish < text.length; finish++) {
      if (escaped) escaped = false
      else if (text[finish] === '\\') escaped = true
      else if (text[finish] === '"') break
    }
    const key: unknown = JSON.parse(text.slice(index, finish + 1)) // 只解码字段名，不解码参数数字。
    index = skip(finish + 1)
    if (text[index++] !== ':') fail()
    index = skip(index)
    const end = endValue(index)
    if (key === 'items') {
      if (arrayStart !== -1 || text[index] !== '[') fail()
      arrayStart = index
      arrayEnd = end
    }
    index = skip(end)
    if (text[index] === '}') break
    if (text[index++] !== ',') fail()
  }
  if (arrayStart < 0 || text[arrayEnd - 1] !== ']') fail()
  const metadata = parseDashboardRuntimeResponse(
    encoder.encode(text.slice(0, arrayStart) + '[]' + text.slice(arrayEnd))
  )
  const items: StrictJsonObject[] = []
  index = skip(arrayStart + 1)
  while (index < arrayEnd - 1) {
    const end = endValue(index)
    items.push(parseDashboardRuntimeResponse(encoder.encode(text.slice(index, end))))
    if (items.length > 100) fail()
    index = skip(end)
    if (text[index] === ']') break
    if (text[index++] !== ',') fail()
    index = skip(index)
    if (text[index] === ']') fail()
  }
  return { metadata, items }
}
export function decodeEventPage(bytes: Uint8Array, deviceId: string): DeviceEventPage {
  const { metadata, items } = pageFrames(bytes)
  closed(metadata, ['items', 'nextCursor', 'windowFrom', 'windowTo', 'retentionDays'])
  if (
    (metadata.nextCursor !== null &&
      (typeof metadata.nextCursor !== 'string' ||
        !/^[A-Za-z0-9_-]+$/.test(metadata.nextCursor) ||
        metadata.nextCursor.length > 8192)) ||
    typeof metadata.windowFrom !== 'string' ||
    typeof metadata.windowTo !== 'string' ||
    !isStrictJsonNumber(metadata.retentionDays!) ||
    metadata.retentionDays.lexical !== '90'
  )
    fail()
  return {
    items: items.map((item) => row(item, deviceId)),
    nextCursor: metadata.nextCursor,
    windowFrom: metadata.windowFrom,
    windowTo: metadata.windowTo,
    retentionDays: 90
  } as DeviceEventPage
}
export const emptyEventFilters = (): EventHistoryFilters => ({
  eventKey: '',
  level: '',
  thingModelVersionId: '',
  from: '',
  to: ''
})
export interface EventHistoryPort {
  list(
    project: string,
    device: string,
    filters: EventHistoryFilters,
    cursor?: string,
    signal?: AbortSignal
  ): Promise<DeviceEventPage>
  detail(
    project: string,
    device: string,
    message: string,
    signal?: AbortSignal
  ): Promise<DeviceEventItem>
}
export class EventHistoryModel {
  filters = emptyEventFilters()
  items: DeviceEventItem[] = []
  detail?: DeviceEventItem
  nextCursor?: string
  pageIndex = 0
  private cursors: (string | undefined)[] = [undefined]
  windowFrom = ''
  windowTo = ''
  loading = false
  detailLoading = false
  error = ''
  detailError = ''
  private project = ''
  private device = ''
  private active = false
  private generation = 0
  private listRun = 0
  private detailRun = 0
  private identity = -1
  private applied = emptyEventFilters()
  private controller?: AbortController
  private detailController?: AbortController
  constructor(
    private port: EventHistoryPort,
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
    this.listRun++
    this.detailRun++
    this.controller?.abort()
    this.detailController?.abort()
    this.active = false
    this.filters = emptyEventFilters()
    this.applied = emptyEventFilters()
    this.items = []
    this.detail = undefined
    this.nextCursor = undefined
    this.windowFrom = ''
    this.windowTo = ''
    this.pageIndex = 0
    this.cursors = [undefined]
    this.error = ''
    this.detailError = ''
    this.loading = false
    this.detailLoading = false
  }
  async refresh() {
    this.applied = { ...this.filters }
    this.cursors = [undefined]
    await this.load(0)
  }
  async next() {
    if (!this.nextCursor || this.loading || this.error) return
    this.cursors[this.pageIndex + 1] = this.nextCursor
    await this.load(this.pageIndex + 1)
  }
  private current(generation: number, identity: number) {
    return (
      this.active &&
      generation === this.generation &&
      identity === this.epoch() &&
      identity === this.identity
    )
  }
  async previous() {
    if (!this.loading && this.pageIndex > 0) await this.load(this.pageIndex - 1)
  }
  private async load(index: number) {
    if (!this.active) return
    this.pageIndex = index
    const cursor = this.cursors[index]
    const generation = this.generation,
      identity = this.epoch(),
      run = ++this.listRun
    this.controller?.abort()
    this.detailController?.abort()
    this.detailRun++
    this.controller = new AbortController()
    this.items = []
    this.detail = undefined
    this.detailError = ''
    this.detailLoading = false
    this.nextCursor = undefined
    this.windowFrom = ''
    this.windowTo = ''
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
      if (!this.current(generation, identity) || run !== this.listRun) return
      this.items = page.items
      this.nextCursor = page.nextCursor || undefined
      this.windowFrom = page.windowFrom ?? ''
      this.windowTo = page.windowTo ?? ''
    } catch {
      if (this.current(generation, identity) && run === this.listRun)
        this.error = '事件历史读取不可用，请检查筛选条件或刷新重试。'
    } finally {
      if (this.current(generation, identity) && run === this.listRun) this.loading = false
    }
  }
  async open(messageId: string) {
    if (!this.active) return
    const generation = this.generation,
      identity = this.epoch(),
      run = ++this.detailRun
    this.detailController?.abort()
    this.detailController = new AbortController()
    this.detail = undefined
    this.detailError = ''
    this.detailLoading = true
    try {
      const item = await this.port.detail(
        this.project,
        this.device,
        messageId,
        this.detailController.signal
      )
      if (this.current(generation, identity) && run === this.detailRun) this.detail = item
    } catch {
      if (this.current(generation, identity) && run === this.detailRun)
        this.detailError = '事件详情不可用，可能已离开当前可读窗口。'
    } finally {
      if (this.current(generation, identity) && run === this.detailRun) this.detailLoading = false
    }
  }
  clearDetail() {
    this.detailRun++
    this.detailController?.abort()
    this.detail = undefined
    this.detailError = ''
    this.detailLoading = false
  }
}
