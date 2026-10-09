<script setup lang="ts">
  import { computed, ref, watch } from 'vue'
  import type { DashboardSchemaV1 } from '@things-link/client-contracts/dashboard/v1'
  import type { AlarmComponentInput } from '@/features/dashboard/designer-model'
  const props = defineProps<{
    schema: DashboardSchemaV1
    selected?: DashboardSchemaV1['pages'][number]['components'][number] | null
    disabled: boolean
  }>()
  const emit = defineEmits<{
    add: [input: AlarmComponentInput]
    rebind: [input: AlarmComponentInput]
  }>()
  const conditions: AlarmComponentInput['conditionStates'] = ['PENDING', 'ACTIVE', 'CLEARED']
  const acknowledgments: AlarmComponentInput['ackStates'] = ['UNACKNOWLEDGED', 'ACKNOWLEDGED']
  const levels: AlarmComponentInput['severities'] = [
    'CRITICAL',
    'MAJOR',
    'MINOR',
    'WARNING',
    'INFO'
  ]
  const title = ref('告警列表'),
    variableKey = ref(''),
    pageSize = ref(20),
    showClearedAt = ref(true)
  const conditionStates = ref<AlarmComponentInput['conditionStates']>(['ACTIVE']),
    ackStates = ref<AlarmComponentInput['ackStates']>(['UNACKNOWLEDGED']),
    severities = ref<AlarmComponentInput['severities']>([...levels])
  const variables = computed(() =>
    props.schema.variables.filter((v) => v.type === 'DEVICE_SINGLE' || v.type === 'DEVICE_MULTI')
  )
  watch(
    () => props.selected,
    (selected) => {
      if (selected?.kind !== 'ALARM_LIST') return
      title.value = selected.props.title ?? '告警列表'
      pageSize.value = selected.props.pageSize
      showClearedAt.value = selected.props.showClearedAt
      variableKey.value = selected.bindings.alarms.devices.variableKey
      conditionStates.value = [...selected.bindings.alarms.conditionStates]
      ackStates.value = [...selected.bindings.alarms.ackStates]
      severities.value = [...selected.bindings.alarms.severities]
    },
    { immediate: true }
  )
  const ready = computed(
    () =>
      !props.disabled &&
      !!title.value.trim() &&
      variables.value.some((v) => v.key === variableKey.value) &&
      Number.isInteger(pageSize.value) &&
      pageSize.value >= 1 &&
      pageSize.value <= 50 &&
      conditionStates.value.length > 0 &&
      ackStates.value.length > 0 &&
      severities.value.length > 0
  )
  function add(rebind = false) {
    if (!ready.value || (rebind && props.selected?.kind !== 'ALARM_LIST')) return
    const input: AlarmComponentInput = {
      title: title.value,
      deviceVariableKey: variableKey.value,
      pageSize: pageSize.value,
      showClearedAt: showClearedAt.value,
      conditionStates: [...conditionStates.value],
      ackStates: [...ackStates.value],
      severities: [...severities.value]
    }
    if (rebind) emit('rebind', input)
    else emit('add', input)
  }
</script>
<template>
  <section class="console-fragment" aria-label="告警列表编辑"
    ><ElDivider content-position="left">告警列表</ElDivider
    ><ElAlert class="console-hint" type="info" show-icon :closable="false"
      >按设备变量与过滤条件读取告警；确认状态不等于个人已读。本组件不确认或清除告警。</ElAlert
    >
    <fieldset :disabled="disabled">
      <label>告警标题<input v-model="title" aria-label="告警标题" maxlength="80" /></label>
      <label
        >告警设备变量<select v-model="variableKey" aria-label="告警设备变量"
          ><option value="">请选择单设备或多设备变量</option
          ><option v-for="variable in variables" :key="variable.key" :value="variable.key"
            >{{ variable.title }} · {{ variable.key }}</option
          ></select
        ></label
      >
      <label
        >告警每页数量<input
          v-model.number="pageSize"
          type="number"
          min="1"
          max="50"
          aria-label="告警每页数量"
      /></label>
      <label
        ><input
          v-model="showClearedAt"
          type="checkbox"
          aria-label="显示恢复时间"
        />显示恢复时间</label
      >
      <fieldset
        ><legend>条件状态（至少一项）</legend
        ><label v-for="value in conditions" :key="value"
          ><input
            v-model="conditionStates"
            type="checkbox"
            :value="value"
            :aria-label="`条件 ${value}`"
          />{{ value }}</label
        ></fieldset
      >
      <fieldset
        ><legend>确认状态（至少一项）</legend
        ><label v-for="value in acknowledgments" :key="value"
          ><input
            v-model="ackStates"
            type="checkbox"
            :value="value"
            :aria-label="`确认 ${value}`"
          />{{ value }}</label
        ></fieldset
      >
      <fieldset
        ><legend>告警等级（至少一项）</legend
        ><label v-for="value in levels" :key="value"
          ><input
            v-model="severities"
            type="checkbox"
            :value="value"
            :aria-label="`等级 ${value}`"
          />{{ value }}</label
        ></fieldset
      >
      <div class="console-actions">
        <button :disabled="!ready" data-testid="alarm-component-add" @click="add()"
          >添加告警列表</button
        ><button
          :disabled="!ready || selected?.kind !== 'ALARM_LIST'"
          data-testid="alarm-component-rebind"
          @click="add(true)"
          >替换选中告警列表</button
        >
      </div>
    </fieldset></section
  >
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
