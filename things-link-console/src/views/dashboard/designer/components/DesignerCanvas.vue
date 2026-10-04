<script setup lang="ts">
  import { computed, onBeforeUnmount, ref, watch } from 'vue'
  import type { CSSProperties } from 'vue'
  import type { DashboardSchemaV1 } from '@things-link/client-contracts/dashboard/v1'
  import deviceMark from '@/assets/images/dashboard/device-mark.png'

  const dataLabels: Record<string, string> = {
    VALUE_CARD: '设备属性值',
    STATUS: '设备状态',
    GAUGE: '数值仪表',
    JSON_VIEW: '完整JSON',
    TABLE: '完整列表'
  }
  type Layout = { x: number; y: number; w: number; h: number }
  const props = defineProps<{
    schema: DashboardSchemaV1
    pageId: string
    selectedId: string | null
    readonly: boolean
  }>()
  const emit = defineEmits<{ select: [id: string]; layout: [id: string, layout: Layout] }>()
  const viewport = ref<HTMLElement>()
  const width = ref(0)
  const height = ref(480)
  const ghost = ref<{ id: string; layout: Layout }>()
  let observer: ResizeObserver | undefined
  let cleanup: (() => void) | undefined
  const fixed = computed(() => props.schema.presentation.mode === 'FIXED_SCREEN')
  const page = computed(() => props.schema.pages.find((entry) => entry.id === props.pageId))
  const scale = computed(() => Math.min(width.value / 1920, height.value / 1080))
  const narrow = computed(() => !fixed.value && width.value < 768)
  watch(
    viewport,
    (element) => {
      observer?.disconnect()
      if (!element) return
      const measure = () => {
        width.value = element.clientWidth
        height.value = element.clientHeight
      }
      observer = new ResizeObserver(measure)
      observer.observe(element)
      measure()
    },
    { flush: 'post' }
  )
  watch(
    () => [props.schema, props.pageId, props.readonly],
    () => {
      cleanup?.()
      ghost.value = undefined
    }
  )
  onBeforeUnmount(() => {
    observer?.disconnect()
    cleanup?.()
  })

  function style(id: string, original: Layout): CSSProperties {
    const layout = ghost.value?.id === id ? ghost.value.layout : original
    if (fixed.value)
      return {
        position: 'absolute',
        left: `${layout.x}px`,
        top: `${layout.y}px`,
        width: `${layout.w}px`,
        height: `${layout.h}px`
      }
    if (narrow.value) return { minHeight: `${layout.h * 16 - 8}px` }
    return {
      gridColumn: `${layout.x + 1} / span ${layout.w}`,
      gridRow: `${layout.y + 1} / span ${layout.h}`
    }
  }
  function drag(event: PointerEvent, id: string, layout: Layout, resize = false) {
    emit('select', id)
    if (props.readonly || narrow.value || event.button !== 0 || !viewport.value) return
    event.preventDefault()
    cleanup?.()
    const original = { ...layout }
    const startX = event.clientX
    const startY = event.clientY
    const stepX = fixed.value ? scale.value : (width.value + 8) / 24
    const stepY = fixed.value ? scale.value : 16
    if (stepX <= 0 || stepY <= 0) return
    const maxX = fixed.value ? 1920 : 24
    const maxY = fixed.value ? 1080 : 1000
    const move = (next: PointerEvent) => {
      const dx = Math.round((next.clientX - startX) / stepX)
      const dy = Math.round((next.clientY - startY) / stepY)
      ghost.value = {
        id,
        layout: resize
          ? {
              ...original,
              w: Math.max(1, Math.min(maxX - original.x, original.w + dx)),
              h: Math.max(1, Math.min(maxY - original.y, original.h + dy))
            }
          : {
              ...original,
              x: Math.max(0, Math.min(maxX - original.w, original.x + dx)),
              y: Math.max(0, Math.min(maxY - original.h, original.y + dy))
            }
      }
    }
    cleanup = () => {
      window.removeEventListener('pointermove', move)
      window.removeEventListener('pointerup', finish)
      window.removeEventListener('pointercancel', cancel)
    }
    const cancel = () => {
      cleanup?.()
      cleanup = undefined
      ghost.value = undefined
    }
    const finish = () => {
      const result = ghost.value?.layout
      cancel()
      if (result) emit('layout', id, result)
    }
    window.addEventListener('pointermove', move)
    window.addEventListener('pointerup', finish, { once: true })
    window.addEventListener('pointercancel', cancel, { once: true })
  }
</script>

<template>
  <div class="canvas-shell" :class="{ dark: schema.presentation.theme === 'DARK' }">
    <p class="canvas-caption"
      >草稿画布 · {{ fixed ? '1920 × 1080 等比适配' : '24 列响应式网格'
      }}<span v-if="narrow"> · 窄屏按组件顺序排列，请用属性面板调整位置</span></p
    >
    <div ref="viewport" class="canvas-viewport" :class="{ fixed }" data-testid="designer-canvas">
      <div
        class="canvas"
        :class="{ 'fixed-canvas': fixed, grid: !fixed && !narrow, single: narrow }"
        :style="fixed ? { transform: `translate(-50%, -50%) scale(${scale})` } : undefined"
      >
        <article
          v-for="component in page?.components"
          :key="component.id"
          tabindex="0"
          class="canvas-component"
          :class="{ selected: selectedId === component.id }"
          :data-testid="`designer-component-${component.id}`"
          :data-component-id="component.id"
          :data-kind="component.kind"
          :style="style(component.id, component.layout)"
          @click="emit('select', component.id)"
          @keydown.enter="emit('select', component.id)"
          @pointerdown="drag($event, component.id, component.layout)"
        >
          <p
            v-if="component.kind === 'TEXT' && 'content' in component.props"
            class="text"
            :class="[
              `size-${component.props.size.toLowerCase()}`,
              `tone-${component.props.tone.toLowerCase()}`,
              `align-${component.props.align.toLowerCase()}`
            ]"
            >{{ component.props.content }}</p
          >
          <template
            v-else-if="
              component.kind === 'IMAGE' &&
              component.props.resourceId === 'device_mark' &&
              component.props.resourceDigest ===
                '5977f591d5eeb691ee85c7656468a8b5dd1378b70b02a55af574331a0f0f0eaa'
            "
          >
            <strong v-if="component.props.title">{{ component.props.title }}</strong>
            <img
              :src="deviceMark"
              :alt="component.props.alt"
              draggable="false"
              :style="{ objectFit: component.props.fit === 'COVER' ? 'cover' : 'contain' }"
            />
          </template>
          <div
            v-else-if="
              ['VALUE_CARD', 'STATUS', 'GAUGE', 'JSON_VIEW'].includes(component.kind) ||
              (component.kind === 'TABLE' && component.props.mode === 'LIST_VALUE')
            "
          >
            <strong>{{ 'title' in component.props ? component.props.title : component.id }}</strong>
            <p>{{ dataLabels[component.kind] }}</p>
            <small>实际数据见草稿快照预览</small>
          </div>
          <p v-else class="unsupported"
            >{{ component.kind }} · 此组件暂不支持编辑，原定义完整保留</p
          >
          <button
            v-if="!readonly && !narrow && selectedId === component.id"
            class="resize-handle"
            type="button"
            aria-label="拖动调整组件大小"
            :data-testid="`designer-resize-${component.id}`"
            @pointerdown.stop="drag($event, component.id, component.layout, true)"
            >↘</button
          >
        </article>
      </div>
      <p v-if="!page?.components.length" class="canvas-empty">空白画布，从左侧添加文本或内置图片</p>
    </div>
  </div>
</template>

<style scoped>
  .canvas-shell {
    --canvas-text: #24354b;
    --canvas-muted: #6c7b90;
    min-width: 0;
    padding: 12px;
    color: var(--canvas-text);
    background: #fff;
    border: 1px solid var(--el-border-color);
    border-radius: 8px;
  }
  .dark {
    --canvas-text: #eef3fb;
    --canvas-muted: #a2b1c6;
    background: #182232;
  }
  .canvas-caption {
    margin: 0 0 12px;
    font-size: 12px;
    color: var(--canvas-muted);
  }
  .canvas-viewport {
    position: relative;
    min-height: 360px;
    overflow: auto;
  }
  .canvas-viewport.fixed {
    height: 480px;
    overflow: hidden;
  }
  .canvas {
    min-width: 0;
  }
  .fixed-canvas {
    position: absolute;
    top: 50%;
    left: 50%;
    width: 1920px;
    height: 1080px;
    transform-origin: center;
  }
  .grid {
    display: grid;
    grid-template-columns: repeat(24, minmax(0, 1fr));
    grid-auto-rows: 8px;
    gap: 8px;
  }
  .single {
    display: flex;
    flex-direction: column;
    gap: 8px;
  }
  .canvas-component {
    position: relative;
    box-sizing: border-box;
    min-width: 0;
    padding: 8px;
    overflow: auto;
    touch-action: none;
    cursor: pointer;
    border: 1px dashed var(--el-border-color);
  }
  .canvas-component.selected {
    outline: 2px solid var(--el-color-primary);
    outline-offset: -2px;
  }
  .canvas-component img {
    display: block;
    width: 100%;
    height: calc(100% - 4px);
    pointer-events: none;
  }
  .text {
    margin: 0;
    overflow-wrap: anywhere;
    white-space: pre-wrap;
  }
  .size-small {
    font-size: 14px;
  }
  .size-medium {
    font-size: 20px;
  }
  .size-large {
    font-size: 32px;
  }
  .tone-secondary {
    color: var(--canvas-muted);
  }
  .tone-primary {
    color: var(--el-color-primary);
  }
  .align-center {
    text-align: center;
  }
  .align-right {
    text-align: right;
  }
  .resize-handle {
    position: absolute;
    right: 0;
    bottom: 0;
    width: 24px;
    height: 24px;
    color: white;
    cursor: nwse-resize;
    background: var(--el-color-primary);
    border: 0;
  }
  .unsupported,
  .canvas-empty {
    font-size: 13px;
    color: var(--canvas-muted);
    overflow-wrap: anywhere;
  }
  .canvas-empty {
    padding: 64px 12px;
    text-align: center;
  }
</style>
