<script setup lang="ts">
  import { ref, watch, onBeforeUnmount } from 'vue'
  import {
    renameManagementResource,
    validManagementName,
    type ManagementKind,
    type ManagementCatalog
  } from '@/api/management-rename'
  const props = defineProps<{
    target: { id: string; managementName: string } | null
    kind: ManagementKind
    projectId: string
    identity: string
    allowed: boolean
  }>()
  const emit = defineEmits<{ close: []; renamed: [result: ManagementCatalog] }>()
  const name = ref(''),
    error = ref(''),
    busy = ref(false)
  let generation = 0
  onBeforeUnmount(() => {
    generation++
  })
  watch(
    () => [props.target, props.identity, props.projectId, props.allowed],
    () => {
      generation++
      busy.value = false
      error.value = ''
      name.value = props.target?.managementName ?? ''
    },
    { immediate: true, flush: 'sync' }
  )
  async function submit() {
    if (!props.allowed || !props.target || busy.value) return
    if (!validManagementName(name.value)) {
      error.value = '请输入 1 至 80 个码点的非空白名称，不得含控制字符。'
      return
    }
    const request = generation
    busy.value = true
    error.value = ''
    try {
      const result = await renameManagementResource(
        props.kind,
        props.projectId,
        props.target.id,
        name.value
      )
      if (request !== generation || !props.allowed) return
      emit('renamed', result)
      emit('close')
    } catch (cause) {
      if (request === generation)
        error.value = cause instanceof Error ? cause.message : '重命名未完成；输入已保留。'
    } finally {
      if (request === generation) busy.value = false
    }
  }
</script>
<template>
  <ElDialog
    class="console-dialog"
    :model-value="!!target && allowed"
    title="重命名管理名称"
    width="min(480px, 92vw)"
    :close-on-click-modal="false"
    :close-on-press-escape="!busy"
    :show-close="!busy"
    @close="emit('close')"
  >
    <ElAlert class="console-hint" type="info" show-icon :closable="false"
      >仅修改目录中的管理名称，草稿内容、草稿修订和已发布版本保持不变。</ElAlert
    >
    <ElInput v-model="name" aria-label="新的管理名称" :disabled="busy" @keyup.enter="submit" />
    <ElAlert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <template #footer>
      <ElButton :disabled="busy" @click="emit('close')">取消</ElButton>
      <ElButton type="primary" :loading="busy" :disabled="busy || !allowed" @click="submit"
        >确认重命名</ElButton
      >
    </template>
  </ElDialog>
</template>
