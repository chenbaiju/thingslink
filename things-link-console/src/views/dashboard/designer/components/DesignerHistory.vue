<script setup lang="ts">
  import { computed, ref, watch, onBeforeUnmount } from 'vue'
  import type { DashboardSchemaV1 } from '@things-link/client-contracts/dashboard/v1'
  import {
    fetchDesignerDeviceCatalog,
    fetchBindingMetadata,
    createDesignerReadScope
  } from '@/api/dashboard-binding'
  import {
    deviceVariableReferences,
    type TimeRangeInput,
    type TimePreset,
    type HistoryComponentInput,
    type DeviceBindingProperty
  } from '@/features/dashboard/designer-model'
  const props = defineProps<{
    schema: DashboardSchemaV1
    selected?: DashboardSchemaV1['pages'][number]['components'][number] | null
    projectId: string
    disabled: boolean
  }>()
  const emit = defineEmits<{
    upsert: [input: TimeRangeInput]
    remove: [key: string]
    add: [input: HistoryComponentInput]
    rebind: [input: HistoryComponentInput]
  }>()
  const presets: TimePreset[] = ['LAST_1_HOUR', 'LAST_24_HOURS', 'LAST_7_DAYS']
  const labels: Record<TimePreset, string> = {
    LAST_1_HOUR: '最近1小时',
    LAST_24_HOURS: '最近24小时',
    LAST_7_DAYS: '最近7天'
  }
  const times = computed(() => props.schema.variables.filter((v) => v.type === 'TIME_RANGE'))
  const devices = computed(() => props.schema.variables.filter((v) => v.type === 'DEVICE_SINGLE'))
  const editing = ref(''),
    timeTitle = ref('时间范围'),
    required = ref(true),
    allowed = ref<TimePreset[]>([...presets]),
    defaultPreset = ref<TimePreset>('LAST_1_HOUR')
  const references = computed(() => deviceVariableReferences(props.schema, editing.value))
  function newTime() {
    editing.value = ''
    timeTitle.value = '时间范围'
    required.value = true
    allowed.value = [...presets]
    defaultPreset.value = 'LAST_1_HOUR'
  }
  function editTime(key: string) {
    const v = times.value.find((v) => v.key === key)
    if (!v) return
    editing.value = v.key
    timeTitle.value = v.title
    required.value = v.required
    allowed.value = [...v.allowedPresets]
    defaultPreset.value = v.defaultPreset
  }
  function saveTime() {
    if (props.disabled || !timeTitle.value.trim() || !allowed.value.includes(defaultPreset.value))
      return
    emit('upsert', {
      key: editing.value || undefined,
      title: timeTitle.value,
      required: required.value,
      allowedPresets: [...allowed.value],
      defaultPreset: defaultPreset.value
    })
  }
  type Series = HistoryComponentInput['series'][number]
  interface Row {
    uid: number
    id?: string
    label: string
    deviceVariableKey: string
    timeRangeVariableKey: string
    propertyKey: string
    granularity: Series['granularity']
    aggregation: Series['aggregation']
    items: Awaited<ReturnType<typeof fetchDesignerDeviceCatalog>>['items']
    cursor: string | null
    metadataDevice: string
    properties: DeviceBindingProperty[]
    model?: Series['model']
    busy: boolean
    error: string
    loaded: boolean
  }
  let sequence = 0,
    epoch = 0
  const scopes = new Map<number, ReturnType<typeof createDesignerReadScope>>()
  function blank(): Row {
    return {
      uid: ++sequence,
      label: '历史值',
      deviceVariableKey: '',
      timeRangeVariableKey: '',
      propertyKey: '',
      granularity: 'RAW',
      aggregation: 'AVG',
      items: [],
      cursor: null,
      metadataDevice: '',
      properties: [],
      busy: false,
      error: '',
      loaded: false
    }
  }
  const title = ref('历史曲线'),
    showLegend = ref(true),
    rows = ref<Row[]>([blank()])
  function clear(row: Row) {
    scopes.get(row.uid)?.close()
    scopes.delete(row.uid)
    row.items = []
    row.cursor = null
    row.properties = []
    row.model = undefined
    row.metadataDevice = ''
    row.busy = false
    row.error = ''
    row.loaded = false
  }
  function invalidate() {
    epoch++
    for (const row of rows.value) clear(row)
  }
  function remove(index: number) {
    if (rows.value.length <= 1 || props.disabled) return
    clear(rows.value[index]!)
    rows.value.splice(index, 1)
  }
  function modelFor(row: Row) {
    const variable = devices.value.find((v) => v.key === row.deviceVariableKey)
    return variable && props.schema.models.find((m) => m.key === variable.modelKey)
  }
  function changedVariable(row: Row) {
    clear(row)
    row.propertyKey = ''
  }
  watch(() => [props.projectId, props.disabled], invalidate, { flush: 'sync' })
  watch(() => props.schema.models, invalidate, { flush: 'sync' })
  watch(
    () => props.selected,
    (selected) => {
      if (selected?.kind !== 'LINE_CHART') return
      invalidate()
      title.value = selected.props.title ?? '历史曲线'
      showLegend.value = selected.props.showLegend
      rows.value = selected.bindings.series.map((entry, index) => ({
        ...blank(),
        id: entry.id,
        label: selected.props.series[index]!.label,
        deviceVariableKey: entry.value.device.variableKey,
        timeRangeVariableKey: entry.value.timeRangeVariableKey,
        propertyKey: entry.value.propertyKey,
        granularity: entry.value.granularity,
        aggregation: entry.value.aggregation
      }))
    },
    { immediate: true }
  )
  const visibility = () => {
    if (document.hidden) invalidate()
  }
  document.addEventListener('visibilitychange', visibility)
  onBeforeUnmount(() => {
    document.removeEventListener('visibilitychange', visibility)
    invalidate()
  })
  async function catalog(row: Row, next = false) {
    const model = modelFor(row)
    if (props.disabled || row.busy || !model || document.hidden) return
    const generation = epoch,
      variable = row.deviceVariableKey
    row.busy = true
    row.error = ''
    row.items = []
    row.loaded = false
    const scope = createDesignerReadScope()
    scopes.set(row.uid, scope)
    try {
      const result = await fetchDesignerDeviceCatalog(
        props.projectId,
        model.versionId,
        next ? (row.cursor ?? undefined) : undefined,
        20,
        scope
      )
      if (
        scopes.get(row.uid) !== scope ||
        generation !== epoch ||
        variable !== row.deviceVariableKey ||
        !rows.value.some((r) => r.uid === row.uid)
      )
        return
      row.items = result.items
      row.cursor = result.nextCursor
      row.loaded = true
    } catch {
      if (
        scopes.get(row.uid) === scope &&
        generation === epoch &&
        variable === row.deviceVariableKey
      ) {
        row.properties = []
        row.model = undefined
        row.cursor = null
        row.error = '目录读取失败，请检查权限与模型后重试。'
      }
    } finally {
      if (scopes.get(row.uid) === scope) {
        scopes.delete(row.uid)
        row.busy = false
      }
    }
  }
  async function metadata(row: Row) {
    const model = modelFor(row)
    if (props.disabled || row.busy || !model || !row.metadataDevice) return
    const generation = epoch,
      variable = row.deviceVariableKey
    row.busy = true
    row.error = ''
    row.properties = []
    row.model = undefined
    const scope = createDesignerReadScope()
    scopes.set(row.uid, scope)
    try {
      const result = await fetchBindingMetadata(props.projectId, row.metadataDevice, scope)
      if (
        scopes.get(row.uid) !== scope ||
        generation !== epoch ||
        variable !== row.deviceVariableKey ||
        !rows.value.some((r) => r.uid === row.uid)
      )
        return
      if (
        result.model.versionId !== model.versionId ||
        result.model.digest !== model.digest ||
        result.model.digestAlgorithm !== model.digestAlgorithm ||
        result.model.profile !== model.profile
      )
        throw new Error('model mismatch')
      row.model = result.model
      row.properties = result.properties.filter((p) => p.dataType === 'NUMBER')
    } catch {
      if (
        scopes.get(row.uid) === scope &&
        generation === epoch &&
        variable === row.deviceVariableKey
      )
        row.error = '属性读取失败或设备已换模，不能使用旧属性。'
    } finally {
      if (scopes.get(row.uid) === scope) {
        scopes.delete(row.uid)
        row.busy = false
      }
    }
  }
  const distinct = computed(
    () =>
      new Set(
        rows.value.map((row) =>
          JSON.stringify([
            row.deviceVariableKey,
            row.timeRangeVariableKey,
            row.propertyKey,
            row.granularity,
            row.aggregation
          ])
        )
      ).size === rows.value.length
  )
  const ready = computed(
    () =>
      !props.disabled &&
      distinct.value &&
      !!title.value.trim() &&
      rows.value.length >= 1 &&
      rows.value.length <= 4 &&
      rows.value.every(
        (row) =>
          !row.busy &&
          !!row.label.trim() &&
          !!row.model &&
          devices.value.some((v) => v.key === row.deviceVariableKey) &&
          times.value.some((v) => v.key === row.timeRangeVariableKey) &&
          row.properties.some((p) => p.key === row.propertyKey)
      )
  )
  function add(rebind = false) {
    if (!ready.value || (rebind && props.selected?.kind !== 'LINE_CHART')) return
    const input: HistoryComponentInput = {
      title: title.value,
      showLegend: showLegend.value,
      series: rows.value.map((row) => ({
        id: row.id,
        label: row.label,
        deviceVariableKey: row.deviceVariableKey,
        timeRangeVariableKey: row.timeRangeVariableKey,
        propertyKey: row.propertyKey,
        granularity: row.granularity,
        aggregation: row.aggregation,
        model: row.model!,
        propertyMetadata: row.properties.find((p) => p.key === row.propertyKey)!
      }))
    }
    if (rebind) emit('rebind', input)
    else emit('add', input)
  }
</script>
<template>
  <section class="console-fragment" aria-label="历史曲线编辑">
    <h3 class="console-heading">时间预设变量</h3
    ><p class="console-description">仅支持三个固定预设；默认值写入草稿，预览选择不会改写默认值。</p>
    <div class="console-actions">
      <button :disabled="disabled" @click="newTime">新建时间变量</button>
      <button v-for="time in times" :key="time.key" :disabled="disabled" @click="editTime(time.key)"
        >编辑时间变量 {{ time.title }}</button
      >
    </div>
    <fieldset :disabled="disabled"
      ><label
        >时间变量标题<input v-model="timeTitle" aria-label="时间变量标题" maxlength="80"
      /></label>
      <label v-for="preset in presets" :key="preset"
        ><input
          v-model="allowed"
          type="checkbox"
          :value="preset"
          :aria-label="`允许${labels[preset]}`"
        />{{ labels[preset] }}</label
      >
      <label
        >默认时间预设<select v-model="defaultPreset" aria-label="默认时间预设"
          ><option v-for="preset in allowed" :key="preset" :value="preset">{{
            labels[preset]
          }}</option></select
        ></label
      >
      <label><input v-model="required" type="checkbox" aria-label="时间变量必选" />必选</label>
      <div class="console-actions">
        <button
          :disabled="!timeTitle.trim() || !allowed.includes(defaultPreset)"
          data-testid="time-variable-save"
          @click="saveTime"
          >保存时间变量</button
        >
        <button
          v-if="editing"
          :disabled="references.length > 0"
          data-testid="time-variable-delete"
          @click="emit('remove', editing)"
          >删除时间变量</button
        >
      </div>
      <p class="console-description" v-if="editing && references.length"
        >时间变量被以下组件引用，不能删除：{{ references.join('；') }}</p
      ></fieldset
    >
    <h3 class="console-heading">历史曲线</h3
    ><fieldset :disabled="disabled"
      ><label>图表标题<input v-model="title" aria-label="历史图表标题" maxlength="80" /></label
      ><label
        ><input v-model="showLegend" type="checkbox" aria-label="显示历史图例" />显示图例</label
      ></fieldset
    >
    <fieldset v-for="(row, index) in rows" :key="row.uid" :disabled="disabled || row.busy"
      ><legend>第{{ index + 1 }}条历史系列</legend>
      <label
        >系列标题<input v-model="row.label" :aria-label="`历史系列${index + 1}标题`" maxlength="80"
      /></label>
      <label
        >单设备变量<select
          v-model="row.deviceVariableKey"
          :aria-label="`历史系列${index + 1}设备变量`"
          @change="changedVariable(row)"
          ><option value="">请选择单设备变量</option
          ><option v-for="variable in devices" :key="variable.key" :value="variable.key"
            >{{ variable.title }} · {{ variable.key }}</option
          ></select
        ></label
      >
      <label
        >时间变量<select
          v-model="row.timeRangeVariableKey"
          :aria-label="`历史系列${index + 1}时间变量`"
          ><option value="">请选择时间变量</option
          ><option v-for="time in times" :key="time.key" :value="time.key"
            >{{ time.title }} · {{ time.key }}</option
          ></select
        ></label
      >
      <div class="console-actions">
        <button :disabled="!modelFor(row)" @click="catalog(row)"
          >读取系列{{ index + 1 }}设备目录</button
        ><button :disabled="!row.cursor" @click="catalog(row, true)"
          >下一页系列{{ index + 1 }}设备</button
        >
      </div>
      <p class="console-description" v-if="row.loaded && !row.items.length">当前页无可选择设备。</p>
      <label
        >属性元数据设备<select
          v-model="row.metadataDevice"
          :aria-label="`历史系列${index + 1}元数据设备`"
          @change="metadata(row)"
          ><option value="">明确选择设备读取属性</option
          ><option v-for="device in row.items" :key="device.deviceId" :value="device.deviceId">{{
            device.name
          }}</option></select
        ></label
      >
      <label
        >数值属性<select v-model="row.propertyKey" :aria-label="`历史系列${index + 1}属性`"
          ><option value="">请选择顶层数值属性</option
          ><option v-for="property in row.properties" :key="property.key" :value="property.key"
            >{{ property.name }}（{{ property.key }}）</option
          ></select
        ></label
      >
      <label
        >粒度<select v-model="row.granularity" :aria-label="`历史系列${index + 1}粒度`"
          ><option
            v-for="value in ['RAW', 'ONE_MINUTE', 'ONE_HOUR', 'ONE_DAY']"
            :key="value"
            :value="value"
            >{{ value }}</option
          ></select
        ></label
      >
      <label
        >聚合<select v-model="row.aggregation" :aria-label="`历史系列${index + 1}聚合`"
          ><option
            v-for="value in ['AVG', 'MIN', 'MAX', 'SUM', 'COUNT']"
            :key="value"
            :value="value"
            >{{ value }}</option
          ></select
        ></label
      >
      <button :disabled="rows.length <= 1" @click="remove(index)">删除系列{{ index + 1 }}</button
      ><p class="console-description" v-if="row.error" role="alert">{{ row.error }}</p></fieldset
    >
    <div class="console-actions">
      <button :disabled="disabled || rows.length >= 4" @click="rows.push(blank())"
        >添加历史系列</button
      >
      <button :disabled="!ready" data-testid="history-component-add" @click="add()"
        >添加历史图表</button
      ><button
        :disabled="!ready || selected?.kind !== 'LINE_CHART'"
        data-testid="history-component-rebind"
        @click="add(true)"
        >替换选中历史图表</button
      >
    </div>
  </section>
</template>
<style scoped>
  section,
  fieldset {
    min-width: 0;
  }
  fieldset {
    margin: 8px 0;
  }
  label {
    display: block;
    margin: 6px 0;
  }
  input:not([type='checkbox']),
  select {
    width: 100%;
    max-width: 100%;
  }
  button {
    margin: 4px;
  }
  p {
    overflow-wrap: anywhere;
  }
</style>
