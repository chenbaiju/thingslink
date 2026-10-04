<template>
  <ElTag :type="presentation.type" effect="light">{{ presentation.label }}</ElTag>
</template>

<script setup lang="ts">
  import { computed } from 'vue'
  const props = defineProps<{ status?: string }>()
  const states: Record<string, { label: string; type: 'info' | 'success' | 'warning' | 'danger' }> =
    {
      DRAFT: { label: '草稿', type: 'info' },
      ACTIVE: { label: '已启用', type: 'success' },
      PAUSED: { label: '已暂停', type: 'warning' },
      REVOKED: { label: '已撤销', type: 'danger' }
    }
  const presentation = computed(
    () => states[props.status ?? ''] ?? { label: props.status || '—', type: 'info' as const }
  )
</script>
