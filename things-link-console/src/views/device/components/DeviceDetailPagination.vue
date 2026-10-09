<template>
  <div class="device-detail-pagination" role="navigation" aria-label="列表分页">
    <ElButton :disabled="loading || pageIndex === 0" @click="$emit('previous')">上一页</ElButton>
    <span>第 {{ pageIndex + 1 }} 页</span>
    <ElButton
      :data-testid="nextTestId"
      :disabled="loading || failed || !hasNext"
      @click="$emit('next')"
      >下一页</ElButton
    >
  </div>
</template>

<script setup lang="ts">
  defineProps<{
    pageIndex: number
    loading: boolean
    failed: boolean
    hasNext: boolean
    nextTestId?: string
  }>()
  defineEmits<{ previous: []; next: [] }>()
</script>
