<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import RuntimeHistoryChart from './RuntimeHistoryChart.vue'
import RuntimeAlarmList from './RuntimeAlarmList.vue'
import type { HistorySeriesView } from './history-presentation'
import RuntimeJsonTree from './RuntimeJsonTree.vue'
import { serializeRuntimeValue, isRuntimeNumber as isCompositeNumber, type RuntimeValue } from './composite-value'
import type { DashboardSchemaV1 } from '@things-link/client-contracts/dashboard/v1'
import { componentLayout, fixedFit, imageSource } from './static-presentation'
import type { StaticComponent, StaticImageResource } from './static-presentation'
import type { DevicePagePlan, DevicePageFacts, DeviceCatalogPage, DeviceSelection } from './device-data'
import { acceptsDeviceSelection, deviceTableIdentity, localTablePage, formatScalar, gaugePosition, isRuntimeNumber, stateLabel } from './device-presentation'

const props = defineProps<{
  schema: DashboardSchemaV1; resources: readonly StaticImageResource[];
  plan: DevicePagePlan | null; facts: DevicePageFacts | null;
  catalogs: Readonly<Record<string, DeviceCatalogPage>>;
  activePageId?: string; loading?: boolean; error?: string; selectionDisabled?: boolean;
}>()
const emit = defineEmits<{
  'page-change': [pageId: string];
  'alarm-page': [componentId: string, cursor: string | undefined];
  'select-device': [variableKey: string, deviceId: DeviceSelection];
  'catalog-page': [variableKey: string, cursor: string | undefined, componentId: string];
}>()
const viewport = ref<HTMLElement | null>(null)
const width = ref(0)
const height = ref(0)
let observer: ResizeObserver | undefined
const fixed = computed(() => props.schema.presentation.mode === 'FIXED_SCREEN')
const page = computed(() => props.schema.pages.find(entry => entry.id === props.activePageId) ?? props.schema.pages[0])
const scale = computed(() => fixedFit(width.value, height.value))
const supported = computed(() => props.schema.pages.every(entry => entry.components.every(component => (['1.0.0', '1.0.1'].includes(component.componentVersion))
  && (component.kind === 'TEXT' ? 'content' in component.props || 'text' in component.bindings
    : component.kind === 'IMAGE' ? imageSource(component, props.resources) !== undefined
    : ['TABLE', 'DEVICE_SELECTOR', 'VALUE_CARD', 'STATUS', 'GAUGE', 'JSON_VIEW', 'LINE_CHART', 'ALARM_LIST'].includes(component.kind)))))
watch(viewport, element => {
  observer?.disconnect()
  if (!element) return
  const measure = () => { width.value = element.clientWidth; height.value = element.clientHeight }
  observer = new ResizeObserver(measure); observer.observe(element); measure()
}, { flush: 'post' })
onBeforeUnmount(() => observer?.disconnect())

function variableKey(component: StaticComponent): string {
  if (component.kind === 'TEXT' && 'text' in component.bindings) return component.bindings.text.variableKey
  if (component.kind === 'ALARM_LIST') return component.bindings.alarms.devices.variableKey
  if (component.kind === 'DEVICE_SELECTOR') return component.bindings.directory.variableKey
  if (component.kind === 'TABLE' && 'columns' in component.bindings) return component.bindings.columns[0]?.value.device.variableKey ?? ''
  if (component.kind === 'JSON_VIEW') return component.bindings.value.device.variableKey
  if (component.kind === 'TABLE' && 'value' in component.bindings) return component.bindings.value.device.variableKey
  if (component.kind === 'STATUS') return component.bindings.status.device.variableKey
  if (component.kind === 'VALUE_CARD' || component.kind === 'GAUGE') return component.bindings.value.device.variableKey
  return ''
}
const selectionErrors = ref<Record<string, string>>({})
const tablePages = ref<Record<string, number>>({})
const compositeGeneration = ref(0)
watch([() => props.facts, () => props.plan, () => props.activePageId], () => {
  tablePages.value = {}; selectionErrors.value = {}; compositeGeneration.value += 1
})
function variable(component: StaticComponent) { return props.schema.variables.find(entry => entry.key === variableKey(component)) }
function isMulti(component: StaticComponent): boolean { return variable(component)?.type === 'DEVICE_MULTI' }
function selection(component: StaticComponent): DeviceSelection {
  return props.plan?.pageId === page.value.id ? props.plan.selections[variableKey(component)] ?? (isMulti(component) ? [] : null) : isMulti(component) ? [] : null
}
function selected(component: StaticComponent): string {
  const value = selection(component); return typeof value === 'string' ? value : ''
}
function selectedIds(component: StaticComponent): readonly string[] {
  const value = selection(component); return typeof value === 'string' ? [value] : value ?? []
}
function select(component: StaticComponent, event: Event) {
  const element = event.target as HTMLSelectElement
  const definition = variable(component)
  if (definition?.type === 'DEVICE_MULTI') {
    const ids = Array.from(element.selectedOptions, option => option.value)
    if (!acceptsDeviceSelection(ids, definition.maxItems)) {
      selectionErrors.value[variableKey(component)] = `最多选择${definition.maxItems}台设备，本次选择未应用。`
      const previous = new Set(selectedIds(component))
      for (const option of element.options) option.selected = previous.has(option.value)
      return
    }
    delete selectionErrors.value[variableKey(component)]
    emit('select-device', variableKey(component), ids)
  } else emit('select-device', variableKey(component), element.value || null)
}
function catalog(component: StaticComponent) { return props.catalogs[component.id] }
function outsideCatalog(component: StaticComponent): readonly string[] {
  const known = new Set(catalog(component)?.items.map(item => item.deviceId) ?? [])
  return selectedIds(component).filter(id => !known.has(id))
}

interface TableCell { columnId: string; state: string; text: string }
interface TableRow { deviceId: string; state: string; name: string; cells: readonly TableCell[] }
function table(component: StaticComponent) {
  const rows: TableRow[] = []
  if (component.kind !== 'TABLE' || component.props.mode !== 'DEVICE_VALUES' || !('columns' in component.bindings)) return localTablePage(rows, 0, 1)
  const key = variableKey(component)
  const ids = selectedIds(component)
  const factSelection = props.facts?.selections[key]
  const matching = Array.isArray(factSelection) && ids.length === factSelection.length && ids.every((id, index) => id === factSelection[index])
  const definition = variable(component)
  const modelId = definition && 'modelKey' in definition ? props.schema.models.find(model => model.key === definition.modelKey)?.versionId : undefined
  for (const deviceId of ids) {
    const snapshot = props.facts?.devices.find(device => device.deviceId === deviceId)
    const current = props.facts?.current.find(device => device.deviceId === deviceId)
    const identity = deviceTableIdentity(snapshot, current)
    const state = props.error ? 'ERROR' : !matching || props.facts?.pageId !== page.value.id ? 'LOADING' : identity.state
    const cells = component.bindings.columns.map(column => {
      if (state !== 'AVAILABLE') return { columnId: column.id, state, text: stateLabel(state) }
      const value = current?.values.find(value => value.propertyKey === column.value.propertyKey)
      const property = props.facts?.models.find(model => model.versionId === modelId)?.properties.find(property => property.propertyKey === column.value.propertyKey)
      if (!value || !property) return { columnId: column.id, state: 'CONTRACT_MISMATCH', text: stateLabel('CONTRACT_MISMATCH') }
      if (value.state !== 'VALUE') return { columnId: column.id, state: value.state, text: stateLabel(value.state) }
      const text = property.dataType === 'NUMBER' && isRuntimeNumber(value.value)
        ? `${value.value.lexical}${property.unit ? ` ${property.unit}` : ''}` : formatScalar(value.value, property, 0, 'MODEL')
      return text === null ? { columnId: column.id, state: 'CONTRACT_MISMATCH', text: stateLabel('CONTRACT_MISMATCH') }
        : { columnId: column.id, state: 'VALUE', text }
    })
    rows.push({ deviceId, state, name: state === 'AVAILABLE' ? identity.name : stateLabel(state), cells })
  }
  return localTablePage(rows, tablePages.value[component.id] ?? 0, component.props.rowLimit)
}

const controls = computed(() => props.plan?.pageId === page.value.id
  ? props.plan.variables.filter(variable => variable.type === 'TIME_RANGE' || variable.type === 'TEXT_ENUM') : [])
function selectControl(key: string, event: Event) { emit('select-device', key, (event.target as HTMLSelectElement).value || null) }
const timeLabels: Record<string, string> = { LAST_1_HOUR: '最近1小时', LAST_24_HOURS: '最近24小时', LAST_7_DAYS: '最近7天' }
function observationState(status?: string): string {
  return ({ READY: 'VALUE', NOT_SELECTED: 'UNSELECTED', NON_NUMERIC: '30058', CONFIGURATION_ERROR: '10001' } as Record<string, string>)[status ?? ''] ?? status ?? 'LOADING'
}
function currentObservationFacts(): boolean {
  if (!props.plan || !props.facts || props.plan.pageId !== page.value.id || props.facts.pageId !== page.value.id) return false
  return props.plan.variables.every(variable => {
    const before = props.plan!.selections[variable.key]; const actual = props.facts!.selections[variable.key]
    return Array.isArray(before) ? Array.isArray(actual) && before.length === actual.length && before.every((id, i) => id === actual[i]) : before === actual
  })
}
function histories(component: StaticComponent): readonly HistorySeriesView[] {
  if (component.kind !== 'LINE_CHART') return []
  return component.props.series.map(series => {
    const binding = props.plan?.historyBindings.find(binding => binding.componentId === component.id && binding.seriesId === series.id)
    const result = currentObservationFacts() ? props.facts?.history.find(entry => entry.queryId === binding?.queryId) : undefined
    const state = props.error ? 'ERROR' : binding?.queryId === null ? 'UNSELECTED' : observationState(result?.status)
    return { id: series.id, label: series.label, state,
      history: result?.status === 'READY' && result.requestedGranularity && result.actualGranularity && result.aggregation
        ? { requestedGranularity: result.requestedGranularity, actualGranularity: result.actualGranularity, aggregation: result.aggregation, points: result.points } : undefined }
  })
}
function alarm(component: StaticComponent) {
  return currentObservationFacts() ? props.facts?.alarms.find(entry => entry.componentId === component.id) : undefined
}
function alarmState(component: StaticComponent): string {
  return props.error ? 'ERROR' : props.plan?.alarmBindings.find(binding => binding.componentId === component.id)?.queryId === null ? 'UNSELECTED' : observationState(alarm(component)?.status)
}

interface ComponentView {
  state: string; text?: string; occurredAt?: string | null; lastOnlineAt?: string | null;
  composite?: RuntimeValue; value?: string; gauge?: NonNullable<ReturnType<typeof gaugePosition>>;
}
function display(component: StaticComponent): ComponentView {
  const key = variableKey(component)
  const variable = props.schema.variables.find(entry => entry.key === key)
  if (component.kind === 'TEXT' && 'text' in component.bindings) {
    const binding = props.plan?.pageId === page.value.id ? props.plan.textBindings.find(entry => entry.componentId === component.id) : undefined
    return binding?.label !== null && binding?.label !== undefined ? { state: 'VALUE', text: binding.label }
      : { state: 'UNSELECTED', text: variable?.required ? '请选择文本选项' : '未选择文本选项' }
  }
  const id = selected(component)
  if (!id) return { state: 'UNSELECTED', text: stateLabel('UNSELECTED', variable?.required) }
  if (props.error) return { state: 'ERROR', text: stateLabel('ERROR') }
  if (!props.facts || props.facts.pageId !== page.value.id || props.facts.selections[key] !== id) return {
    state: 'LOADING', text: stateLabel('LOADING'),
  }
  const device = props.facts.devices.find(entry => entry.deviceId === id)
  const current = props.facts.current.find(entry => entry.deviceId === id)
  if (!device) return { state: 'CONTRACT_MISMATCH', text: stateLabel('CONTRACT_MISMATCH') }
  const unavailable = current && current.status !== 'AVAILABLE' ? current.status : device.status
  if (unavailable !== 'AVAILABLE') return { state: unavailable, text: stateLabel(unavailable) }
  if (component.kind === 'STATUS' && device.status === 'AVAILABLE') return {
    state: device.deviceStatus, text: stateLabel(device.deviceStatus), lastOnlineAt: device.lastOnlineAt,
  }
  if (component.kind !== 'VALUE_CARD' && component.kind !== 'GAUGE' && component.kind !== 'JSON_VIEW'
    && !(component.kind === 'TABLE' && 'value' in component.bindings)) return { state: 'AVAILABLE' }
  if (!('value' in component.bindings)) return { state: 'CONTRACT_MISMATCH', text: stateLabel('CONTRACT_MISMATCH') }
  const propertyKey = component.bindings.value.propertyKey
  const reference = variable?.type === 'DEVICE_SINGLE' ? props.schema.models.find(entry => entry.key === variable.modelKey) : undefined
  const property = props.facts.models.find(entry => entry.versionId === reference?.versionId)?.properties.find(entry => entry.propertyKey === propertyKey)
  const value = current?.values.find(entry => entry.propertyKey === propertyKey)
  if (!property || !value) return { state: 'CONTRACT_MISMATCH', text: stateLabel('CONTRACT_MISMATCH') }
  if (value.state !== 'VALUE') return { state: value.state, text: stateLabel(value.state) }
  if (component.kind === 'JSON_VIEW' || component.kind === 'TABLE') {
    const valid = component.kind === 'TABLE' ? property.dataType === 'LIST' && Array.isArray(value.value)
      : (property.dataType === 'OBJECT' || property.dataType === 'LIST') && typeof value.value === 'object' && !isCompositeNumber(value.value)
    return valid ? { state: 'VALUE', composite: value.value, occurredAt: value.occurredAt }
      : { state: 'CONTRACT_MISMATCH', text: stateLabel('CONTRACT_MISMATCH') }
  }
  const text = formatScalar(value.value, property, component.props.precision, component.props.unitMode)
  if (text === null) return { state: 'CONTRACT_MISMATCH', text: stateLabel('CONTRACT_MISMATCH') }
  if (component.kind === 'GAUGE') {
    const gauge = isRuntimeNumber(value.value) && property.dataType === 'NUMBER' ? gaugePosition(value.value,
      component.props.scaleMode === 'MODEL' ? property.minimumValue : component.props.min,
      component.props.scaleMode === 'MODEL' ? property.maximumValue : component.props.max) : null
    if (!gauge) return { state: 'CONTRACT_MISMATCH', text: '仪表量程不符合合同' }
    return { state: 'VALUE', text, occurredAt: value.occurredAt, value: isRuntimeNumber(value.value) ? value.value.lexical : undefined, gauge }
  }
  return { state: 'VALUE', text, occurredAt: value.occurredAt }
}
function listPage(component: StaticComponent, view: ComponentView) {
  return localTablePage(Array.isArray(view.composite) ? view.composite : [], tablePages.value[component.id] ?? 0,
    component.kind === 'TABLE' ? component.props.rowLimit : 1)
}
const items = computed(() => page.value.components.map(component => ({ component, view: display(component) })))
</script>

<template>
  <section class="static-dashboard" :class="{ 'static-dark': schema.presentation.theme === 'DARK' }" data-testid="static-dashboard" :data-layout="schema.presentation.mode">
    <p v-if="!supported" role="alert" data-testid="static-dashboard-error">此看板包含当前客户端不支持的内容，无法显示完整发布版本。</p>
    <template v-else>
      <nav v-if="schema.pages.length > 1" class="static-page-tabs" aria-label="看板页面">
        <button v-for="entry in schema.pages" :key="entry.id" type="button" :aria-current="page.id === entry.id ? 'page' : undefined" :data-page-id="entry.id" data-testid="dashboard-page-tab" @click="emit('page-change', entry.id)">{{ entry.title }}</button>
      </nav>
      <div v-if="controls.length" class="runtime-variable-controls">
        <label v-for="control in controls" :key="control.key">{{ control.title }}
          <select :data-testid="`runtime-variable-${control.key}`" :disabled="selectionDisabled" @change="selectControl(control.key, $event)">
            <option value="" :selected="props.plan?.selections[control.key] == null">请选择</option>
            <template v-if="control.type === 'TIME_RANGE'"><option v-for="preset in control.allowedPresets" :key="preset" :value="preset" :selected="props.plan?.selections[control.key] === preset">{{ timeLabels[preset] }}</option></template>
            <template v-else-if="control.type === 'TEXT_ENUM'"><option v-for="option in control.options" :key="option.value" :value="option.value" :selected="props.plan?.selections[control.key] === option.value">{{ option.label }}</option></template>
          </select>
        </label>
      </div>
      <div ref="viewport" class="static-viewport" :class="{ 'static-fixed-viewport': fixed }">
        <div class="static-canvas" :class="{ 'static-fixed': fixed, 'static-grid': !fixed && width >= 768, 'static-single': !fixed && width < 768 }" data-testid="dashboard-canvas" :data-page-id="page.id" :style="fixed ? { transform: `translate(-50%, -50%) scale(${scale})` } : undefined">
          <article v-for="{ component, view } in items" :key="component.id" class="static-component" :data-component-id="component.id" :data-kind="component.kind" :style="componentLayout(component, fixed, width)">
            <p v-if="component.kind === 'TEXT' && 'content' in component.props" class="static-text" data-testid="dashboard-text" :class="[`text-${component.props.size.toLowerCase()}`, `tone-${component.props.tone.toLowerCase()}`, `align-${component.props.align.toLowerCase()}`]">{{ component.props.content }}</p>
            <p v-else-if="component.kind === 'TEXT'" class="static-text" data-testid="dashboard-text" :data-state="view.state" :class="[`text-${component.props.size.toLowerCase()}`, `tone-${component.props.tone.toLowerCase()}`, `align-${component.props.align.toLowerCase()}`]">{{ view.text }}</p>
            <template v-else-if="component.kind === 'LINE_CHART'">
              <h3 v-if="component.props.title" class="device-title">{{ component.props.title }}</h3>
              <RuntimeHistoryChart :component-id="component.id" :series="histories(component)" :show-legend="component.props.showLegend" />
            </template>
            <template v-else-if="component.kind === 'ALARM_LIST'">
              <h3 v-if="component.props.title" class="device-title">{{ component.props.title }}</h3>
              <RuntimeAlarmList :component-id="component.id" :state="alarmState(component)" :show-cleared-at="component.props.showClearedAt" :page="alarm(component)" :loading="loading" @page="emit('alarm-page', component.id, $event)" />
            </template>
            <template v-else-if="component.kind === 'IMAGE'">
              <h3 v-if="component.props.title" class="static-image-title">{{ component.props.title }}</h3>
              <img data-testid="dashboard-image" class="static-image" :src="imageSource(component, resources)" :alt="component.props.alt" :style="{ objectFit: component.props.fit === 'COVER' ? 'cover' : 'contain' }" draggable="false" />
            </template>
            <template v-else-if="component.kind === 'DEVICE_SELECTOR'">
              <label class="device-title" :for="`device-selector-${component.id}`">{{ component.props.title ?? '选择设备' }}</label>
              <select :id="`device-selector-${component.id}`" :data-testid="`device-selector-${component.id}`" :multiple="isMulti(component)" :disabled="selectionDisabled" @change="select(component, $event)">
                <option v-if="!isMulti(component)" value="" :selected="selected(component) === ''">{{ component.props.placeholder || '请选择设备' }}</option>
                <option v-for="id in outsideCatalog(component)" :key="id" :value="id" :selected="selectedIds(component).includes(id)">已选择设备（不在当前目录页）</option>
                <option v-for="device in catalog(component)?.items ?? []" :key="device.deviceId" :value="device.deviceId" :selected="selectedIds(component).includes(device.deviceId)">{{ device.name }}</option>
              </select>
              <p v-if="selectionErrors[variableKey(component)]" role="alert">{{ selectionErrors[variableKey(component)] }}</p>
              <button v-if="isMulti(component)" type="button" :data-testid="`device-selector-clear-${component.id}`" :disabled="selectionDisabled" @click="emit('select-device', variableKey(component), [])">清空设备选择</button>
              <p v-if="isMulti(component) && selectedIds(component).length === 0" class="device-detail">{{ stateLabel('UNSELECTED', variable(component)?.required) }}</p>
              <p v-if="catalog(component)?.items.length === 0" class="device-detail">当前目录页没有可选设备</p>
              <div class="device-actions">
                <button type="button" :data-testid="`device-catalog-first-${component.id}`" :disabled="loading || selectionDisabled" @click="emit('catalog-page', variableKey(component), undefined, component.id)">目录首页</button>
                <button v-if="catalog(component)?.hasMore" type="button" :data-testid="`device-catalog-next-${component.id}`" :disabled="loading || selectionDisabled" @click="emit('catalog-page', variableKey(component), catalog(component)?.nextCursor ?? undefined, component.id)">下一页</button>
              </div>
            </template>
            <template v-else-if="component.kind === 'JSON_VIEW' || component.kind === 'TABLE' && component.props.mode === 'LIST_VALUE'">
              <h3 v-if="component.props.title" class="device-title">{{ component.props.title }}</h3>
              <div :data-testid="`${component.kind === 'JSON_VIEW' ? 'composite-json' : 'composite-list'}-${component.id}`" :data-state="view.state">
                <p v-if="view.state !== 'VALUE'" role="status">{{ view.text }}</p>
                <template v-else-if="component.kind === 'JSON_VIEW' && view.composite !== undefined">
                  <RuntimeJsonTree :key="`${component.id}-${compositeGeneration}`" :value="view.composite" :initial-expand-depth="component.props.initialExpandDepth" />
                </template>
                <template v-else-if="component.kind === 'TABLE'">
                  <table class="device-table"><thead><tr><th scope="col">序号</th><th scope="col">值</th></tr></thead>
                    <tbody><tr v-for="(entry, index) in listPage(component, view).rows" :key="index" :data-list-index="listPage(component, view).index * component.props.rowLimit + index">
                      <th scope="row">{{ listPage(component, view).index * component.props.rowLimit + index + 1 }}</th><td><pre class="composite-text">{{ serializeRuntimeValue(entry) }}</pre></td>
                    </tr></tbody>
                  </table>
                  <p v-if="listPage(component, view).total === 0">完整列表为空</p>
                  <div class="device-actions">
                    <button type="button" :data-testid="`list-previous-${component.id}`" :disabled="listPage(component, view).index === 0" @click="tablePages[component.id] = listPage(component, view).index - 1">上一页</button>
                    <span :data-testid="`list-page-${component.id}`">第{{ listPage(component, view).index + 1 }} / {{ listPage(component, view).count }}页，共{{ listPage(component, view).total }}项</span>
                    <button type="button" :data-testid="`list-next-${component.id}`" :disabled="listPage(component, view).index + 1 >= listPage(component, view).count" @click="tablePages[component.id] = listPage(component, view).index + 1">下一页</button>
                  </div>
                </template>
                <p v-if="view.state === 'VALUE'" class="device-detail">{{ view.occurredAt ? `采集时间：${view.occurredAt}` : '采集时间未知' }}</p>
              </div>
            </template>
            <template v-else-if="component.kind === 'TABLE' && component.props.mode === 'DEVICE_VALUES'">
              <h3 v-if="component.props.title" class="device-title">{{ component.props.title }}</h3>
              <p v-if="table(component).total === 0" role="status">{{ stateLabel('UNSELECTED', variable(component)?.required) }}</p>
              <table v-else class="device-table" :data-testid="`device-table-${component.id}`">
                <thead><tr><th scope="col">设备名称</th><th v-for="column in component.props.columns" :key="column.id" scope="col">{{ column.label }}</th></tr></thead>
                <tbody><tr v-for="row in table(component).rows" :key="row.deviceId" :data-testid="`device-table-row-${component.id}`" :data-device-id="row.deviceId" :data-state="row.state">
                  <th scope="row">{{ row.name }}</th><td v-for="cell in row.cells" :key="cell.columnId" :data-column-id="cell.columnId" :data-state="cell.state">{{ cell.text }}</td>
                </tr></tbody>
              </table>
              <div class="device-actions">
                <button type="button" :data-testid="`device-table-previous-${component.id}`" :disabled="table(component).index === 0" @click="tablePages[component.id] = table(component).index - 1">上一页</button>
                <span :data-testid="`device-table-page-${component.id}`">第{{ table(component).index + 1 }} / {{ table(component).count }}页，共{{ table(component).total }}台</span>
                <button type="button" :data-testid="`device-table-next-${component.id}`" :disabled="table(component).index + 1 >= table(component).count" @click="tablePages[component.id] = table(component).index + 1">下一页</button>
              </div>
            </template>
            <template v-else-if="component.kind === 'VALUE_CARD' || component.kind === 'STATUS' || component.kind === 'GAUGE'">
              <h3 v-if="component.props.title" class="device-title">{{ component.props.title }}</h3>
              <div :data-testid="`device-${component.kind === 'VALUE_CARD' ? 'value' : component.kind === 'STATUS' ? 'status' : 'gauge'}-${component.id}`" :data-state="view.state" :data-value="view.value" :data-min="view.gauge?.minimum" :data-max="view.gauge?.maximum">
                <p class="device-value" role="status">{{ view.text }}</p>
                <template v-if="view.gauge">
                  <div class="device-gauge-track" aria-hidden="true"><span :style="{ width: `${view.gauge.percent}%` }"></span></div>
                  <p class="device-detail">量程 {{ view.gauge.minimum }} ～ {{ view.gauge.maximum }}</p>
                  <p v-if="view.gauge.outOfRange" class="device-warning">数值超出量程，指针已停在量程边界</p>
                </template>
                <p v-if="view.state === 'VALUE'" class="device-detail">{{ view.occurredAt ? `采集时间：${view.occurredAt}` : '采集时间未知' }}</p>
                <p v-if="component.kind === 'STATUS' && component.props.showLastOnlineAt && ['INACTIVE', 'ONLINE', 'OFFLINE'].includes(view.state)" class="device-detail">{{ view.lastOnlineAt ? `最后在线：${view.lastOnlineAt}` : '未提供最后在线时间' }}</p>
              </div>
            </template>
          </article>
        </div>
      </div>
    </template>
  </section>
</template>
<style scoped>
.static-dashboard { --static-text: #172b4d; --static-secondary: #53657e; --static-primary: #155bc1; color: var(--static-text); background: #fff; min-width: 0; width: 100%; }
.static-dark { --static-text: #e5edf9; --static-secondary: #aabbcf; --static-primary: #81b7ff; background: #142033; }
.static-page-tabs { display: flex; flex-wrap: wrap; gap: 8px; padding-bottom: 12px; }
.static-page-tabs button { font: inherit; color: inherit; background: transparent; border: 1px solid currentColor; border-radius: 6px; padding: 8px 12px; cursor: pointer; overflow-wrap: anywhere; }
.static-page-tabs button[aria-current="page"] { color: var(--static-primary); font-weight: 700; }
.static-viewport { width: 100%; min-width: 0; position: relative; overflow: hidden; }
.static-fixed-viewport { height: min(70vh, 1080px); }
.static-canvas { min-width: 0; }
.static-grid { display: grid; grid-template-columns: repeat(24, minmax(0, 1fr)); grid-auto-rows: 8px; gap: 8px; }
.static-single { display: flex; flex-direction: column; gap: 8px; }
.static-fixed { position: absolute; width: 1920px; height: 1080px; left: 50%; top: 50%; transform-origin: center; }
.static-component { box-sizing: border-box; display: flex; flex-direction: column; min-width: 0; min-height: 0; overflow: auto; }
.static-text { margin: 0; white-space: pre-wrap; overflow-wrap: anywhere; max-width: 100%; line-height: 1.5; color: var(--static-text); }
.text-small { font-size: 14px; }.text-medium { font-size: 18px; }.text-large { font-size: 28px; }
.tone-secondary { color: var(--static-secondary); }.tone-primary { color: var(--static-primary); }
.align-left { text-align: left; }.align-center { text-align: center; }.align-right { text-align: right; }
.static-image-title { margin: 0 0 8px; font-size: 16px; overflow-wrap: anywhere; flex-shrink: 0; }
.static-image { display: block; width: 100%; min-height: 0; flex: 1 1 0; object-position: center; }
.static-single .static-image { flex-basis: auto; height: auto; max-width: 100%; }
.device-title { margin: 0 0 8px; font-size: 16px; font-weight: 600; overflow-wrap: anywhere; }
.device-value { margin: 0; font-size: 22px; white-space: pre-wrap; overflow-wrap: anywhere; }
.device-detail { margin: 8px 0 0; color: var(--static-secondary); font-size: 13px; overflow-wrap: anywhere; }
.device-warning { margin: 8px 0 0; color: var(--static-primary); overflow-wrap: anywhere; }
.device-actions { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 8px; }
.device-actions button, select { max-width: 100%; box-sizing: border-box; font: inherit; color: inherit; background: inherit; border: 1px solid var(--static-secondary); padding: 6px 8px; border-radius: 4px; }
.device-gauge-track { margin-top: 8px; height: 12px; background: var(--static-secondary); border-radius: 6px; overflow: hidden; }
.device-gauge-track span { display: block; height: 100%; background: var(--static-primary); }
.device-table { width: 100%; border-collapse: collapse; table-layout: fixed; }
.device-table th, .device-table td { border: 1px solid var(--static-secondary); padding: 6px; text-align: left; white-space: pre-wrap; overflow-wrap: anywhere; }
.composite-text { margin: 0; font: inherit; white-space: pre-wrap; overflow-wrap: anywhere; }
.runtime-variable-controls { display: flex; flex-wrap: wrap; gap: 12px; padding-bottom: 12px; }
.runtime-variable-controls label { display: flex; flex-direction: column; min-width: 0; max-width: 100%; }
</style>
