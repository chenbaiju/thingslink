<script setup lang="ts">
  import { computed, ref, shallowRef, watch, onBeforeUnmount } from 'vue'
  import type { DashboardSchemaV1 } from '@things-link/client-contracts/dashboard/v1'
  import {
    fetchDesignerDeviceCatalog,
    fetchBindingMetadata,
    createDesignerReadScope
  } from '@/api/dashboard-binding'
  import {
    deviceVariableReferences,
    type DeviceVariableInput,
    type VariableComponentInput,
    type DeviceComponentInput,
    type DeviceBindingProperty
  } from '@/features/dashboard/designer-model'
  const props = defineProps<{
    schema: DashboardSchemaV1
    projectId: string
    disabled: boolean
    selected?: DashboardSchemaV1['pages'][number]['components'][number] | null
    bindingModel?: DeviceComponentInput['model'] | null
  }>()
  const emit = defineEmits<{
    upsert: [input: DeviceVariableInput]
    remove: [key: string]
    add: [input: VariableComponentInput]
    rebind: [input: VariableComponentInput]
  }>()
  const editing = ref(''),
    type = ref<'DEVICE_SINGLE' | 'DEVICE_MULTI'>('DEVICE_SINGLE'),
    title = ref('设备选择'),
    required = ref(true),
    maxItems = ref(20),
    modelKey = ref(''),
    defaults = ref<string[]>([])
  const variables = computed(() =>
    props.schema.variables.filter((v) => v.type === 'DEVICE_SINGLE' || v.type === 'DEVICE_MULTI')
  )
  const references = computed(() =>
    editing.value ? deviceVariableReferences(props.schema, editing.value) : []
  )
  const model = computed(() =>
    modelKey.value === '__binding'
      ? props.bindingModel
      : props.schema.models.find((m) => m.key === modelKey.value)
  )
  const items = shallowRef<Awaited<ReturnType<typeof fetchDesignerDeviceCatalog>>['items']>([]),
    cursor = ref<string | null>(null),
    busy = ref(false),
    error = ref(''),
    loaded = ref(false)
  const properties = shallowRef<readonly DeviceBindingProperty[]>([]),
    metadataDevice = ref('')
  let generation = 0,
    scope: ReturnType<typeof createDesignerReadScope> | undefined
  function clearRead() {
    generation++
    scope?.close()
    items.value = []
    cursor.value = null
    loaded.value = false
    properties.value = []
    metadataDevice.value = ''
    busy.value = false
    error.value = ''
  }
  function startNew() {
    editing.value = ''
    type.value = 'DEVICE_SINGLE'
    title.value = '设备选择'
    required.value = true
    maxItems.value = 20
    modelKey.value = ''
    defaults.value = []
    clearRead()
  }
  function editVariable(key: string) {
    const v = variables.value.find((v) => v.key === key)
    if (!v) return
    editing.value = v.key
    type.value = v.type
    title.value = v.title
    required.value = v.required
    maxItems.value = v.type === 'DEVICE_MULTI' ? v.maxItems : 20
    modelKey.value = v.modelKey
    defaults.value =
      v.type === 'DEVICE_MULTI'
        ? [...v.defaultDeviceIds]
        : v.defaultDeviceId
          ? [v.defaultDeviceId]
          : []
    clearRead()
  }
  watch(
    () => props.projectId,
    () => {
      startNew()
      componentVariable.value = ''
    },
    { flush: 'sync' }
  )
  watch(() => props.disabled, clearRead, { flush: 'sync' })
  watch(modelKey, () => {
    clearRead()
    if (!editing.value) defaults.value = []
  })
  watch(type, () => {
    if (!editing.value) defaults.value = []
  })
  const visibility = () => {
    if (document.hidden) clearRead()
  }
  document.addEventListener('visibilitychange', visibility)
  onBeforeUnmount(() => {
    document.removeEventListener('visibilitychange', visibility)
    clearRead()
  })
  async function catalog(next = false) {
    if (props.disabled || busy.value || !model.value || document.hidden) return
    const epoch = generation
    const expected = model.value.versionId
    busy.value = true
    error.value = ''
    scope = createDesignerReadScope()
    try {
      const result = await fetchDesignerDeviceCatalog(
        props.projectId,
        expected,
        next ? (cursor.value ?? undefined) : undefined,
        20,
        scope
      )
      if (epoch !== generation) return
      items.value = result.items
      cursor.value = result.nextCursor
      loaded.value = true
    } catch {
      if (epoch === generation) {
        items.value = []
        cursor.value = null
        loaded.value = false
        properties.value = []
        error.value = '设备目录读取失败，请检查项目权限与模型后重试。'
      }
    } finally {
      if (epoch === generation) busy.value = false
    }
  }
  function toggleDefault(id: string) {
    if (props.disabled) return
    if (defaults.value.includes(id)) defaults.value = defaults.value.filter((value) => value !== id)
    else if (type.value === 'DEVICE_SINGLE') defaults.value = [id]
    else if (defaults.value.length < maxItems.value) defaults.value = [...defaults.value, id]
  }
  async function metadata() {
    if (props.disabled || busy.value || !metadataDevice.value || !model.value) return
    const epoch = generation
    busy.value = true
    error.value = ''
    properties.value = []
    scope = createDesignerReadScope()
    try {
      const result = await fetchBindingMetadata(props.projectId, metadataDevice.value, scope)
      if (epoch !== generation) return
      if (
        result.model.versionId !== model.value.versionId ||
        result.model.digest !== model.value.digest ||
        result.model.digestAlgorithm !== model.value.digestAlgorithm ||
        result.model.profile !== model.value.profile
      )
        throw new Error('model mismatch')
      properties.value = result.properties
    } catch {
      if (epoch === generation) error.value = '属性读取失败或设备已换模，未采用旧属性。'
    } finally {
      if (epoch === generation) busy.value = false
    }
  }
  function save() {
    if (
      props.disabled ||
      !model.value ||
      !title.value.trim() ||
      defaults.value.length > (type.value === 'DEVICE_SINGLE' ? 1 : maxItems.value)
    )
      return
    emit('upsert', {
      key: editing.value || undefined,
      type: type.value,
      title: title.value,
      required: required.value,
      model: {
        versionId: model.value.versionId,
        digest: model.value.digest,
        digestAlgorithm: model.value.digestAlgorithm,
        profile: model.value.profile
      },
      defaultDeviceIds: [...defaults.value],
      maxItems: maxItems.value
    })
  }
  const componentVariable = ref(''),
    kind = ref<'DEVICE_SELECTOR' | 'TABLE'>('DEVICE_SELECTOR'),
    componentTitle = ref('多设备当前值'),
    placeholder = ref('请选择设备'),
    pageSize = ref(20),
    rowLimit = ref(20),
    columns = ref<{ id?: string; label: string; propertyKey: string }[]>([
      { label: '属性', propertyKey: '' }
    ])
  const target = computed(() => variables.value.find((v) => v.key === componentVariable.value))
  const scalarProperties = computed(() =>
    properties.value.filter((p) => ['NUMBER', 'TEXT', 'SWITCH', 'ENUM'].includes(p.dataType))
  )
  const canRebind = computed(
    () =>
      props.selected?.kind === 'DEVICE_SELECTOR' ||
      (props.selected?.kind === 'TABLE' && props.selected.props.mode === 'DEVICE_VALUES')
  )
  watch(
    () => props.selected,
    (selected) => {
      if (selected?.kind === 'DEVICE_SELECTOR') {
        kind.value = 'DEVICE_SELECTOR'
        componentTitle.value =
          selected.props.title ??
          variables.value.find((v) => v.key === selected.bindings.directory.variableKey)?.title ??
          '设备选择器'
        componentVariable.value = selected.bindings.directory.variableKey
        placeholder.value = selected.props.placeholder
        pageSize.value = selected.props.pageSize
      } else if (
        selected?.kind === 'TABLE' &&
        selected.props.mode === 'DEVICE_VALUES' &&
        'columns' in selected.bindings
      ) {
        kind.value = 'TABLE'
        componentTitle.value = selected.props.title ?? '多设备当前值'
        rowLimit.value = selected.props.rowLimit
        componentVariable.value = selected.bindings.columns[0]?.value.device.variableKey ?? ''
        columns.value = selected.props.columns.map((c, i) => ({
          ...c,
          propertyKey:
            'columns' in selected.bindings ? selected.bindings.columns[i]!.value.propertyKey : ''
        }))
      }
    },
    { immediate: true }
  )
  const canAdd = computed(
    () =>
      !props.disabled &&
      !busy.value &&
      !!target.value &&
      ((kind.value === 'DEVICE_SELECTOR' &&
        Number.isInteger(pageSize.value) &&
        pageSize.value >= 1 &&
        pageSize.value <= 50) ||
        (target.value.type === 'DEVICE_MULTI' &&
          Number.isInteger(rowLimit.value) &&
          rowLimit.value >= 1 &&
          rowLimit.value <= 256 &&
          target.value.modelKey === modelKey.value &&
          columns.value.length >= 1 &&
          columns.value.length <= 10 &&
          columns.value.every(
            (c) => c.label.trim() && scalarProperties.value.some((p) => p.key === c.propertyKey)
          )))
  )
  function add(rebind = false) {
    if (!canAdd.value || (rebind && !canRebind.value)) return
    const input: VariableComponentInput = {
      kind: kind.value,
      variableKey: componentVariable.value,
      title: componentTitle.value,
      placeholder: placeholder.value,
      pageSize: pageSize.value,
      rowLimit: rowLimit.value,
      columns: columns.value.map((c) => ({ ...c })),
      model: model.value ?? undefined,
      properties: properties.value
    }
    if (rebind) emit('rebind', input)
    else emit('add', input)
  }
</script>
<template>
  <section class="console-fragment" aria-label="设备变量管理">
    <ElDivider content-position="left">设备变量</ElDivider
    ><ElAlert class="console-hint" type="info" show-icon :closable="false"
      >默认设备写入草稿；预览中的临时选择不会修改默认值。设备可见性由每次读取重新确认。</ElAlert
    >
    <button :disabled="disabled" data-testid="variable-new" @click="startNew">新建设备变量</button>
    <div v-for="v in variables" :key="v.key"
      ><button
        :disabled="disabled"
        :data-testid="`variable-edit-${v.key}`"
        @click="editVariable(v.key)"
        >编辑变量 {{ v.title }}</button
      ><span>{{ v.type === 'DEVICE_SINGLE' ? '单设备' : '多设备' }}</span></div
    >
    <fieldset :disabled="disabled || busy">
      <label>变量标题<input v-model="title" aria-label="变量标题" maxlength="80" /></label>
      <label
        >变量类型<select v-model="type" aria-label="变量类型" :disabled="references.length > 0"
          ><option value="DEVICE_SINGLE">单设备</option
          ><option value="DEVICE_MULTI">多设备</option></select
        ></label
      >
      <label
        >变量模型<select
          v-model="modelKey"
          aria-label="变量模型"
          @change="defaults = []"
          :disabled="references.length > 0"
          ><option value="">请选择精确模型</option
          ><option v-for="m in schema.models" :key="m.key" :value="m.key"
            >{{ m.key }} · {{ m.versionId }}</option
          ><option
            v-if="
              bindingModel && !schema.models.some((m) => m.versionId === bindingModel?.versionId)
            "
            value="__binding"
            >当前绑定设备的精确模型</option
          ></select
        ></label
      >
      <label><input v-model="required" type="checkbox" aria-label="变量必选" />必选</label>
      <label v-if="type === 'DEVICE_MULTI'"
        >最多设备数<input
          v-model.number="maxItems"
          type="number"
          min="1"
          max="20"
          aria-label="变量最多设备数"
      /></label>
      <ElAlert
        v-if="references.length"
        class="console-hint"
        type="warning"
        show-icon
        :closable="false"
        >此变量正在被引用，不能删除或修改类型、模型：{{ references.join('；') }}</ElAlert
      >
      <div class="console-actions">
        <button :disabled="!model" @click="catalog()">读取变量设备目录</button
        ><button :disabled="!cursor" @click="catalog(true)">下一页变量设备</button>
      </div>
      <p class="console-description" v-if="loaded && !items.length">当前页无可选择设备。</p>
      <label v-for="device in items" :key="device.deviceId"
        ><input
          type="checkbox"
          :checked="defaults.includes(device.deviceId)"
          :aria-label="`默认设备 ${device.name}`"
          @change="toggleDefault(device.deviceId)"
        />{{ device.name }}</label
      >
      <p class="console-description"
        >默认设备 {{ defaults.length }} 台；未选默认设备时等待用户选择，不会自动选第一台。</p
      >
      <div v-for="id in defaults" :key="id"
        ><span>{{ id }}</span
        ><button @click="toggleDefault(id)">移除默认设备 {{ id }}</button></div
      >
      <div class="console-actions">
        <button
          :disabled="
            !model ||
            !title.trim() ||
            !Number.isInteger(maxItems) ||
            maxItems < 1 ||
            maxItems > 20 ||
            defaults.length > (type === 'DEVICE_SINGLE' ? 1 : maxItems)
          "
          data-testid="variable-save"
          @click="save"
          >保存变量</button
        >
        <button
          v-if="editing"
          :disabled="references.length > 0"
          data-testid="variable-delete"
          @click="emit('remove', editing)"
          >删除变量</button
        >
      </div>
      <label
        >属性元数据设备<select
          v-model="metadataDevice"
          aria-label="变量属性元数据设备"
          @change="metadata"
          ><option value="">明确选择一台设备读取属性</option
          ><option v-for="device in items" :key="device.deviceId" :value="device.deviceId">{{
            device.name
          }}</option></select
        ></label
      >
    </fieldset>
    <ElAlert
      v-if="error"
      role="alert"
      class="console-hint"
      type="error"
      show-icon
      :closable="false"
      >{{ error }}</ElAlert
    >
    <ElDivider content-position="left">变量组件</ElDivider>
    <fieldset :disabled="disabled || busy">
      <label
        >组件类型<select v-model="kind" aria-label="变量组件类型"
          ><option value="DEVICE_SELECTOR">设备选择器</option
          ><option value="TABLE">多设备当前值表</option></select
        ></label
      >
      <label
        >绑定变量<select v-model="componentVariable" aria-label="组件设备变量"
          ><option value="">请选择变量</option
          ><option
            v-for="v in variables.filter(
              (v) => kind === 'DEVICE_SELECTOR' || v.type === 'DEVICE_MULTI'
            )"
            :key="v.key"
            :value="v.key"
            >{{ v.title }} · {{ v.key }}</option
          ></select
        ></label
      >
      <template v-if="kind === 'DEVICE_SELECTOR'">
        <label
          >选择器标题<input
            v-model="componentTitle"
            aria-label="选择器标题"
            maxlength="80" /></label
        ><label>选择提示<input v-model="placeholder" aria-label="选择器提示" /></label
        ><label
          >目录每页数<input
            v-model.number="pageSize"
            type="number"
            min="1"
            max="50"
            aria-label="选择器每页数量" /></label
      ></template>
      <template v-else
        ><label>表格标题<input v-model="componentTitle" aria-label="多设备表标题" /></label
        ><label
          >每页行数<input
            v-model.number="rowLimit"
            type="number"
            min="1"
            max="256"
            aria-label="多设备表每页行数" /></label
        ><ElAlert class="console-hint" type="info" show-icon :closable="false"
          >请先编辑该变量，读取同模型设备的属性元数据，再配置 1～10 个标量列。</ElAlert
        >
        <div v-for="(column, index) in columns" :key="index"
          ><label>列标题<input v-model="column.label" :aria-label="`第${index + 1}列标题`" /></label
          ><label
            >列属性<select v-model="column.propertyKey" :aria-label="`第${index + 1}列属性`"
              ><option value="">请选择标量属性</option
              ><option v-for="p in scalarProperties" :key="p.key" :value="p.key"
                >{{ p.name }}（{{ p.key }}）</option
              ></select
            ></label
          ><button :disabled="columns.length <= 1" @click="columns.splice(index, 1)"
            >删除第{{ index + 1 }}列</button
          ></div
        ><button
          :disabled="columns.length >= 10"
          @click="columns.push({ label: '属性', propertyKey: '' })"
          >添加表格列</button
        ></template
      >
      <div class="console-actions">
        <button :disabled="!canAdd" data-testid="variable-component-add" @click="add()"
          >添加变量组件</button
        ><button
          :disabled="!canAdd || !canRebind"
          data-testid="variable-component-rebind"
          @click="add(true)"
          >替换选中变量组件</button
        >
      </div>
    </fieldset>
  </section>
</template>
<style scoped>
  section {
    min-width: 0;
    overflow-wrap: anywhere;
  }
  fieldset {
    min-width: 0;
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
</style>
