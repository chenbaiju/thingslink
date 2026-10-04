<script setup lang="ts">
  import ArtButtonTable from '@/components/core/forms/art-button-table/index.vue'
  defineOptions({ inheritAttrs: false })
  const props = withDefaults(
    defineProps<{
      label: string
      icon: string
      type?: string
    }>(),
    { type: 'primary' }
  )
  const tone = computed(() =>
    ['danger', 'warning', 'success', 'info'].includes(props.type) ? props.type : 'primary'
  )
</script>
<template>
  <ArtButtonTable
    v-bind="$attrs"
    :icon="icon"
    :label="label"
    :icon-color="`var(--el-color-${tone})`"
    :button-bg-color="`var(--el-color-${tone}-light-9)`"
    class="console-table-action"
  />
</template>
