/** Q6 测试专用事实合同；不改变页面或 API 的历史刷新语义。 */
export interface ReportFact {
  projectId: string
  deviceId: string
  propertyKey: string
  occurredAt: string
  value: number
  messageId: string
  createdAt: string
  sourceLog: Record<string, unknown>
}

export type ReportIdentity = Pick<
  ReportFact,
  'projectId' | 'deviceId' | 'propertyKey' | 'createdAt'
>
export interface MessageObservation {
  url: string
  method: string
  status: number
  body: unknown
}

export interface HistoryObservation {
  url: string
  method: string
  requestSequence: number
  afterSequence: number
  status: number
  body: unknown
}

/** 保留 Instant 的纳秒精度；Date.parse 截断微秒会错误接纳排他 to 边界。 */
export function instantNanos(value: string): bigint {
  const match = /^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d{1,9}))?Z$/.exec(value)
  if (!match) throw new Error('无效 UTC 时点')
  const millis = Date.parse(`${match[1]}Z`)
  if (!Number.isFinite(millis) || new Date(millis).toISOString().slice(0, 19) !== match[1]) {
    throw new Error('无效 UTC 日期')
  }
  return BigInt(millis) * 1_000_000n + BigInt((match[2] ?? '').padEnd(9, '0'))
}

function object(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('响应不是对象')
  return value as Record<string, unknown>
}

/** 日志和历史均来自 PostgreSQL 微秒事实；不得接受热缓存或未持久化事件的不同精度。 */
function persistedMessage(identity: ReportIdentity, row: Record<string, unknown>) {
  const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
  if (
    typeof row.id !== 'string' ||
    !uuid.test(row.id) ||
    typeof row.messageId !== 'string' ||
    !uuid.test(row.messageId) ||
    row.deviceId !== identity.deviceId ||
    row.direction !== 'UP' ||
    row.protocol !== 'MQTT' ||
    row.errorCode !== null ||
    typeof row.ts !== 'string' ||
    typeof row.payloadSummary !== 'string'
  ) {
    throw new Error('消息标识、设备、方向或错误状态不匹配')
  }
  const time = instantNanos(row.ts)
  if (time % 1000n !== 0n || time < instantNanos(identity.createdAt))
    throw new Error('消息不是本轮设备的持久化微秒事实')
  // 两个旅程均冻结 propertiesPerReport=1；截断或非完整 JSON 不得当成可信载荷。
  const payload = object(JSON.parse(row.payloadSummary))
  const value = payload[identity.propertyKey]
  if (Object.keys(payload).length !== 1 || typeof value !== 'number' || !Number.isFinite(value))
    throw new Error('消息缺少唯一目标数值属性')
  return { messageId: row.messageId, occurredAt: row.ts, value }
}

/** 从本轮新建唯一设备的完整 UP/MQTT 日志页中，确定性选取最早的已提交属性消息。 */
export function reportFromMessageLogs(
  identity: ReportIdentity,
  observation: MessageObservation
): ReportFact {
  const url = new URL(observation.url)
  const keys = ['direction', 'protocol', 'from', 'to', 'limit']
  if (
    observation.method !== 'GET' ||
    observation.status !== 200 ||
    url.pathname !==
      `/api/v1/projects/${identity.projectId}/devices/${identity.deviceId}/messages` ||
    [...url.searchParams.keys()].length !== keys.length ||
    keys.some((key) => url.searchParams.getAll(key).length !== 1) ||
    url.searchParams.get('direction') !== 'UP' ||
    url.searchParams.get('protocol') !== 'MQTT' ||
    url.searchParams.get('from') !== identity.createdAt ||
    url.searchParams.get('limit') !== '200'
  )
    throw new Error('消息请求不是本轮精确项目设备范围')
  const to = instantNanos(url.searchParams.get('to')!)
  const body = object(observation.body)
  if (
    !Array.isArray(body.items) ||
    body.items.length === 0 ||
    body.hasMore !== false ||
    body.nextCursor !== null
  )
    throw new Error('消息页为空、不完整或未知')
  const ids = new Set<string>()
  const rows = body.items.map(object).map((sourceLog) => {
    const fact = persistedMessage(identity, sourceLog)
    if (ids.has(fact.messageId)) throw new Error('重复 messageId')
    ids.add(fact.messageId)
    if (!(instantNanos(fact.occurredAt) < to)) throw new Error('消息超出查询排他窗口')
    return { ...identity, ...fact, sourceLog: Object.freeze({ ...sourceLog }) }
  })
  rows.sort((a, b) => {
    const delta = instantNanos(a.occurredAt) - instantNanos(b.occurredAt)
    return delta < 0n ? -1 : delta > 0n ? 1 : a.messageId.localeCompare(b.messageId)
  })
  return rows[0]
}

/** 同步必须先取得真实上报，再调用产生新请求的 UI 动作；不得并行领取首次响应。 */
export async function synchronizeHistory(
  getReport: () => Promise<ReportFact>,
  requestFreshHistory: (report: ReportFact) => Promise<HistoryObservation>
): Promise<ReportFact> {
  const report = await getReport()
  assertHistoryContainsReport(report, await requestFreshHistory(report))
  return report
}

/** 只接受新请求、精确路径/查询、[from,to) 与目标原始点；任一缺失都失败关闭。 */
export function assertHistoryContainsReport(report: ReportFact, observation: HistoryObservation) {
  const source = persistedMessage(report, report.sourceLog)
  if (
    source.messageId !== report.messageId ||
    source.occurredAt !== report.occurredAt ||
    source.value !== report.value
  )
    throw new Error('目标消息绑定被更改')
  if (
    !Number.isSafeInteger(observation.requestSequence) ||
    !Number.isSafeInteger(observation.afterSequence) ||
    observation.afterSequence < 0 ||
    observation.requestSequence <= observation.afterSequence
  )
    throw new Error('旧历史请求不得复用')
  const url = new URL(observation.url)
  const path = `/api/v1/projects/${report.projectId}/devices/${report.deviceId}/telemetry/property/history`
  if (url.pathname !== path || observation.method !== 'GET' || observation.status !== 200) {
    throw new Error('历史响应路径、方法或状态不匹配')
  }
  const keys = ['propertyKey', 'from', 'to', 'granularity', 'aggregation']
  if (
    [...url.searchParams.keys()].length !== keys.length ||
    keys.some((key) => url.searchParams.getAll(key).length !== 1) ||
    url.searchParams.get('propertyKey') !== report.propertyKey ||
    url.searchParams.get('granularity') !== 'RAW' ||
    url.searchParams.get('aggregation') !== 'AVG'
  )
    throw new Error('历史查询参数不匹配')
  const from = instantNanos(url.searchParams.get('from')!)
  const to = instantNanos(url.searchParams.get('to')!)
  const target = instantNanos(report.occurredAt)
  if (!(from <= target && target < to)) throw new Error('历史窗口不包含目标时点')
  const body = object(observation.body)
  if (
    body.actualGranularity !== 'RAW' ||
    body.requestedGranularity !== 'RAW' ||
    body.aggregation !== 'AVG'
  ) {
    throw new Error('历史响应不是原始点')
  }
  if (!Array.isArray(body.points) || body.points.length === 0) throw new Error('历史必须非空')
  const matching = body.points
    .map(object)
    .filter(
      (point) =>
        typeof point.ts === 'string' &&
        instantNanos(point.ts) === target &&
        point.value === report.value
    )
  if (matching.length !== 1 || matching[0].sampleCount !== 1)
    throw new Error('历史缺少唯一目标原始点')
}
