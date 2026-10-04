<template>
  <section class="console-fragment">
    <p class="console-description"
      >按顺序执行。选择节点后填写参数，复杂值使用 JSON；保存不会自动发布。</p
    >
    <div class="console-toolbar">
      <ElSelect v-model="newType" placeholder="选择节点" aria-label="选择节点" :disabled="disabled">
        <ElOption
          v-for="node in descriptors"
          :key="node.nodeType"
          :value="node.nodeType ?? ''"
          :label="node.nodeType"
        />
      </ElSelect>
      <ElButton :disabled="disabled || !newType || modelValue.length >= 32" @click="add"
        >添加节点</ElButton
      >
    </div>
    <ElCard
      v-for="(node, index) in modelValue"
      :key="`${revision}-${index}`"
      class="node-card"
      shadow="never"
      :data-testid="`node-${index}`"
    >
      <template #header>
        {{ index + 1 }}. {{ node.nodeType }}
        <div class="console-actions">
          <ElButton :disabled="disabled || index === 0" @click="move(index, -1)">上移</ElButton>
          <ElButton :disabled="disabled || index === modelValue.length - 1" @click="move(index, 1)"
            >下移</ElButton
          >
          <ElButton :disabled="disabled" @click="remove(index)">删除节点</ElButton>
        </div>
      </template>
      <template v-if="editable(node)">
        <ElForm label-position="top">
          <ElFormItem
            v-for="(field, key) in schema(node).properties"
            :key="key"
            :label="`${key}${schema(node).required?.includes(String(key)) ? ' *' : ''}`"
            :error="errors[`${index}:${key}`]"
          >
            <ElSelect
              v-if="field.enum"
              :model-value="text(node, String(key), field)"
              :disabled="disabled"
              :aria-label="String(key)"
              @update:model-value="(value) => update(index, String(key), String(value), field)"
            >
              <ElOption
                v-for="option in field.enum"
                :key="option"
                :value="option"
                :label="option"
              />
            </ElSelect>
            <ElInput
              v-else
              :model-value="text(node, String(key), field)"
              :type="field.type === 'string' ? 'text' : 'textarea'"
              :aria-label="String(key)"
              :maxlength="field.maxLength"
              :disabled="disabled"
              @input="(value) => update(index, String(key), value, field)"
            />
          </ElFormItem>
        </ElForm>
      </template>
      <template v-else
        ><ElAlert
          title="未知节点或字段：历史配置只读，请显式重建合规版本。"
          type="warning"
          :closable="false"
        /><pre>{{ JSON.stringify(node.config, null, 2) }}</pre>
      </template>
    </ElCard>
  </section>
</template>
<script setup lang="ts">
  import { computed, ref, watch } from 'vue'
  import type { RuleAction, RuleDescriptor } from '@/api/rule-management'
  import {
    canEditConfig,
    fieldValue,
    remapNodeFields,
    type ConfigObject,
    type ConfigSchema,
    type FieldSchema
  } from './node-config'
  const props = defineProps<{
    modelValue: RuleAction[]
    descriptors: RuleDescriptor[]
    disabled?: boolean
  }>()
  const emit = defineEmits<{ 'update:modelValue': [RuleAction[]]; valid: [boolean] }>()
  const newType = ref('')
  const errors = ref<Record<string, string>>({})
  const raw = ref<Record<string, string>>({})
  const revision = ref(0)
  const schema = (node: RuleAction): ConfigSchema =>
    (props.descriptors.find((item) => item.nodeType === node.nodeType)?.configSchema ??
      {}) as ConfigSchema
  const editable = (node: RuleAction) =>
    props.descriptors.some((item) => item.nodeType === node.nodeType) &&
    canEditConfig(node.config, schema(node))
  const valid = computed(
    () =>
      Object.keys(errors.value).length === 0 &&
      props.modelValue.length <= 32 &&
      props.modelValue.every(editable)
  )
  watch(valid, (value) => emit('valid', value), { immediate: true })
  function text(node: RuleAction, key: string, field: FieldSchema): string {
    const index = props.modelValue.indexOf(node)
    if (Object.hasOwn(raw.value, `${index}:${key}`)) return raw.value[`${index}:${key}`]
    const value = (node.config as ConfigObject)?.[key]
    return value === undefined
      ? ''
      : field.type === 'string' || field.enum
        ? String(value)
        : JSON.stringify(value)
  }
  function update(index: number, key: string, value: string, field: FieldSchema) {
    raw.value[`${index}:${key}`] = value
    try {
      const parsed = fieldValue(value, field)
      const items = JSON.parse(JSON.stringify(props.modelValue)) as RuleAction[]
      const config = { ...(items[index].config as ConfigObject) }
      if (parsed === undefined) delete config[key]
      else config[key] = parsed
      items[index].config = config
      delete errors.value[`${index}:${key}`]
      emit('update:modelValue', items)
    } catch {
      errors.value[`${index}:${key}`] = '请输入合法 JSON，字符串请加双引号'
    }
  }
  function changed(order: number[], items = order.map((index) => props.modelValue[index])) {
    errors.value = remapNodeFields(errors.value, order)
    raw.value = remapNodeFields(raw.value, order)
    revision.value++
    emit('update:modelValue', items)
  }
  function add() {
    changed(
      props.modelValue.map((_, i) => i),
      [...props.modelValue, { nodeType: newType.value, config: {} }]
    )
  }
  function remove(index: number) {
    changed(props.modelValue.map((_, i) => i).filter((i) => i !== index))
  }
  function move(index: number, delta: number) {
    const order = props.modelValue.map((_, i) => i)
    ;[order[index], order[index + delta]] = [order[index + delta], order[index]]
    changed(order)
  }
</script>
<style scoped>
  .node-card {
    margin-top: 12px;
  }
</style>
