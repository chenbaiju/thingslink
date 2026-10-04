<template>
  <section v-if="available && visible && result.entries.length" aria-label="动态文本预览">
    <p>文本选项仅影响当前预览，保存的默认值保持不变。</p>
    <label v-for="variable in result.variables" :key="variable.key">
      {{ variable.title }}
      <select
        :aria-label="`预览文本 ${variable.title}`"
        :value="selection(variable.key)"
        @change="change(variable.key, ($event.target as HTMLSelectElement).value)"
      >
        <option value="">{{ variable.required ? '请选择文本选项' : '未选择文本选项' }}</option>
        <option v-for="option in variable.options" :key="option.value" :value="option.value">{{
          option.label
        }}</option>
      </select>
    </label>
    <p
      v-for="entry in result.entries"
      :key="entry.id"
      :data-preview-text="entry.id"
      :data-state="entry.state"
      class="text-preview"
      :class="[
        `align-${entry.align.toLowerCase()}`,
        `size-${entry.size.toLowerCase()}`,
        `tone-${entry.tone.toLowerCase()}`
      ]"
      >{{ entry.text }}</p
    >
  </section>
</template>
<script setup lang="ts">
  import { computed, onBeforeUnmount, ref, shallowRef, watch } from 'vue'
  import type { DashboardSchemaV1 } from '@things-link/client-contracts/dashboard/v1'
  import { textPreview, type TextSelections } from '@/features/dashboard/text-preview'
  const props = defineProps<{
    schema: DashboardSchemaV1
    pageId: string
    projectId: string
    available: boolean
  }>()
  const selections = shallowRef<TextSelections>({})
  const visible = ref(!document.hidden)
  const result = computed(() => textPreview(props.schema, props.pageId, selections.value))
  function selection(key: string) {
    return Object.hasOwn(selections.value, key)
      ? (selections.value[key] ?? '')
      : (result.value.variables.find((variable) => variable.key === key)?.defaultValue ?? '')
  }
  function change(key: string, value: string) {
    if (!props.available || !visible.value) return
    const variable = result.value.variables.find((variable) => variable.key === key)
    if (!variable || (value !== '' && !variable.options.some((option) => option.value === value)))
      return
    selections.value = { ...selections.value, [key]: value || null }
  }
  watch(
    () => [props.schema, props.pageId, props.projectId, props.available],
    () => {
      selections.value = {}
    },
    { flush: 'sync' }
  )
  const visibility = () => {
    visible.value = !document.hidden
    if (!visible.value) selections.value = {}
  }
  document.addEventListener('visibilitychange', visibility)
  onBeforeUnmount(() => document.removeEventListener('visibilitychange', visibility))
</script>
<style scoped>
  select {
    width: 100%;
    max-width: 320px;
  }
  .text-preview {
    overflow-wrap: anywhere;
    white-space: pre-wrap;
  }
  .align-left {
    text-align: left;
  }
  .align-center {
    text-align: center;
  }
  .align-right {
    text-align: right;
  }
  .size-small {
    font-size: 14px;
  }
  .size-medium {
    font-size: 18px;
  }
  .size-large {
    font-size: 28px;
  }
  .tone-regular {
    color: var(--el-text-color-primary);
  }
  .tone-secondary {
    color: var(--el-text-color-secondary);
  }
  .tone-primary {
    color: var(--el-color-primary);
  }
</style>
