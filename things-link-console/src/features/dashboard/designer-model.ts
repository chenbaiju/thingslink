import {
  validateDashboardSchemaV1,
  parseDashboardRuntimeResponse,
  createRuntimeNumber,
  gaugePosition,
  type RuntimeNumber,
  type DashboardModelReference,
  type DashboardSingleDeviceVariable,
  type DashboardMultiDeviceVariable,
  type DashboardTimeRangeVariable,
  type DashboardTextEnumVariable,
  type DashboardHistoryGranularity,
  type DashboardHistoryAggregation,
  type DashboardSchemaV1
} from '@things-link/client-contracts/dashboard/v1'
export const BUILTIN_IMAGE = Object.freeze({
  resourceId: 'device_mark',
  resourceDigest: '5977f591d5eeb691ee85c7656468a8b5dd1378b70b02a55af574331a0f0f0eaa'
})
/** 版本摘要只接受服务端元数据；浏览器不计算或猜测发布身份。 */
export interface DeviceComponentInput {
  kind: 'VALUE_CARD' | 'STATUS' | 'GAUGE' | 'JSON_VIEW' | 'TABLE'
  title: string
  deviceId: string
  model: Omit<DashboardModelReference, 'key'>
  variableKey?: string
  propertyKey?: string
  propertyMetadata?: DeviceBindingProperty
  componentProps?: Record<string, unknown>
}
export interface DeviceVariableInput {
  key?: string
  type: 'DEVICE_SINGLE' | 'DEVICE_MULTI'
  title: string
  required: boolean
  model: Omit<DashboardModelReference, 'key'>
  defaultDeviceIds: string[]
  maxItems?: number
}
export interface VariableComponentInput {
  kind: 'DEVICE_SELECTOR' | 'TABLE'
  variableKey: string
  title: string
  placeholder?: string
  pageSize?: number
  rowLimit?: number
  columns?: { id?: string; label: string; propertyKey: string }[]
  model?: DeviceComponentInput['model']
  properties?: readonly DeviceBindingProperty[]
}
export function deviceVariableReferences(schema: DashboardSchemaV1, key: string): string[] {
  return schema.pages.flatMap((page) =>
    page.components
      .filter((component) => {
        const visit = (value: unknown): boolean =>
          !!value &&
          typeof value === 'object' &&
          Object.entries(value).some(
            ([name, child]) =>
              ((name === 'variableKey' || name === 'timeRangeVariableKey') && child === key) ||
              visit(child)
          )
        return visit(component.bindings)
      })
      .map((component) => `${page.title} / ${component.id}`)
  )
}
export type TimePreset = DashboardTimeRangeVariable['defaultPreset']
export interface TimeRangeInput {
  key?: string
  title: string
  required: boolean
  defaultPreset: TimePreset
  allowedPresets: TimePreset[]
}
export interface HistoryComponentInput {
  title: string
  showLegend: boolean
  series: {
    id?: string
    label: string
    deviceVariableKey: string
    timeRangeVariableKey: string
    propertyKey: string
    granularity: DashboardHistoryGranularity
    aggregation: DashboardHistoryAggregation
    model: DeviceComponentInput['model']
    propertyMetadata: DeviceBindingProperty
  }[]
}
export interface AlarmComponentInput {
  title: string
  deviceVariableKey: string
  conditionStates: ('PENDING' | 'ACTIVE' | 'CLEARED')[]
  ackStates: ('UNACKNOWLEDGED' | 'ACKNOWLEDGED')[]
  severities: ('CRITICAL' | 'MAJOR' | 'MINOR' | 'WARNING' | 'INFO')[]
  pageSize: number
  showClearedAt: boolean
}
export interface TextEnumInput {
  key?: string
  title: string
  required: boolean
  options: { value: string; label: string }[]
  defaultValue?: string
}
export interface TextComponentInput {
  mode: 'STATIC' | 'DYNAMIC'
  content?: string
  variableKey?: string
  align: 'LEFT' | 'CENTER' | 'RIGHT'
  size: 'SMALL' | 'MEDIUM' | 'LARGE'
  tone: 'REGULAR' | 'SECONDARY' | 'PRIMARY'
}
export interface DeviceBindingProperty {
  key: string
  name: string
  dataType: string
  minimumValue?: RuntimeNumber | null
  maximumValue?: RuntimeNumber | null
}
/** 文本先按精确数字解析并验证ConfigNumber，不能先Number舍入再声称原输入合法。 */
export function parseGaugeBounds(
  minimum: string,
  maximum: string
): { min: number; max: number } | null {
  try {
    const parsed = parseDashboardRuntimeResponse(
      new TextEncoder().encode(`{"min":${minimum},"max":${maximum}}`)
    )
    if (Object.keys(parsed).sort().join(',') !== 'max,min') return null
    const min = createRuntimeNumber(parsed.min),
      max = createRuntimeNumber(parsed.max)
    if (!gaugePosition(min, min, max)) return null
    return { min: Number(min.lexical), max: Number(max.lexical) }
  } catch {
    return null
  }
}
export interface DraftSnapshot {
  dashboardId?: string
  revision?: string
  content?: unknown
}
export interface EditorSnapshot {
  dashboardId: string | null
  schema: DashboardSchemaV1 | null
  activePageId: string
  selectedId: string | null
  revision: string
  saving: boolean
  dirty: boolean
  conflict: boolean
  error: string
  readonly: boolean
  canUndo: boolean
  canRedo: boolean
}
export interface EditorPorts {
  save(id: string, revision: string, content: DashboardSchemaV1): Promise<DraftSnapshot>
  canEdit(): boolean
  now(): number
  available(): boolean
  setTimer(callback: () => void, delay: number): unknown
  clearTimer(timer: unknown): void
  changed(snapshot: EditorSnapshot): void
}
const encoder = new TextEncoder()
function normalized(value: unknown): DashboardSchemaV1 {
  return validateDashboardSchemaV1(encoder.encode(JSON.stringify(value))).schema
}
export function emptyDashboard(): DashboardSchemaV1 {
  return normalized({
    schemaVersion: 'tc.dashboard/v1',
    presentation: { mode: 'RESPONSIVE_GRID' },
    pages: [{ id: 'main', title: '首页', components: [] }]
  })
}
/** JSON对象键无序、数组顺序有义；仅比较合同结构，不模拟数据库摘要。 */
function structurallyEqual(left: unknown, right: unknown): boolean {
  if (left === right) return true
  if (left === null || right === null || typeof left !== 'object' || typeof right !== 'object')
    return false
  if (Array.isArray(left) || Array.isArray(right))
    return (
      Array.isArray(left) &&
      Array.isArray(right) &&
      left.length === right.length &&
      left.every((value, index) => structurallyEqual(value, right[index]))
    )
  const a = left as Record<string, unknown>
  const b = right as Record<string, unknown>
  const keys = Object.keys(a)
  return (
    keys.length === Object.keys(b).length &&
    keys.every(
      (key) => Object.prototype.hasOwnProperty.call(b, key) && structurallyEqual(a[key], b[key])
    )
  )
}
function editable(schema: DashboardSchemaV1): boolean {
  return (
    schema.variables.every(
      (variable) =>
        variable.type === 'DEVICE_SINGLE' ||
        variable.type === 'DEVICE_MULTI' ||
        variable.type === 'TIME_RANGE' ||
        variable.type === 'TEXT_ENUM'
    ) &&
    schema.pages.every((page) =>
      page.components.every(
        (component) =>
          (component.kind === 'IMAGE'
            ? Object.keys(component.bindings).length === 0
            : component.kind === 'TEXT' ||
              component.kind === 'VALUE_CARD' ||
              component.kind === 'STATUS' ||
              component.kind === 'GAUGE' ||
              component.kind === 'JSON_VIEW' ||
              component.kind === 'TABLE' ||
              component.kind === 'DEVICE_SELECTOR' ||
              component.kind === 'LINE_CHART' ||
              component.kind === 'ALARM_LIST') &&
          (component.kind !== 'IMAGE' ||
            (component.props.resourceId === BUILTIN_IMAGE.resourceId &&
              component.props.resourceDigest === BUILTIN_IMAGE.resourceDigest))
      )
    )
  )
}
type MutableDocument = {
  models: DashboardModelReference[]
  variables: (
    | DashboardSingleDeviceVariable
    | DashboardMultiDeviceVariable
    | DashboardTimeRangeVariable
    | DashboardTextEnumVariable
  )[]
  presentation: { mode: 'RESPONSIVE_GRID' | 'FIXED_SCREEN'; theme: 'LIGHT' | 'DARK' }
  pages: {
    id: string
    title: string
    components: {
      id: string
      kind: string
      componentVersion: string
      layout: { x: number; y: number; w: number; h: number }
      props: Record<string, unknown>
      bindings: Record<string, unknown>
    }[]
  }[]
}
function nextPlacement(draft: MutableDocument, page: MutableDocument['pages'][number]) {
  const ids = new Set(
    draft.pages.flatMap((entry) => entry.components.map((component) => component.id))
  )
  let index = 1
  while (ids.has(`component_${index}`)) index++
  const fixed = draft.presentation.mode === 'FIXED_SCREEN'
  const w = fixed ? 480 : 12
  const h = fixed ? 160 : 10
  const maxX = fixed ? 1920 : 24
  const maxY = fixed ? 1080 : 1000
  let layout: { x: number; y: number; w: number; h: number } | undefined
  for (let y = 0; !layout && y + h <= maxY; y += fixed ? 10 : 1)
    for (let x = 0; !layout && x + w <= maxX; x += fixed ? 10 : 1) {
      if (
        page.components.every(
          (component) =>
            x + w <= component.layout.x ||
            component.layout.x + component.layout.w <= x ||
            y + h <= component.layout.y ||
            component.layout.y + component.layout.h <= y
        )
      )
        layout = { x, y, w, h }
    }
  if (!layout) throw new Error('no room')
  return { id: `component_${index}`, layout }
}
function blank(): EditorSnapshot {
  return {
    dashboardId: null,
    schema: null,
    activePageId: '',
    selectedId: null,
    revision: '',
    saving: false,
    dirty: false,
    conflict: false,
    error: '',
    readonly: true,
    canUndo: false,
    canRedo: false
  }
}
/** 编辑文档是完整规范Schema；选中/历史/保存状态只驻留本会话内存。 */
export function createDashboardEditor(ports: EditorPorts) {
  let state = blank()
  let baseline = ''
  let generation = 0
  let timer: unknown
  let flight = false
  let stopped = false
  let firstDirty: number | null = null
  let lastEdit = 0
  let undo: string[] = []
  let redo: string[] = []
  const emit = (patch: Partial<EditorSnapshot> = {}) => {
    state = { ...state, ...patch, canUndo: undo.length > 0, canRedo: redo.length > 0 }
    ports.changed(state)
  }
  const serialized = () => JSON.stringify(state.schema)
  const cancelTimer = () => {
    ports.clearTimer(timer)
    timer = undefined
  }
  function trim() {
    while (
      undo.length + redo.length > 50 ||
      [...undo, ...redo].reduce((sum, value) => sum + encoder.encode(value).length, 0) >
        8 * 1024 * 1024
    ) {
      if (undo.length) undo.shift()
      else redo.shift()
    }
  }
  function schedule() {
    cancelTimer()
    if (
      !state.dirty ||
      stopped ||
      state.readonly ||
      !ports.canEdit() ||
      !ports.available() ||
      flight
    )
      return
    const deadline = Math.min(lastEdit + 1500, (firstDirty ?? lastEdit) + 10000)
    timer = ports.setTimer(
      () => {
        timer = undefined
        void save()
      },
      Math.max(0, deadline - ports.now())
    )
  }
  function changed(schema: DashboardSchemaV1) {
    const dirty = JSON.stringify(schema) !== baseline
    lastEdit = ports.now()
    if (dirty && firstDirty === null) firstDirty = lastEdit
    if (!dirty) firstDirty = null
    const activePageId = schema.pages.some((page) => page.id === state.activePageId)
      ? state.activePageId
      : schema.pages[0]!.id
    const selectedId = schema.pages
      .find((page) => page.id === activePageId)!
      .components.some((component) => component.id === state.selectedId)
      ? state.selectedId
      : null
    emit({ schema, activePageId, selectedId, dirty, ...(stopped ? {} : { error: '' }) })
    schedule()
  }
  function edit(action: (document: MutableDocument) => void) {
    if (!state.schema || state.readonly || !ports.canEdit() || !ports.available()) return
    try {
      const before = serialized()
      const draft = JSON.parse(before) as MutableDocument
      action(draft)
      const next = normalized(draft)
      if (JSON.stringify(next) === before) return
      undo.push(before)
      redo = []
      trim()
      changed(next)
    } catch {
      emit({ error: '修改不符合画布合同（布局重叠、边界或字段值不合法），未采用本次修改。' })
    }
  }
  async function save() {
    cancelTimer()
    if (
      flight ||
      !state.schema ||
      !state.dashboardId ||
      !state.dirty ||
      stopped ||
      state.readonly ||
      !ports.canEdit() ||
      !ports.available()
    )
      return
    const epoch = generation
    const id = state.dashboardId
    const revision = state.revision
    const snapshot = state.schema
    const sent = serialized()
    firstDirty = null
    flight = true
    emit({ saving: true })
    try {
      const result = await ports.save(id, revision, snapshot)
      if (generation !== epoch || state.dashboardId !== id) return
      if (
        result.dashboardId !== id ||
        result.revision !== String(BigInt(revision) + 1n) ||
        !structurallyEqual(normalized(result.content), snapshot)
      )
        throw new Error('invalid save receipt')
      baseline = sent
      firstDirty = serialized() === baseline ? null : (firstDirty ?? lastEdit)
      emit({ revision: result.revision, dirty: serialized() !== baseline, error: '' })
    } catch (error) {
      if (generation !== epoch) return
      stopped = true
      const value = error as { code?: number; status?: number; response?: { status?: number } }
      const conflict =
        value.code === 60037 ||
        value.code === 409 ||
        value.status === 409 ||
        value.response?.status === 409
      emit({
        conflict,
        error: conflict
          ? '草稿已被其他编辑更新；本地修改保留，请重新加载远端或人工重做。'
          : '保存未完成，自动保存已停止；请明确重试或重新加载。'
      })
    } finally {
      flight = false
      if (generation === epoch) emit({ saving: false })
      schedule()
    }
  }
  function addDeviceComponent(input: DeviceComponentInput, replaceSelected = false) {
    let createdId: string | undefined
    edit((draft) => {
      const page = draft.pages.find((entry) => entry.id === state.activePageId)!
      const selected = replaceSelected
        ? page.components.find((entry) => entry.id === state.selectedId)
        : undefined
      if (
        replaceSelected &&
        (!selected ||
          !['VALUE_CARD', 'STATUS', 'GAUGE', 'JSON_VIEW', 'TABLE'].includes(selected.kind))
      )
        throw new Error('unsupported rebind target')
      const placement = selected
        ? { id: selected.id, layout: selected.layout }
        : nextPlacement(draft, page)
      const keys = new Set([...draft.models, ...draft.variables].map((entry) => entry.key))
      const nextKey = (prefix: string) => {
        let index = 1
        while (keys.has(`${prefix}_${index}`)) index++
        const key = `${prefix}_${index}`
        keys.add(key)
        return key
      }
      let model = draft.models.find((entry) => entry.versionId === input.model.versionId)
      if (model) {
        if (
          model.digestAlgorithm !== input.model.digestAlgorithm ||
          model.digest !== input.model.digest ||
          model.profile !== input.model.profile
        )
          throw new Error('model identity mismatch')
      } else {
        model = { key: nextKey('model'), ...input.model }
        draft.models.push(model)
      }
      let variable = input.variableKey
        ? draft.variables.find((entry) => entry.key === input.variableKey)
        : draft.variables.find(
            (entry) =>
              entry.type === 'DEVICE_SINGLE' &&
              entry.modelKey === model.key &&
              entry.defaultDeviceId === input.deviceId
          )
      if (
        input.variableKey &&
        (!variable || variable.type !== 'DEVICE_SINGLE' || variable.modelKey !== model.key)
      )
        throw new Error('变量类型或模型不匹配')
      if (!variable) {
        variable = {
          key: nextKey('device'),
          type: 'DEVICE_SINGLE',
          title: input.title,
          required: true,
          modelKey: model.key,
          defaultDeviceId: input.deviceId
        }
        draft.variables.push(variable)
      }
      const device = { variableKey: variable.key }
      const defaults: Record<DeviceComponentInput['kind'], Record<string, unknown>> = {
        VALUE_CARD: { precision: 2, unitMode: 'MODEL' },
        STATUS: { showLastOnlineAt: true },
        GAUGE: { scaleMode: 'MODEL', precision: 2, unitMode: 'MODEL' },
        JSON_VIEW: { initialExpandDepth: 1 },
        TABLE: { mode: 'LIST_VALUE', rowLimit: 20 }
      }
      const properties: Record<string, unknown> = {
        ...(selected?.kind === input.kind &&
        !(input.kind === 'TABLE' && selected.props.mode !== 'LIST_VALUE')
          ? selected.props
          : defaults[input.kind]),
        ...input.componentProps,
        title: input.title
      }
      if (input.kind === 'GAUGE' || input.kind === 'JSON_VIEW' || input.kind === 'TABLE') {
        const property = input.propertyMetadata
        const supported =
          input.kind === 'GAUGE'
            ? ['NUMBER']
            : input.kind === 'JSON_VIEW'
              ? ['OBJECT', 'LIST']
              : ['LIST']
        if (
          !property ||
          property.key !== input.propertyKey ||
          !supported.includes(property.dataType)
        )
          throw new Error('incompatible property')
        if (input.kind === 'GAUGE' && properties.scaleMode === 'MODEL') {
          if (
            Object.hasOwn(input.componentProps ?? {}, 'min') ||
            Object.hasOwn(input.componentProps ?? {}, 'max')
          )
            throw new Error('物模型量程不能包含自定义边界')
          delete properties.min
          delete properties.max
          if (
            !property.minimumValue ||
            !gaugePosition(
              property.minimumValue,
              property.minimumValue,
              property.maximumValue ?? null
            )
          )
            throw new Error('invalid model gauge range')
        }
        if (input.kind === 'TABLE' && properties.mode !== 'LIST_VALUE')
          throw new Error('unsupported table mode')
      }
      const component = {
        ...placement,
        kind: input.kind,
        componentVersion: '1.0.0',
        props: properties,
        bindings:
          input.kind === 'STATUS'
            ? { status: { source: 'DEVICE_STATUS', device } }
            : { value: { source: 'CURRENT_VALUE', device, propertyKey: input.propertyKey } }
      }
      if (selected) page.components[page.components.indexOf(selected)] = component
      else page.components.push(component)
      createdId = placement.id
    })
    if (
      createdId &&
      state.schema?.pages.some((page) => page.components.some((item) => item.id === createdId))
    )
      emit({ selectedId: createdId })
  }
  function upsertDeviceVariable(input: DeviceVariableInput) {
    edit((draft) => {
      const old = input.key ? draft.variables.find((v) => v.key === input.key) : undefined
      if (input.key && (!old || !('modelKey' in old))) throw new Error('设备变量不存在')
      const references = old ? deviceVariableReferences(state.schema!, old.key) : []
      const oldModel =
        old && 'modelKey' in old ? draft.models.find((m) => m.key === old.modelKey) : undefined
      if (
        references.length &&
        (old!.type !== input.type || oldModel?.versionId !== input.model.versionId)
      )
        throw new Error('被引用的变量不能修改类型或模型')
      let model = draft.models.find((m) => m.versionId === input.model.versionId)
      if (
        model &&
        (model.digest !== input.model.digest ||
          model.digestAlgorithm !== input.model.digestAlgorithm ||
          model.profile !== input.model.profile)
      )
        throw new Error('模型身份不一致')
      const keys = new Set([...draft.models, ...draft.variables].map((item) => item.key))
      const next = (prefix: string) => {
        let i = 1
        while (keys.has(`${prefix}_${i}`)) i++
        const key = `${prefix}_${i}`
        keys.add(key)
        return key
      }
      if (!model) {
        model = { ...input.model, key: next('model') }
        draft.models.push(model)
      }
      if (input.type === 'DEVICE_SINGLE' && input.defaultDeviceIds.length > 1)
        throw new Error('单选默认值数量不合法')
      const variable = {
        key: old?.key ?? next('device'),
        type: input.type,
        title: input.title,
        required: input.required,
        modelKey: model.key,
        ...(input.type === 'DEVICE_SINGLE'
          ? input.defaultDeviceIds.length
            ? { defaultDeviceId: input.defaultDeviceIds[0] }
            : {}
          : { defaultDeviceIds: [...input.defaultDeviceIds], maxItems: input.maxItems ?? 20 })
      }
      if (old)
        draft.variables[draft.variables.indexOf(old)] = variable as
          | DashboardSingleDeviceVariable
          | DashboardMultiDeviceVariable
      else
        draft.variables.push(
          variable as DashboardSingleDeviceVariable | DashboardMultiDeviceVariable
        )
    })
  }
  function addVariableComponent(input: VariableComponentInput, replace = false) {
    let id: string | undefined
    edit((draft) => {
      const variable = draft.variables.find((v) => v.key === input.variableKey)
      if (
        !variable ||
        !(variable.type === 'DEVICE_SINGLE' || variable.type === 'DEVICE_MULTI') ||
        (input.kind === 'TABLE' && variable.type !== 'DEVICE_MULTI')
      )
        throw new Error('组件变量基数不匹配')
      if (input.kind === 'TABLE') {
        const expected = draft.models.find((m) => m.key === variable.modelKey)
        if (
          !expected ||
          !input.model ||
          expected.versionId !== input.model.versionId ||
          expected.digest !== input.model.digest ||
          expected.digestAlgorithm !== input.model.digestAlgorithm ||
          expected.profile !== input.model.profile
        )
          throw new Error('列元数据模型与变量不一致')
      }
      const page = draft.pages.find((p) => p.id === state.activePageId)!
      const selected = replace ? page.components.find((c) => c.id === state.selectedId) : undefined
      if (
        replace &&
        (!selected ||
          !(
            selected.kind === 'DEVICE_SELECTOR' ||
            (selected.kind === 'TABLE' && selected.props.mode === 'DEVICE_VALUES')
          ))
      )
        throw new Error('不支持替换此组件')
      const placement = selected
        ? { id: selected.id, layout: selected.layout }
        : nextPlacement(draft, page)
      const columnKeys = new Set(input.columns?.flatMap((c) => (c.id ? [c.id] : [])) ?? [])
      const columns =
        input.columns?.map((column) => {
          let id = column.id
          if (!id) {
            let index = 1
            while (columnKeys.has(`column_${index}`)) index++
            id = `column_${index}`
            columnKeys.add(id)
          }
          return { ...column, id }
        }) ?? []
      if (
        input.kind === 'TABLE' &&
        (!columns.length ||
          columns.length > 10 ||
          columns.some(
            (c) =>
              !input.properties?.some(
                (p) =>
                  p.key === c.propertyKey &&
                  ['NUMBER', 'TEXT', 'SWITCH', 'ENUM'].includes(p.dataType)
              )
          ))
      )
        throw new Error('列属性必须来自变量模型且为标量')
      const component = {
        ...placement,
        kind: input.kind,
        componentVersion: '1.0.0',
        props:
          input.kind === 'DEVICE_SELECTOR'
            ? {
                title: input.title,
                placeholder: input.placeholder ?? '请选择设备',
                pageSize: input.pageSize ?? 20
              }
            : {
                title: input.title,
                mode: 'DEVICE_VALUES',
                rowLimit: input.rowLimit ?? 20,
                columns: columns.map((c) => ({ id: c.id, label: c.label }))
              },
        bindings:
          input.kind === 'DEVICE_SELECTOR'
            ? { directory: { source: 'DEVICE_DIRECTORY', variableKey: variable.key } }
            : {
                columns: columns.map((c) => ({
                  id: c.id,
                  value: {
                    source: 'CURRENT_VALUE',
                    device: { variableKey: variable.key },
                    propertyKey: c.propertyKey
                  }
                }))
              }
      }
      if (selected) page.components[page.components.indexOf(selected)] = component
      else page.components.push(component)
      id = placement.id
    })
    if (id && state.schema?.pages.some((page) => page.components.some((c) => c.id === id)))
      emit({ selectedId: id })
  }
  function upsertTimeRange(input: TimeRangeInput) {
    edit((draft) => {
      const previous = input.key ? draft.variables.find((v) => v.key === input.key) : undefined
      if (input.key && previous?.type !== 'TIME_RANGE')
        throw new Error('时间变量不存在或类型不匹配')
      const keys = new Set([...draft.models, ...draft.variables].map((v) => v.key))
      let index = 1
      while (keys.has(`time_${index}`)) index++
      const variable: DashboardTimeRangeVariable = {
        key: previous?.key ?? `time_${index}`,
        type: 'TIME_RANGE',
        title: input.title,
        required: input.required,
        defaultPreset: input.defaultPreset,
        allowedPresets: [...input.allowedPresets]
      }
      if (previous) draft.variables[draft.variables.indexOf(previous)] = variable
      else draft.variables.push(variable)
    })
  }
  function addHistoryComponent(input: HistoryComponentInput, replace = false) {
    let id: string | undefined
    edit((draft) => {
      if (input.series.length < 1 || input.series.length > 4) throw new Error('图表要求1到4条系列')
      const page = draft.pages.find((p) => p.id === state.activePageId)!
      const selected = replace ? page.components.find((c) => c.id === state.selectedId) : undefined
      if (replace && selected?.kind !== 'LINE_CHART') throw new Error('只能替换选中历史图表')
      const keys = new Set(input.series.flatMap((s) => (s.id ? [s.id] : [])))
      const series = input.series.map((s) => {
        const variable = draft.variables.find((v) => v.key === s.deviceVariableKey)
        const time = draft.variables.find((v) => v.key === s.timeRangeVariableKey)
        if (variable?.type !== 'DEVICE_SINGLE' || time?.type !== 'TIME_RANGE')
          throw new Error('历史系列变量类型不匹配')
        const model = draft.models.find((m) => m.key === variable.modelKey)
        if (
          !model ||
          model.versionId !== s.model.versionId ||
          model.digest !== s.model.digest ||
          model.digestAlgorithm !== s.model.digestAlgorithm ||
          model.profile !== s.model.profile ||
          s.propertyMetadata.key !== s.propertyKey ||
          s.propertyMetadata.dataType !== 'NUMBER'
        )
          throw new Error('历史系列属性或模型不匹配')
        let id = s.id
        if (!id) {
          let index = 1
          while (keys.has(`series_${index}`)) index++
          id = `series_${index}`
          keys.add(id)
        }
        return { ...s, id }
      })
      const placement = selected
        ? { id: selected.id, layout: selected.layout }
        : nextPlacement(draft, page)
      const component = {
        ...placement,
        kind: 'LINE_CHART',
        componentVersion: '1.0.0',
        props: {
          title: input.title,
          showLegend: input.showLegend,
          series: series.map((s) => ({ id: s.id, label: s.label }))
        },
        bindings: {
          series: series.map((s) => ({
            id: s.id,
            value: {
              source: 'HISTORY_SERIES',
              device: { variableKey: s.deviceVariableKey },
              propertyKey: s.propertyKey,
              timeRangeVariableKey: s.timeRangeVariableKey,
              granularity: s.granularity,
              aggregation: s.aggregation
            }
          }))
        }
      }
      if (selected) page.components[page.components.indexOf(selected)] = component
      else page.components.push(component)
      id = placement.id
    })
    if (id && state.schema?.pages.some((p) => p.components.some((c) => c.id === id)))
      emit({ selectedId: id })
  }
  function addAlarmComponent(input: AlarmComponentInput, replace = false) {
    let id: string | undefined
    edit((draft) => {
      const variable = draft.variables.find((v) => v.key === input.deviceVariableKey)
      if (!variable || (variable.type !== 'DEVICE_SINGLE' && variable.type !== 'DEVICE_MULTI'))
        throw new Error('告警列表必须绑定设备变量')
      const page = draft.pages.find((p) => p.id === state.activePageId)!
      const selected = replace ? page.components.find((c) => c.id === state.selectedId) : undefined
      if (replace && selected?.kind !== 'ALARM_LIST') throw new Error('只能替换选中告警列表')
      const placement = selected
        ? { id: selected.id, layout: selected.layout }
        : nextPlacement(draft, page)
      const component = {
        ...placement,
        kind: 'ALARM_LIST',
        componentVersion: '1.0.0',
        props: { title: input.title, pageSize: input.pageSize, showClearedAt: input.showClearedAt },
        bindings: {
          alarms: {
            source: 'ALARM_LIST',
            devices: { variableKey: input.deviceVariableKey },
            conditionStates: [...input.conditionStates],
            ackStates: [...input.ackStates],
            severities: [...input.severities]
          }
        }
      }
      if (selected) page.components[page.components.indexOf(selected)] = component
      else page.components.push(component)
      id = placement.id
    })
    if (id && state.schema?.pages.some((p) => p.components.some((c) => c.id === id)))
      emit({ selectedId: id })
  }
  function upsertTextEnum(input: TextEnumInput) {
    edit((draft) => {
      const previous = input.key ? draft.variables.find((v) => v.key === input.key) : undefined
      if (input.key && previous?.type !== 'TEXT_ENUM') throw new Error('文本变量不存在或类型不匹配')
      const keys = new Set([...draft.models, ...draft.variables].map((v) => v.key))
      let index = 1
      while (keys.has(`text_${index}`)) index++
      const variable: DashboardTextEnumVariable = {
        key: previous?.key ?? `text_${index}`,
        type: 'TEXT_ENUM',
        title: input.title,
        required: input.required,
        options: input.options.map((option) => ({ ...option })),
        ...(input.defaultValue === undefined ? {} : { defaultValue: input.defaultValue })
      }
      if (previous) draft.variables[draft.variables.indexOf(previous)] = variable
      else draft.variables.push(variable)
    })
  }
  function addTextComponent(input: TextComponentInput, replace = false) {
    let id: string | undefined
    edit((draft) => {
      if (input.mode === 'DYNAMIC') {
        if (
          Object.hasOwn(input, 'content') ||
          draft.variables.find((v) => v.key === input.variableKey)?.type !== 'TEXT_ENUM'
        )
          throw new Error('动态文本仅允许文本枚举绑定')
      } else if (input.mode !== 'STATIC' || Object.hasOwn(input, 'variableKey'))
        throw new Error('静态文本不允许变量绑定')
      const page = draft.pages.find((p) => p.id === state.activePageId)!
      const selected = replace ? page.components.find((c) => c.id === state.selectedId) : undefined
      if (replace && selected?.kind !== 'TEXT') throw new Error('只能替换选中文本')
      const placement = selected
        ? { id: selected.id, layout: selected.layout }
        : nextPlacement(draft, page)
      const component = {
        ...placement,
        kind: 'TEXT',
        componentVersion: '1.0.0',
        props: {
          align: input.align,
          size: input.size,
          tone: input.tone,
          ...(input.mode === 'STATIC' ? { content: input.content ?? '' } : {})
        },
        bindings:
          input.mode === 'STATIC'
            ? {}
            : { text: { source: 'ENUM_TEXT', variableKey: input.variableKey } }
      }
      if (selected) page.components[page.components.indexOf(selected)] = component
      else page.components.push(component)
      id = placement.id
    })
    if (id && state.schema?.pages.some((p) => p.components.some((c) => c.id === id)))
      emit({ selectedId: id })
  }
  return {
    snapshot: () => state,
    reset() {
      generation++
      cancelTimer()
      undo = []
      redo = []
      baseline = ''
      firstDirty = null
      stopped = false
      state = blank()
      emit()
    },
    open(result: DraftSnapshot) {
      this.reset()
      try {
        if (
          !result.dashboardId ||
          !/^(0|[1-9][0-9]{0,18})$/.test(result.revision ?? '') ||
          BigInt(result.revision!) > 9223372036854775807n
        )
          throw new Error('invalid revision')
        const schema = normalized(result.content)
        baseline = JSON.stringify(schema)
        emit({
          dashboardId: result.dashboardId,
          schema,
          activePageId: schema.pages[0]!.id,
          revision: result.revision!,
          readonly: !editable(schema) || !ports.canEdit(),
          error: editable(schema)
            ? ''
            : '此草稿包含当前设计器未支持的内容，完整保留并以只读方式打开。'
        })
      } catch {
        emit({ error: '远端草稿不符合受支持合同，未修改远端内容。' })
      }
    },
    select(id: string | null) {
      if (
        id === null ||
        state.schema?.pages
          .find((page) => page.id === state.activePageId)
          ?.components.some((component) => component.id === id)
      )
        emit({ selectedId: id })
    },
    setPage(id: string) {
      if (state.schema?.pages.some((page) => page.id === id))
        emit({ activePageId: id, selectedId: null })
    },
    add(kind: 'TEXT' | 'IMAGE') {
      edit((draft) => {
        const page = draft.pages.find((entry) => entry.id === state.activePageId)!
        const placement = nextPlacement(draft, page)
        page.components.push({
          id: placement.id,
          kind,
          componentVersion: '1.0.0',
          layout: placement.layout,
          props:
            kind === 'TEXT'
              ? { content: '新文本' }
              : { ...BUILTIN_IMAGE, alt: '设备图标', fit: 'CONTAIN' },
          bindings: {}
        })
      })
      const page = state.schema?.pages.find((page) => page.id === state.activePageId)
      if (page?.components.length) emit({ selectedId: page.components.at(-1)!.id })
    },
    upsertTimeRange,
    removeTimeRange(key: string) {
      if (state.schema && deviceVariableReferences(state.schema, key).length) {
        emit({ error: '时间变量仍被组件引用，请先解除所有页面的引用。' })
        return
      }
      edit((draft) => {
        const variable = draft.variables.find((v) => v.key === key)
        if (variable?.type !== 'TIME_RANGE') throw new Error('不是时间变量')
        draft.variables = draft.variables.filter((v) => v.key !== key)
      })
    },
    upsertTextEnum,
    removeTextEnum(key: string) {
      if (state.schema && deviceVariableReferences(state.schema, key).length) {
        emit({ error: '文本变量仍被组件引用，请先解除所有页面的引用。' })
        return
      }
      edit((draft) => {
        if (draft.variables.find((v) => v.key === key)?.type !== 'TEXT_ENUM')
          throw new Error('不是文本变量')
        draft.variables = draft.variables.filter((v) => v.key !== key)
      })
    },
    addTextComponent,
    rebindTextComponent(input: TextComponentInput) {
      addTextComponent(input, true)
    },
    addAlarmComponent,
    rebindAlarmComponent(input: AlarmComponentInput) {
      addAlarmComponent(input, true)
    },
    addHistoryComponent,
    rebindHistoryComponent(input: HistoryComponentInput) {
      addHistoryComponent(input, true)
    },
    upsertDeviceVariable,
    removeDeviceVariable(key: string) {
      if (state.schema && deviceVariableReferences(state.schema, key).length) {
        emit({ error: '变量仍被组件引用，请先解除所有页面的引用。' })
        return
      }
      edit((draft) => {
        const variable = draft.variables.find((v) => v.key === key)
        if (variable && !('modelKey' in variable)) throw new Error('不是设备变量')
        draft.variables = draft.variables.filter((v) => v.key !== key)
      })
    },
    addVariableComponent,
    rebindVariableComponent(input: VariableComponentInput) {
      addVariableComponent(input, true)
    },
    addDeviceComponent,
    rebindSelectedDevice(input: DeviceComponentInput) {
      addDeviceComponent(input, true)
    },
    updateSelected(patch: {
      layout?: { x: number; y: number; w: number; h: number }
      props?: Record<string, unknown>
    }) {
      edit((draft) => {
        const item = draft.pages
          .find((page) => page.id === state.activePageId)
          ?.components.find((component) => component.id === state.selectedId)
        if (!item) return
        if (patch.layout) item.layout = { ...patch.layout }
        if (patch.props) item.props = { ...item.props, ...patch.props }
      })
    },
    setTheme(theme: 'LIGHT' | 'DARK') {
      edit((draft) => {
        draft.presentation.theme = theme
      })
    },
    setPresentation(mode: 'RESPONSIVE_GRID' | 'FIXED_SCREEN') {
      edit((draft) => {
        if (draft.presentation.mode === mode) return
        if (mode === 'FIXED_SCREEN' && draft.pages.length !== 1) throw new Error('multiple pages')
        for (const page of draft.pages)
          for (const component of page.components) {
            const old = component.layout
            component.layout =
              mode === 'FIXED_SCREEN'
                ? { x: old.x * 80, y: old.y * 16, w: old.w * 80, h: old.h * 16 }
                : {
                    x: Math.floor(old.x / 80),
                    y: Math.floor(old.y / 16),
                    w: Math.max(1, Math.ceil(old.w / 80)),
                    h: Math.max(1, Math.ceil(old.h / 16))
                  }
          }
        draft.presentation = { mode, theme: draft.presentation.theme }
      })
    },
    addPage(title: string) {
      edit((draft) => {
        let index = 1
        while (draft.pages.some((page) => page.id === `page_${index}`)) index++
        draft.pages.push({ id: `page_${index}`, title, components: [] })
      })
    },
    renamePage(title: string) {
      edit((draft) => {
        draft.pages.find((page) => page.id === state.activePageId)!.title = title
      })
    },
    removePage() {
      edit((draft) => {
        draft.pages = draft.pages.filter((page) => page.id !== state.activePageId)
      })
    },
    removeSelected() {
      edit((draft) => {
        const page = draft.pages.find((entry) => entry.id === state.activePageId)!
        page.components = page.components.filter((component) => component.id !== state.selectedId)
      })
    },
    undo() {
      if (!undo.length || state.readonly || !ports.canEdit() || !ports.available()) return
      redo.push(serialized())
      const previous = undo.pop()!
      trim()
      changed(normalized(JSON.parse(previous)))
    },
    redo() {
      if (!redo.length || state.readonly || !ports.canEdit() || !ports.available()) return
      undo.push(serialized())
      const next = redo.pop()!
      trim()
      changed(normalized(JSON.parse(next)))
    },
    retrySave() {
      if (state.conflict) {
        emit({ error: '修订冲突不能盲目覆盖，请先重新加载远端。' })
        return
      }
      stopped = false
      emit({ error: '' })
      void save()
    },
    dispose() {
      this.reset()
    }
  }
}
