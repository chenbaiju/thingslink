import {
  validateDashboardSchemaV1,
  isStrictJsonNumber,
  formatScalar,
  gaugePosition,
  parseCompositeValue,
  type RuntimeValue,
  type StrictJsonValue,
  type ScalarNumber,
  type ScalarProperty,
  type DashboardSchemaV1,
  type HistoryQuery,
  type HistoryResult,
  type AlarmQuery,
  type AlarmResult
} from '@things-link/client-contracts/dashboard/v1'
export type PreviewSelections = Readonly<Record<string, readonly string[]>>
export interface PreviewCell {
  text: string
  detail?: string
}
export interface PreviewHistorySeries extends PreviewCell {
  id: string
  label: string
  result?: HistoryResult
}
export interface PreviewRow {
  componentId: string
  title: string
  text: string
  detail?: string
  table?: {
    columns: readonly { id: string; label: string }[]
    rows: { deviceId: string; name: string; cells: PreviewCell[] }[]
    rowLimit: number
  }
  history?: { showLegend: boolean; series: PreviewHistorySeries[] }
  alarm?: { showClearedAt: boolean; query?: AlarmQuery; result?: AlarmResult; pageCursor?: string }
  selector?: { variableKey: string }
  gauge?: NonNullable<ReturnType<typeof gaugePosition>>
  composite?: {
    value: RuntimeValue
    mode: 'JSON' | 'LIST'
    initialExpandDepth: number
    rowLimit: number
  }
}
export interface PreviewDevice {
  deviceId: string
  expectedModelVersionId: string
  propertyKeys: string[]
}
export interface PreviewPorts {
  snapshots(body: {
    models: Omit<DashboardSchemaV1['models'][number], 'key'>[]
    devices: PreviewDevice[]
  }): Promise<unknown>
  current(body: { devices: PreviewDevice[] }): Promise<unknown>
  history?(query: HistoryQuery): Promise<HistoryResult>
  alarms?(query: AlarmQuery, componentId: string, cursor?: string): Promise<AlarmResult>
  now?(): number
  checkCurrent(): void
  beforeCurrentRead?(plan: DesignerCurrentPlan): Promise<void>
  afterCurrentInvalidation?(): Promise<void>
  invalidateDirectories?(): void
}
/** 当前值订阅与REST共用排序去重后的计划；私有数据不包含展示行对象。 */
export interface DesignerCurrentPlan {
  readonly devices: readonly PreviewDevice[]
}
export class DesignerCurrentRecoveryRequired extends Error {
  constructor() {
    super('设备不可用或模型变化，需要重新核验完整草稿')
    this.name = 'DesignerCurrentRecoveryRequired'
  }
}
type Component = DashboardSchemaV1['pages'][number]['components'][number]
interface CurrentTarget {
  component: Component
  deviceId: string
  propertyKey: string
  cellIndex?: number
}
interface CurrentPlanData {
  targets: CurrentTarget[]
  metadata: Map<string, Map<string, Property>>
}
const currentPlans = new WeakMap<DesignerCurrentPlan, CurrentPlanData>()
interface CurrentPatch {
  values: { target: CurrentTarget; value: PreviewCell & Pick<PreviewRow, 'gauge' | 'composite'> }[]
  invalidated: Map<string, string>
}
function prepareCurrentPlan(
  devices: PreviewDevice[],
  bindings: { component: Component; deviceId: string; propertyKey?: string; target: PreviewCell }[],
  metadata: Map<string, Map<string, Property>>,
  rows: Map<string, PreviewRow>
): DesignerCurrentPlan {
  const targets: CurrentTarget[] = bindings
    .filter(
      (binding) =>
        !!binding.propertyKey &&
        binding.component.kind !== 'LINE_CHART' &&
        devices.some((device) => device.deviceId === binding.deviceId)
    )
    .map((binding) => {
      let cellIndex: number | undefined
      if (binding.component.kind === 'TABLE' && binding.component.props.mode === 'DEVICE_VALUES') {
        cellIndex = rows
          .get(binding.component.id)
          ?.table?.rows.find((row) => row.deviceId === binding.deviceId)
          ?.cells.indexOf(binding.target)
        requireThat(cellIndex !== undefined && cellIndex >= 0)
      }
      return {
        component: binding.component,
        deviceId: binding.deviceId,
        propertyKey: binding.propertyKey!,
        ...(cellIndex === undefined ? {} : { cellIndex })
      }
    })
  const queries = devices
    .map((device) => ({
      deviceId: device.deviceId,
      expectedModelVersionId: device.expectedModelVersionId,
      propertyKeys: [
        ...new Set(
          targets
            .filter((target) => target.deviceId === device.deviceId)
            .map((target) => target.propertyKey)
        )
      ].sort()
    }))
    .filter((device) => device.propertyKeys.length)
    .sort((a, b) => a.deviceId.localeCompare(b.deviceId))
  const plan: DesignerCurrentPlan = Object.freeze({
    devices: Object.freeze(
      queries.map(
        (query) =>
          Object.freeze({
            ...query,
            propertyKeys: Object.freeze(query.propertyKeys)
          }) as unknown as PreviewDevice
      )
    )
  })
  currentPlans.set(plan, { targets, metadata })
  return plan
}
/** 整批校验后才返回补丁，既不调用网络也不修改当前展示行。 */
function decodeCurrentResponse(plan: DesignerCurrentPlan, current: unknown): CurrentPatch {
  const data = currentPlans.get(plan)
  requireThat(data)
  const patch: CurrentPatch = { values: [], invalidated: new Map() }
  object(current, ['devices'])
  requireThat(Array.isArray(current.devices) && current.devices.length === plan.devices.length)
  current.devices.forEach((raw, index) => {
    object(raw, ['deviceId', 'status', 'values'])
    const query = plan.devices[index]!
    requireThat(
      raw.deviceId === query.deviceId &&
        ['AVAILABLE', 'NOT_AVAILABLE', 'MODEL_MISMATCH'].includes(raw.status as string) &&
        Array.isArray(raw.values)
    )
    if (raw.status !== 'AVAILABLE') {
      requireThat(raw.values.length === 0)
      patch.invalidated.set(
        query.deviceId,
        raw.status === 'NOT_AVAILABLE' ? '设备不可用或无权访问' : '设备物模型已变更'
      )
      return
    }
    requireThat(raw.values.length === query.propertyKeys.length)
    raw.values.forEach((fact, position) => {
      requireThat(fact && typeof fact === 'object')
      const value = fact as Record<string, unknown>
      requireThat(value.propertyKey === query.propertyKeys[position])
      if (value.state !== 'VALUE') {
        object(value, ['propertyKey', 'state'])
        requireThat(typeof value.state === 'string' && Object.hasOwn(emptyStates, value.state))
      } else {
        object(value, ['propertyKey', 'state', 'value', 'occurredAt', 'reportedModelVersionId'])
        requireThat(
          value.reportedModelVersionId === query.expectedModelVersionId &&
            timestamp(value.occurredAt)
        )
      }
      for (const target of data.targets.filter(
        (target) => target.deviceId === query.deviceId && target.propertyKey === value.propertyKey
      )) {
        if (value.state !== 'VALUE') {
          patch.values.push({ target, value: { text: emptyStates[value.state as string]! } })
          continue
        }
        const meta = data.metadata.get(query.expectedModelVersionId)?.get(target.propertyKey)
        requireThat(meta)
        const component = target.component
        const rendered: PreviewCell & Pick<PreviewRow, 'gauge' | 'composite'> = {
          text: '',
          detail: `上报时间：${value.occurredAt as string}`
        }
        if (
          component.kind === 'JSON_VIEW' ||
          (component.kind === 'TABLE' && component.props.mode === 'LIST_VALUE')
        ) {
          requireThat(
            component.kind === 'JSON_VIEW'
              ? meta.dataType === 'OBJECT' || meta.dataType === 'LIST'
              : meta.dataType === 'LIST'
          )
          rendered.text = component.kind === 'TABLE' ? '完整列表快照' : '完整JSON快照'
          rendered.composite = {
            value: parseCompositeValue(value.value, meta.dataType as 'OBJECT' | 'LIST'),
            mode: component.kind === 'TABLE' ? 'LIST' : 'JSON',
            initialExpandDepth:
              component.kind === 'JSON_VIEW' ? component.props.initialExpandDepth : 1,
            rowLimit: component.kind === 'TABLE' ? component.props.rowLimit : 20
          }
        } else {
          requireThat(['NUMBER', 'TEXT', 'SWITCH', 'ENUM'].includes(meta.dataType))
          const scalar = meta.dataType === 'NUMBER' ? number(value.value) : value.value
          if (meta.dataType === 'NUMBER')
            requireThat(
              (meta.minimumValue === null ||
                compareNumbers(scalar as ScalarNumber, meta.minimumValue) >= 0) &&
                (meta.maximumValue === null ||
                  compareNumbers(scalar as ScalarNumber, meta.maximumValue) <= 0)
            )
          if (component.kind === 'GAUGE') {
            requireThat(meta.dataType === 'NUMBER')
            const config = component.props
            const position = gaugePosition(
              scalar as ScalarNumber,
              config.scaleMode === 'MODEL' ? meta.minimumValue : config.min,
              config.scaleMode === 'MODEL' ? meta.maximumValue : config.max
            )
            requireThat(position)
            rendered.gauge = position
          }
          const text = formatScalar(
            scalar,
            meta,
            'precision' in component.props ? component.props.precision : 2,
            'unitMode' in component.props ? component.props.unitMode : 'MODEL'
          )
          requireThat(text !== null)
          rendered.text = text
        }
        patch.values.push({ target, value: rendered })
      }
    })
  })
  return patch
}
function applyCurrentPatch(previous: readonly PreviewRow[], patch: CurrentPatch): PreviewRow[] {
  const changed = new Map<string, PreviewRow>()
  for (const { target, value } of patch.values) {
    let row = changed.get(target.component.id)
    if (!row) {
      const old = previous.find((row) => row.componentId === target.component.id)
      requireThat(old)
      row = {
        ...old,
        ...(old.table
          ? {
              table: {
                ...old.table,
                rows: old.table.rows.map((item) => ({
                  ...item,
                  cells: item.cells.map((cell) => ({ ...cell }))
                }))
              }
            }
          : {})
      }
      changed.set(row.componentId, row)
    }
    const cell =
      target.cellIndex === undefined
        ? row
        : (row.table?.rows.find((item) => item.deviceId === target.deviceId)?.cells[
            target.cellIndex
          ] as PreviewRow | undefined)
    requireThat(cell)
    delete cell.detail
    delete cell.gauge
    delete cell.composite
    Object.assign(cell, value)
  }
  return previous.map((row) => changed.get(row.componentId) ?? row)
}
/** dirty轮仅一次PG当前值请求；不重建历史UTC窗口或借假ports重跑完整加载。 */
export async function refreshDesignerCurrent(
  plan: DesignerCurrentPlan,
  previousRows: readonly PreviewRow[],
  ports: {
    current(body: { devices: readonly PreviewDevice[] }): Promise<unknown>
    checkCurrent(): void
  }
): Promise<PreviewRow[]> {
  requireThat(currentPlans.has(plan))
  ports.checkCurrent()
  if (!plan.devices.length) return [...previousRows]
  const response = await ports.current({ devices: plan.devices })
  ports.checkCurrent()
  const patch = decodeCurrentResponse(plan, response)
  if (patch.invalidated.size) throw new DesignerCurrentRecoveryRequired()
  const result = applyCurrentPatch(previousRows, patch)
  ports.checkCurrent()
  return result
}
function requireThat(value: unknown): asserts value {
  if (!value) throw new Error('草稿数据响应不符合合同，未展示本轮结果。')
}
function object(value: unknown, keys: string[]): asserts value is Record<string, unknown> {
  requireThat(
    value &&
      typeof value === 'object' &&
      !Array.isArray(value) &&
      Object.keys(value).sort().join(',') === [...keys].sort().join(',')
  )
}
function timestamp(value: unknown): value is string {
  if (
    typeof value !== 'string' ||
    !/^\d{4}-\d{2}-\d{2}T(?:[01]\d|2[0-3]):[0-5]\d:[0-5]\d(?:\.\d{1,9})?Z$/.test(value)
  )
    return false
  const date = new Date(value)
  return Number.isFinite(date.getTime()) && date.toISOString().slice(0, 10) === value.slice(0, 10)
}
function number(value: unknown): ScalarNumber {
  requireThat(isStrictJsonNumber(value as StrictJsonValue))
  const lexical = (value as { lexical: string }).lexical
  requireThat(Number.isFinite(Number(lexical)))
  return { kind: 'NUMBER', lexical }
}
function decimalParts(lexical: string) {
  const [coefficient = '', exponent = '0'] = lexical.toLowerCase().split('e')
  const negative = coefficient.startsWith('-')
  const unsigned = negative ? coefficient.slice(1) : coefficient
  const point = unsigned.indexOf('.')
  const scale = point < 0 ? 0 : unsigned.length - point - 1
  const digits = unsigned.replace('.', '').replace(/^0+/, '') || '0'
  return {
    negative: negative && digits !== '0',
    digits,
    magnitude: digits === '0' ? null : BigInt(digits.length - scale) + BigInt(exponent)
  }
}
function compareNumbers(left: ScalarNumber, right: ScalarNumber) {
  const a = decimalParts(left.lexical),
    b = decimalParts(right.lexical)
  if (a.negative !== b.negative) return a.negative ? -1 : 1
  const direction = a.negative ? -1 : 1
  if (a.magnitude !== b.magnitude)
    return (
      (a.magnitude === null ? -1 : b.magnitude === null ? 1 : a.magnitude < b.magnitude ? -1 : 1) *
      direction
    )
  const length = Math.max(a.digits.length, b.digits.length)
  const x = a.digits.padEnd(length, '0'),
    y = b.digits.padEnd(length, '0')
  return (x < y ? -1 : x > y ? 1 : 0) * direction
}
interface Property extends ScalarProperty {
  propertyKey: string
  minimumValue: ScalarNumber | null
  maximumValue: ScalarNumber | null
}
function property(value: unknown): Property {
  object(value, [
    'propertyKey',
    'dataType',
    'unit',
    'minimumValue',
    'maximumValue',
    'enumOptions',
    'onLabel',
    'offLabel'
  ])
  requireThat(
    typeof value.propertyKey === 'string' &&
      /^[A-Za-z0-9_-]{1,64}$/.test(value.propertyKey) &&
      ['NUMBER', 'TEXT', 'SWITCH', 'ENUM', 'OBJECT', 'LIST'].includes(value.dataType as string)
  )
  for (const key of ['unit', 'onLabel', 'offLabel'])
    requireThat(value[key] === null || typeof value[key] === 'string')
  if (value.dataType === 'ENUM')
    requireThat(
      Array.isArray(value.enumOptions) &&
        value.enumOptions.length > 0 &&
        value.enumOptions.every((entry) => typeof entry === 'string') &&
        new Set(value.enumOptions).size === value.enumOptions.length
    )
  else requireThat(value.enumOptions === null)
  return {
    ...value,
    minimumValue: value.minimumValue === null ? null : number(value.minimumValue),
    maximumValue: value.maximumValue === null ? null : number(value.maximumValue)
  } as unknown as Property
}
const emptyStates: Record<string, string> = {
  NO_VALUE: '暂无采集值',
  SOURCE_VERSION_UNKNOWN: '上报模型版本未知',
  SOURCE_MODEL_MISMATCH: '上报模型不匹配',
  CONTRACT_MISMATCH: '上报值不符合合同'
}
/** 默认值与当前内存选择分离；读取仅覆盖当前页并在服务端重新确认身份。 */
export async function loadDesignerDevicePreview(
  input: DashboardSchemaV1,
  pageId: string,
  ports: PreviewPorts,
  selections: PreviewSelections = {},
  timeSelections: Readonly<Record<string, string>> = {}
): Promise<PreviewRow[]> {
  ports.checkCurrent()
  const schema = validateDashboardSchemaV1(new TextEncoder().encode(JSON.stringify(input))).schema
  const page = schema.pages.find((entry) => entry.id === pageId)
  requireThat(page)
  const devices = new Map<string, PreviewDevice>()
  const models = new Map<string, Omit<DashboardSchemaV1['models'][number], 'key'>>()
  const rows = new Map<string, PreviewRow>()
  const selectedDevices = new Set<string>()
  const roundTo = new Date(ports.now?.() ?? Date.now()).toISOString()
  const histories = new Map<string, { query: HistoryQuery; targets: PreviewHistorySeries[] }>()
  const alarms = new Map<string, { query: AlarmQuery; targets: PreviewRow[] }>()
  type Component = DashboardSchemaV1['pages'][number]['components'][number]
  const bindings: {
    component: Component
    deviceId: string
    propertyKey?: string
    target: PreviewCell
  }[] = []
  const tableNames = new Map<string, { deviceId: string; name: string; cells: PreviewCell[] }[]>()
  function select(variableKey: string) {
    const variable = schema.variables.find((entry) => entry.key === variableKey)
    requireThat(variable?.type === 'DEVICE_SINGLE' || variable?.type === 'DEVICE_MULTI')
    const model = schema.models.find((entry) => entry.key === variable.modelKey)
    requireThat(model)
    const ids = Object.hasOwn(selections, variableKey)
      ? selections[variableKey]!
      : variable.type === 'DEVICE_SINGLE'
        ? variable.defaultDeviceId
          ? [variable.defaultDeviceId]
          : []
        : variable.defaultDeviceIds
    requireThat(
      Array.isArray(ids) &&
        ids.length <= (variable.type === 'DEVICE_SINGLE' ? 1 : variable.maxItems) &&
        new Set(ids).size === ids.length &&
        ids.every((id) => /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(id))
    )
    for (const id of ids) selectedDevices.add(id)
    return { variable, model, ids }
  }
  function bind(
    component: Component,
    variableKey: string,
    id: string,
    target: PreviewCell,
    propertyKey?: string
  ) {
    const { model } = select(variableKey)
    const old = devices.get(id)
    requireThat(!old || old.expectedModelVersionId === model.versionId)
    const query = old ?? { deviceId: id, expectedModelVersionId: model.versionId, propertyKeys: [] }
    if (propertyKey && !query.propertyKeys.includes(propertyKey))
      query.propertyKeys.push(propertyKey)
    requireThat(query.propertyKeys.length <= 50)
    devices.set(id, query)
    models.set(model.versionId, {
      versionId: model.versionId,
      digestAlgorithm: model.digestAlgorithm,
      digest: model.digest,
      profile: model.profile
    })
    bindings.push({ component, deviceId: id, propertyKey, target })
  }
  for (const component of page.components) {
    if (component.kind === 'TEXT' || component.kind === 'IMAGE') continue
    const row: PreviewRow = {
      componentId: component.id,
      title: component.props.title ?? component.id,
      text: '未选择设备'
    }
    rows.set(component.id, row)
    if (component.kind === 'ALARM_LIST') {
      const slot = component.bindings.alarms
      const { variable, model, ids } = select(slot.devices.variableKey)
      row.text = variable.required ? '请选择设备' : '未选择设备'
      row.alarm = { showClearedAt: component.props.showClearedAt }
      if (!ids.length) continue
      const queried = [...ids]
        .sort()
        .map((deviceId) => ({ deviceId, expectedModelVersionId: model.versionId }))
      const conditionStates = [...slot.conditionStates].sort(),
        ackStates = [...slot.ackStates].sort(),
        severities = [...slot.severities].sort()
      const queryId = JSON.stringify([
        queried,
        conditionStates,
        ackStates,
        severities,
        component.props.pageSize
      ])
      const entry = alarms.get(queryId) ?? {
        query: {
          queryId,
          devices: queried,
          conditionStates,
          ackStates,
          severities,
          limit: component.props.pageSize
        },
        targets: [] as PreviewRow[]
      }
      entry.targets.push(row)
      row.alarm.query = entry.query
      alarms.set(queryId, entry)
      for (const id of ids) bind(component, variable.key, id, row)
      continue
    }
    if (component.kind === 'LINE_CHART') {
      row.text = '版本化历史快照'
      row.history = { showLegend: component.props.showLegend, series: [] }
      for (const series of component.bindings.series) {
        const declaration = component.props.series.find((item) => item.id === series.id)!
        const target: PreviewHistorySeries = {
          id: series.id,
          label: declaration.label,
          text: '请选择设备'
        }
        row.history.series.push(target)
        const { variable, model, ids } = select(series.value.device.variableKey)
        requireThat(variable.type === 'DEVICE_SINGLE')
        target.text = variable.required ? '请选择设备' : '未选择设备'
        const time = schema.variables.find((item) => item.key === series.value.timeRangeVariableKey)
        requireThat(time?.type === 'TIME_RANGE')
        const preset = Object.hasOwn(timeSelections, time.key)
          ? timeSelections[time.key]!
          : time.defaultPreset
        requireThat(time.allowedPresets.includes(preset as typeof time.defaultPreset))
        if (!ids[0]) continue
        bind(component, variable.key, ids[0], target, series.value.propertyKey)
        const durations: Record<string, number> = {
          LAST_1_HOUR: 3600000,
          LAST_24_HOURS: 86400000,
          LAST_7_DAYS: 604800000
        }
        const from = new Date(Date.parse(roundTo) - durations[preset]!).toISOString()
        const queryId = JSON.stringify([
          ids[0],
          model.versionId,
          series.value.propertyKey,
          from,
          roundTo,
          series.value.granularity,
          series.value.aggregation
        ])
        const entry = histories.get(queryId) ?? {
          query: {
            queryId,
            deviceId: ids[0],
            expectedModelVersionId: model.versionId,
            propertyKey: series.value.propertyKey,
            from,
            to: roundTo,
            granularity: series.value.granularity,
            aggregation: series.value.aggregation
          },
          targets: [] as PreviewHistorySeries[]
        }
        entry.targets.push(target)
        histories.set(queryId, entry)
      }
      continue
    }
    if (component.kind === 'DEVICE_SELECTOR') {
      const variableKey = component.bindings.directory.variableKey
      select(variableKey)
      row.selector = { variableKey }
      row.text = '当前预览选择不改变草稿默认值'
      continue
    }
    if (
      component.kind === 'TABLE' &&
      component.props.mode === 'DEVICE_VALUES' &&
      'columns' in component.bindings
    ) {
      const columns = component.bindings.columns
      const variableKey = columns[0]!.value.device.variableKey
      const { variable, ids } = select(variableKey)
      requireThat(
        variable.type === 'DEVICE_MULTI' &&
          columns.every((column) => column.value.device.variableKey === variableKey)
      )
      row.text = ids.length ? '完整选中设备快照' : variable.required ? '请选择设备' : '未选择设备'
      row.table = { columns: component.props.columns, rows: [], rowLimit: component.props.rowLimit }
      for (const id of ids) {
        const tableRow = {
          deviceId: id,
          name: id,
          cells: columns.map(() => ({ text: '暂无值' }) as PreviewCell)
        }
        row.table.rows.push(tableRow)
        tableNames.set(id, [...(tableNames.get(id) ?? []), tableRow])
        columns.forEach((column, index) =>
          bind(component, variableKey, id, tableRow.cells[index]!, column.value.propertyKey)
        )
      }
      continue
    }
    requireThat(
      component.kind === 'STATUS' ||
        component.kind === 'VALUE_CARD' ||
        component.kind === 'GAUGE' ||
        component.kind === 'JSON_VIEW' ||
        (component.kind === 'TABLE' && component.props.mode === 'LIST_VALUE')
    )
    const slot =
      component.kind === 'STATUS'
        ? component.bindings.status
        : 'value' in component.bindings
          ? component.bindings.value
          : undefined
    requireThat(slot)
    const { variable, ids } = select(slot.device.variableKey)
    requireThat(variable.type === 'DEVICE_SINGLE')
    row.text = variable.required ? '请选择设备' : '未选择设备'
    if (ids[0])
      bind(
        component,
        variable.key,
        ids[0],
        row,
        'propertyKey' in slot ? slot.propertyKey : undefined
      )
  }
  if (
    histories.size > 10 ||
    histories.size +
      alarms.size +
      (devices.size ? 1 : 0) +
      (bindings.some((binding) => binding.propertyKey && binding.component.kind !== 'LINE_CHART')
        ? 1
        : 0) >
      20 ||
    selectedDevices.size > 20 ||
    [...devices.values()].reduce((sum, query) => sum + query.propertyKeys.length, 0) > 200
  )
    throw new Error('当前页设备或属性数量超过预览上限，请减少选择。')
  if (!devices.size) {
    await ports.beforeCurrentRead?.(prepareCurrentPlan([], bindings, new Map(), rows))
    ports.checkCurrent()
    return [...rows.values()]
  }
  const queries = [...devices.values()]
  const response = await ports.snapshots({ models: [...models.values()], devices: queries })
  ports.checkCurrent()
  object(response, ['devices', 'models'])
  requireThat(
    Array.isArray(response.devices) &&
      response.devices.length === queries.length &&
      Array.isArray(response.models)
  )
  const available: PreviewDevice[] = []
  const invalidated = new Set<string>()
  response.devices.forEach((value, index) => {
    requireThat(value && typeof value === 'object')
    const entry = value as Record<string, unknown>,
      query = queries[index]!
    requireThat(entry.deviceId === query.deviceId)
    let text: string
    if (entry.status === 'NOT_AVAILABLE') {
      ports.invalidateDirectories?.()
      object(entry, ['deviceId', 'status'])
      text = '设备不可用或无权访问'
    } else if (entry.status === 'MODEL_MISMATCH') {
      ports.invalidateDirectories?.()
      object(entry, ['deviceId', 'status', 'currentModelVersionId'])
      requireThat(
        entry.currentModelVersionId === null ||
          (typeof entry.currentModelVersionId === 'string' &&
            /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(entry.currentModelVersionId))
      )
      requireThat(entry.currentModelVersionId !== query.expectedModelVersionId)
      text = '设备物模型已变更'
    } else {
      object(entry, [
        'deviceId',
        'status',
        'name',
        'deviceStatus',
        'lastOnlineAt',
        'currentModelVersionId'
      ])
      requireThat(
        entry.status === 'AVAILABLE' &&
          typeof entry.name === 'string' &&
          ['INACTIVE', 'ONLINE', 'OFFLINE'].includes(entry.deviceStatus as string) &&
          (entry.lastOnlineAt === null || timestamp(entry.lastOnlineAt)) &&
          entry.currentModelVersionId === query.expectedModelVersionId
      )
      available.push(query)
      text = ({ INACTIVE: '未激活', ONLINE: '在线', OFFLINE: '离线' } as Record<string, string>)[
        entry.deviceStatus as string
      ]!
    }
    for (const item of tableNames.get(query.deviceId) ?? [])
      item.name = entry.status === 'AVAILABLE' ? (entry.name as string) : '设备不可用'
    for (const binding of bindings.filter((entry) => entry.deviceId === query.deviceId)) {
      const row = binding.target as PreviewRow
      row.text =
        binding.component.kind === 'STATUS' || entry.status !== 'AVAILABLE' ? text : '暂无值'
      if (
        binding.component.kind === 'STATUS' &&
        binding.component.props.showLastOnlineAt &&
        typeof entry.lastOnlineAt === 'string'
      )
        row.detail = `最后在线：${entry.lastOnlineAt}`
    }
  })
  const expectedModels = new Set(available.map((device) => device.expectedModelVersionId))
  requireThat(response.models.length === expectedModels.size)
  const metadata = new Map<string, Map<string, Property>>()
  for (const value of response.models) {
    object(value, ['versionId', 'digestAlgorithm', 'digest', 'profile', 'properties'])
    requireThat(
      typeof value.versionId === 'string' &&
        expectedModels.has(value.versionId) &&
        !metadata.has(value.versionId)
    )
    const expected = models.get(value.versionId)!
    requireThat(
      value.digest === expected.digest &&
        value.digestAlgorithm === expected.digestAlgorithm &&
        value.profile === expected.profile
    )
    const keys = new Set(
      available
        .filter((device) => device.expectedModelVersionId === value.versionId)
        .flatMap((device) => device.propertyKeys)
    )
    requireThat(Array.isArray(value.properties) && value.properties.length === keys.size)
    const properties = new Map<string, Property>()
    for (const raw of value.properties) {
      const item = property(raw)
      requireThat(keys.has(item.propertyKey) && !properties.has(item.propertyKey))
      properties.set(item.propertyKey, item)
    }
    metadata.set(value.versionId, properties)
  }
  const currentPlan = prepareCurrentPlan(available, bindings, metadata, rows)
  await ports.beforeCurrentRead?.(currentPlan)
  ports.checkCurrent()
  if (currentPlan.devices.length) {
    const response = await ports.current({ devices: [...currentPlan.devices] })
    ports.checkCurrent()
    const patch = decodeCurrentResponse(currentPlan, response)
    if (patch.invalidated.size) {
      await ports.afterCurrentInvalidation?.()
      ports.checkCurrent()
    }
    for (const [deviceId, text] of patch.invalidated) {
      invalidated.add(deviceId)
      ports.invalidateDirectories?.()
      for (const item of tableNames.get(deviceId) ?? []) item.name = '设备不可用'
      for (const binding of bindings.filter((entry) => entry.deviceId === deviceId)) {
        const target = binding.target as PreviewRow
        target.text = text
        delete target.detail
        delete target.gauge
        delete target.composite
      }
    }
    for (const row of applyCurrentPatch([...rows.values()], patch)) rows.set(row.componentId, row)
  }
  let totalPoints = 0
  for (const { query, targets } of histories.values()) {
    ports.checkCurrent()
    if (
      invalidated.has(query.deviceId) ||
      !available.some((device) => device.deviceId === query.deviceId)
    )
      continue
    const meta = metadata.get(query.expectedModelVersionId)!.get(query.propertyKey)!
    if (meta.dataType !== 'NUMBER') {
      for (const target of targets) target.text = '历史属性不是数值类型'
      continue
    }
    requireThat(ports.history)
    const result = await ports.history(query)
    ports.checkCurrent()
    totalPoints += result.points.length
    requireThat(totalPoints <= 20000 && result.queryId === query.queryId)
    for (const target of targets) {
      target.result = result
      target.text =
        result.status === 'READY'
          ? result.points.length
            ? '历史读取完成'
            : '当前时间范围没有数据'
          : result.status === 'NON_NUMERIC'
            ? '历史包含非数值数据，无法绘制完整序列'
            : '历史配置或查询预算不符合要求'
    }
  }
  for (const { query, targets } of alarms.values()) {
    ports.checkCurrent()
    if (
      query.devices.some(
        (device) =>
          invalidated.has(device.deviceId) ||
          !available.some((item) => item.deviceId === device.deviceId)
      )
    ) {
      for (const target of targets) {
        target.text = '设备不可用或模型已变化'
        delete target.alarm!.query
      }
      continue
    }
    requireThat(ports.alarms)
    const result = await ports.alarms(query, targets[0]!.componentId)
    ports.checkCurrent()
    requireThat(result.queryId === query.queryId)
    for (const target of targets) {
      target.alarm!.result = { ...result, componentId: target.componentId }
      target.text =
        result.status === 'READY'
          ? result.items.length
            ? '当前页告警事实'
            : '当前筛选没有告警'
          : '告警配置、设备模型或游标已失效'
    }
  }
  ports.checkCurrent()
  return [...rows.values()]
}
