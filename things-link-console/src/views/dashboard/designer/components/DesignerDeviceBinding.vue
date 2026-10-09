<template>
  <el-form class="console-fragment" label-position="top" :disabled="disabled || busy">
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <el-form-item label="组件类型"
      ><el-select v-model="kind" aria-label="绑定组件类型">
        <el-option label="属性值" value="VALUE_CARD" /><el-option label="设备状态" value="STATUS" />
        <el-option label="仪表盘" value="GAUGE" /><el-option
          label="复合值"
          value="JSON_VIEW"
        /><el-option label="列表值表格" value="TABLE" /> </el-select
    ></el-form-item>
    <el-form-item v-if="existingVariables.length" label="复用设备变量"
      ><select v-model="variableKey" aria-label="绑定现有设备变量"
        ><option value="">按当前设备创建或复用默认变量</option
        ><option v-for="variable in existingVariables" :key="variable.key" :value="variable.key"
          >{{ variable.title }} · {{ variable.key }}</option
        ></select
      ></el-form-item
    >
    <el-form-item label="组件标题"
      ><el-input v-model="title" maxlength="80" aria-label="绑定组件标题"
    /></el-form-item>
    <el-form-item v-if="kind !== 'STATUS'" label="顶层属性"
      ><el-select v-model="propertyKey" placeholder="选择属性" aria-label="绑定顶层属性">
        <el-option
          v-for="property in supportedProperties"
          :key="property.key"
          :label="`${property.name}（${property.key}）`"
          :value="property.key"
        /> </el-select
    ></el-form-item>
    <template v-if="kind === 'VALUE_CARD' || kind === 'GAUGE'">
      <el-form-item label="显示小数位"
        ><el-input-number
          v-model="precision"
          :min="0"
          :max="6"
          :precision="0"
          aria-label="绑定显示小数位"
      /></el-form-item>
      <el-form-item label="单位"
        ><el-select v-model="unitMode" aria-label="绑定单位"
          ><el-option label="物模型单位" value="MODEL" /><el-option
            label="不显示单位"
            value="NONE" /></el-select
      ></el-form-item>
    </template>
    <el-form-item v-if="kind === 'STATUS'" label="最后在线时间"
      ><el-switch v-model="showLastOnlineAt" aria-label="绑定最后在线时间"
    /></el-form-item>
    <template v-if="kind === 'GAUGE'">
      <el-form-item label="仪表量程"
        ><el-select v-model="scaleMode" aria-label="绑定仪表量程"
          ><el-option label="使用物模型边界" value="MODEL" /><el-option
            label="显式设置边界"
            value="EXPLICIT" /></el-select
      ></el-form-item>
      <template v-if="scaleMode === 'EXPLICIT'">
        <el-form-item label="最小值"
          ><el-input v-model="minimum" aria-label="绑定仪表最小值"
        /></el-form-item>
        <el-form-item label="最大值"
          ><el-input v-model="maximum" aria-label="绑定仪表最大值"
        /></el-form-item>
      </template>
      <p v-if="!validGauge"
        >量程边界须完整、最小值小于最大值，且符合配置数值范围与15位有效数字限制。</p
      >
    </template>
    <el-form-item v-if="kind === 'JSON_VIEW'" label="初始展开层数"
      ><el-input-number
        v-model="initialExpandDepth"
        :min="0"
        :max="2"
        :precision="0"
        aria-label="绑定初始展开层数"
    /></el-form-item>
    <el-form-item v-if="kind === 'TABLE'" label="每页行数"
      ><el-input-number
        v-model="rowLimit"
        :min="1"
        :max="256"
        :precision="0"
        aria-label="绑定每页行数"
    /></el-form-item>
    <ElAlert
      v-if="kind === 'TABLE' || kind === 'JSON_VIEW'"
      class="console-hint"
      type="info"
      show-icon
      :closable="false"
      >只读取完整顶层属性，不支持嵌套路径。表格分页和展开不改变原始值。</ElAlert
    >
    <p v-if="!deviceId || !model">请先选择设备并加载其已发布物模型。</p>
    <p v-else-if="kind !== 'STATUS' && !supportedProperties.length"
      >该模型没有适用于当前组件的顶层属性。</p
    >
    <div class="console-actions">
      <el-button type="primary" :loading="busy" :disabled="!ready" @click="add(false)"
        >添加设备组件</el-button
      >
      <el-button v-if="canRebind" :disabled="!ready" @click="add(true)">替换选中组件绑定</el-button>
    </div>
  </el-form>
</template>
<script setup lang="ts">
  import { computed, ref, watch } from 'vue'
  import { gaugePosition, type DashboardSchemaV1 } from '@things-link/client-contracts/dashboard/v1'
  import {
    parseGaugeBounds,
    type DeviceBindingProperty,
    type DeviceComponentInput
  } from '@/features/dashboard/designer-model'
  const props = defineProps<{
    schema?: DashboardSchemaV1
    deviceId: string
    model: DeviceComponentInput['model'] | null
    properties: readonly DeviceBindingProperty[]
    busy?: boolean
    error?: string
    disabled?: boolean
    canRebind?: boolean
    selected?: DashboardSchemaV1['pages'][number]['components'][number] | null
  }>()
  const emit = defineEmits<{
    add: [input: DeviceComponentInput]
    rebind: [input: DeviceComponentInput]
  }>()
  const variableKey = ref('')
  const existingVariables = computed(
    () =>
      props.schema?.variables.filter(
        (v) =>
          v.type === 'DEVICE_SINGLE' &&
          props.schema?.models.some(
            (m) => m.key === v.modelKey && m.versionId === props.model?.versionId
          )
      ) ?? []
  )
  const kind = ref<DeviceComponentInput['kind']>('VALUE_CARD'),
    title = ref('设备属性'),
    propertyKey = ref('')
  const precision = ref(2),
    unitMode = ref<'MODEL' | 'NONE'>('MODEL'),
    showLastOnlineAt = ref(true),
    scaleMode = ref<'MODEL' | 'EXPLICIT'>('MODEL'),
    minimum = ref('0'),
    maximum = ref('100'),
    initialExpandDepth = ref(1),
    rowLimit = ref(20)
  const supportedProperties = computed(() =>
    props.properties.filter((property) =>
      (kind.value === 'GAUGE'
        ? ['NUMBER']
        : kind.value === 'JSON_VIEW'
          ? ['OBJECT', 'LIST']
          : kind.value === 'TABLE'
            ? ['LIST']
            : ['NUMBER', 'TEXT', 'SWITCH', 'ENUM']
      ).includes(property.dataType)
    )
  )
  const property = computed(() =>
    supportedProperties.value.find((entry) => entry.key === propertyKey.value)
  )
  const explicitBounds = computed(() => parseGaugeBounds(minimum.value, maximum.value))
  const validGauge = computed(() =>
    scaleMode.value === 'EXPLICIT'
      ? !!explicitBounds.value
      : !!property.value?.minimumValue &&
        !!gaugePosition(
          property.value.minimumValue,
          property.value.minimumValue,
          property.value.maximumValue ?? null
        )
  )
  watch(
    () => [props.deviceId, props.model, props.properties],
    () => {
      propertyKey.value = ''
      variableKey.value = ''
    }
  )
  watch(
    () => props.selected,
    (selected) => {
      if (
        !selected ||
        !['VALUE_CARD', 'STATUS', 'GAUGE', 'JSON_VIEW', 'TABLE'].includes(selected.kind)
      )
        return
      kind.value = selected.kind as DeviceComponentInput['kind']
      const values = selected.props as Record<string, unknown>
      title.value = typeof values.title === 'string' ? values.title : '设备属性'
      precision.value = typeof values.precision === 'number' ? values.precision : 2
      unitMode.value = values.unitMode === 'NONE' ? 'NONE' : 'MODEL'
      showLastOnlineAt.value = values.showLastOnlineAt !== false
      scaleMode.value = values.scaleMode === 'EXPLICIT' ? 'EXPLICIT' : 'MODEL'
      minimum.value = String(values.min ?? 0)
      maximum.value = String(values.max ?? 100)
      initialExpandDepth.value =
        typeof values.initialExpandDepth === 'number' ? values.initialExpandDepth : 1
      rowLimit.value = typeof values.rowLimit === 'number' ? values.rowLimit : 20
    },
    { immediate: true }
  )
  const ready = computed(
    () =>
      !props.disabled &&
      !props.busy &&
      !props.error &&
      !!props.deviceId &&
      !!props.model &&
      !!title.value.trim() &&
      (kind.value === 'STATUS' || !!property.value) &&
      (kind.value !== 'GAUGE' || validGauge.value) &&
      ((kind.value !== 'GAUGE' && kind.value !== 'VALUE_CARD') ||
        (Number.isInteger(precision.value) && precision.value >= 0 && precision.value <= 6)) &&
      (kind.value !== 'JSON_VIEW' ||
        (Number.isInteger(initialExpandDepth.value) &&
          initialExpandDepth.value >= 0 &&
          initialExpandDepth.value <= 2)) &&
      (kind.value !== 'TABLE' ||
        (Number.isInteger(rowLimit.value) && rowLimit.value >= 1 && rowLimit.value <= 256))
  )
  function add(rebind: boolean) {
    if (!ready.value || !props.model || (rebind && !props.canRebind)) return
    const componentProps =
      kind.value === 'VALUE_CARD'
        ? { precision: precision.value, unitMode: unitMode.value }
        : kind.value === 'STATUS'
          ? { showLastOnlineAt: showLastOnlineAt.value }
          : kind.value === 'GAUGE'
            ? {
                precision: precision.value,
                unitMode: unitMode.value,
                scaleMode: scaleMode.value,
                ...(scaleMode.value === 'EXPLICIT' ? explicitBounds.value : {})
              }
            : kind.value === 'JSON_VIEW'
              ? { initialExpandDepth: initialExpandDepth.value }
              : { mode: 'LIST_VALUE', rowLimit: rowLimit.value }
    const input: DeviceComponentInput = {
      kind: kind.value,
      title: title.value.trim(),
      deviceId: props.deviceId,
      model: props.model,
      componentProps,
      ...(variableKey.value ? { variableKey: variableKey.value } : {}),
      ...(kind.value === 'STATUS'
        ? {}
        : { propertyKey: propertyKey.value, propertyMetadata: property.value })
    }
    if (rebind) emit('rebind', input)
    else emit('add', input)
  }
</script>
