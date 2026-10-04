<script setup lang="ts">
  import { inject, ref, shallowRef, watch, onBeforeUnmount } from 'vue'
  import { previewInteractionKey } from '@/features/dashboard/preview-interaction'
  import {
    fetchDesignerDeviceCatalog,
    createDesignerReadScope,
    type DesignerDeviceCatalog
  } from '@/api/dashboard-binding'
  const props = defineProps<{
    readGeneration?: number
    projectId: string
    modelVersionId: string
    variableKey: string
    title: string
    placeholder?: string
    multiple: boolean
    maxItems: number
    pageSize: number
    selected: readonly string[]
    disabled: boolean
  }>()
  const interaction = inject(previewInteractionKey, null)
  const emit = defineEmits<{ change: [ids: string[]]; denied: [] }>()
  const items = shallowRef<DesignerDeviceCatalog['items']>([])
  const cursor = ref<string | null>(null),
    busy = ref(false),
    error = ref('')
  let epoch = 0
  let scope: ReturnType<typeof createDesignerReadScope> | undefined
  function clear() {
    epoch++
    scope?.close()
    scope = undefined
    items.value = []
    cursor.value = null
    busy.value = false
    error.value = ''
  }
  watch(
    () => [
      props.projectId,
      props.modelVersionId,
      props.variableKey,
      props.pageSize,
      props.disabled,
      props.readGeneration
    ],
    clear,
    { flush: 'sync' }
  )
  onBeforeUnmount(clear)
  async function load(next?: string) {
    if (props.disabled || busy.value) return
    const generation = ++epoch
    const projectId = props.projectId,
      modelVersionId = props.modelVersionId,
      pageSize = props.pageSize
    let signal: AbortSignal | undefined
    let owned: ReturnType<typeof createDesignerReadScope> | undefined
    busy.value = true
    error.value = ''
    items.value = []
    cursor.value = null
    const current = () => {
      if (generation !== epoch || props.disabled || signal?.aborted)
        throw new DOMException('目录读取已取消', 'AbortError')
    }
    const read = async (
      reading: ReturnType<typeof createDesignerReadScope>,
      cancellation?: AbortSignal
    ) => {
      signal = cancellation
      current()
      const page = await fetchDesignerDeviceCatalog(
        projectId,
        modelVersionId,
        next,
        pageSize,
        reading
      )
      current()
      return page
    }
    try {
      let page: DesignerDeviceCatalog
      if (interaction) {
        const key = JSON.stringify(['directory', projectId, modelVersionId, pageSize, next ?? null])
        page = await interaction.run(key, (reading, cancellation) => read(reading, cancellation))
      } else {
        owned = createDesignerReadScope()
        scope = owned
        page = await read(owned)
      }
      current()
      items.value = page.items
      cursor.value = page.nextCursor
    } catch (cause) {
      if (
        generation === epoch &&
        !signal?.aborted &&
        !(cause instanceof DOMException && cause.name === 'AbortError')
      ) {
        if ([401, 403, 404].includes((cause as { status?: number })?.status ?? 0)) emit('denied')
        else error.value = '设备目录读取失败，请重新读取。'
      }
    } finally {
      // 注入的scope属于全页调度器，子组件只能围栏结果，不能关闭其他目录/快照共用的读取轮次。
      owned?.close()
      if (generation === epoch) {
        busy.value = false
        scope = undefined
      }
    }
  }
  function choose(id: string) {
    if (props.disabled) return
    const selected = props.selected.includes(id)
    if (props.multiple) {
      if (!selected && props.selected.length >= props.maxItems) {
        error.value = `最多选择${props.maxItems}台设备`
        return
      }
      emit(
        'change',
        selected ? props.selected.filter((value) => value !== id) : [...props.selected, id]
      )
    } else emit('change', selected ? [] : [id])
  }
</script>
<template>
  <section
    :aria-label="`预览设备选择：${title}`"
    :data-preview-selector="variableKey"
    class="device-selector"
  >
    <strong>{{ title }}</strong
    ><p>仅改变本轮预览，草稿默认设备保持不变。</p>
    <div class="console-actions">
      <el-button :disabled="disabled || busy" @click="load()">读取可选设备</el-button>
      <el-button :disabled="disabled || busy || !cursor" @click="load(cursor!)"
        >下一页可选设备</el-button
      >
    </div>
    <p v-if="error" role="alert">{{ error }}</p>
    <p v-if="!selected.length">{{ placeholder ?? '请选择设备' }}</p>
    <p v-for="id in selected" :key="id" :data-selected-device="id">
      已选择：{{ items.find((item) => item.deviceId === id)?.name ?? id }}
      <el-button :disabled="disabled" @click="choose(id)">移除选择</el-button>
    </p>
    <ul
      ><li v-for="item in items" :key="item.deviceId">
        <label
          ><input
            type="checkbox"
            :checked="selected.includes(item.deviceId)"
            :disabled="
              disabled ||
              (!selected.includes(item.deviceId) && multiple && selected.length >= maxItems)
            "
            @change="choose(item.deviceId)"
          />{{ item.name }}</label
        >
      </li></ul
    >
  </section>
</template>
<style scoped>
  .device-selector {
    min-width: 0;
    margin: 12px 0;
    overflow-wrap: anywhere;
  }
  ul {
    padding-left: 18px;
  }
</style>
