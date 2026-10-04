<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { serializeRuntimeValue, type RuntimeValue } from './composite-value'
import { initiallyExpanded, runtimeChildren, runtimeContainer } from './json-presentation'

const props = withDefaults(defineProps<{ value: RuntimeValue; initialExpandDepth: number; depth?: number }>(), { depth: 0 })
const expanded = ref(initiallyExpanded(props.depth, props.initialExpandDepth))
watch([() => props.value, () => props.initialExpandDepth, () => props.depth], () => {
  expanded.value = initiallyExpanded(props.depth, props.initialExpandDepth)
})
const children = computed(() => runtimeChildren(props.value))
const container = computed(() => runtimeContainer(props.value))
function toggle(event: Event) { expanded.value = (event.target as HTMLDetailsElement).open }
</script>

<template>
  <details v-if="container && depth < 8" class="runtime-json-node" :data-json-depth="depth" :open="expanded" @toggle="toggle">
    <summary>{{ Array.isArray(value) ? `数组 · ${children.length}项` : `对象 · ${children.length}个字段` }}</summary>
    <div v-if="expanded" class="runtime-json-children">
      <p v-if="children.length === 0" class="runtime-json-empty">{{ Array.isArray(value) ? '[]' : '{}' }}</p>
      <div v-for="entry in children" :key="entry.label" class="runtime-json-entry">
        <span class="runtime-json-key">{{ entry.label }}</span>
        <RuntimeJsonTree :value="entry.value" :initial-expand-depth="initialExpandDepth" :depth="depth + 1" />
      </div>
    </div>
  </details>
  <pre v-else class="runtime-json-scalar">{{ serializeRuntimeValue(value) }}</pre>
</template>

<style scoped>
.runtime-json-node { min-width: 0; max-width: 100%; }
.runtime-json-node summary { cursor: pointer; overflow-wrap: anywhere; }
.runtime-json-children { border-left: 1px solid currentColor; padding-left: 8px; margin-left: 4px; }
.runtime-json-entry { margin-top: 6px; min-width: 0; }
.runtime-json-key { display: block; font-weight: 600; overflow-wrap: anywhere; white-space: pre-wrap; }
.runtime-json-scalar, .runtime-json-empty { margin: 0; white-space: pre-wrap; overflow-wrap: anywhere; font: inherit; max-width: 100%; }
</style>
