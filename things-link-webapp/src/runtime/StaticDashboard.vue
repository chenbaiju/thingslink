<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import type { DashboardSchemaV1 } from '@things-link/client-contracts/dashboard/v1'
import { componentLayout, fixedFit, imageSource, supportsStaticDashboard } from './static-presentation'
import type { StaticImageResource } from './static-presentation'

const props = defineProps<{ schema: DashboardSchemaV1; resources: readonly StaticImageResource[] }>()
const activePage = ref(0)
const viewport = ref<HTMLElement | null>(null)
const width = ref(0)
const height = ref(0)
let observer: ResizeObserver | undefined
const supported = computed(() => supportsStaticDashboard(props.schema, props.resources))
const fixed = computed(() => props.schema.presentation.mode === 'FIXED_SCREEN')
const page = computed(() => props.schema.pages[activePage.value] ?? props.schema.pages[0])
const scale = computed(() => fixedFit(width.value, height.value))
watch(() => props.schema, () => { activePage.value = 0 })
watch(viewport, element => {
  observer?.disconnect()
  if (!element) return
  const measure = () => { width.value = element.clientWidth; height.value = element.clientHeight }
  observer = new ResizeObserver(measure)
  observer.observe(element)
  measure()
}, { flush: 'post' })
onBeforeUnmount(() => observer?.disconnect())
</script>

<template>
  <section class="static-dashboard" :class="{ 'static-dark': schema.presentation.theme === 'DARK' }" data-testid="static-dashboard" :data-layout="schema.presentation.mode">
    <p v-if="!supported" role="alert" data-testid="static-dashboard-error">此看板包含当前客户端不支持的内容，无法显示完整发布版本。</p>
    <template v-else>
      <nav v-if="schema.pages.length > 1" class="static-page-tabs" aria-label="看板页面">
        <button v-for="(entry, index) in schema.pages" :key="entry.id" type="button" :aria-current="activePage === index ? 'page' : undefined" :data-page-id="entry.id" data-testid="dashboard-page-tab" @click="activePage = index">{{ entry.title }}</button>
      </nav>
      <div ref="viewport" class="static-viewport" :class="{ 'static-fixed-viewport': fixed }">
        <div class="static-canvas" :class="{ 'static-fixed': fixed, 'static-grid': !fixed && width >= 768, 'static-single': !fixed && width < 768 }" data-testid="dashboard-canvas" :data-page-id="page.id" :style="fixed ? { transform: `translate(-50%, -50%) scale(${scale})` } : undefined">
          <article v-for="component in page.components" :key="component.id" class="static-component" :data-component-id="component.id" :data-kind="component.kind" :style="componentLayout(component, fixed, width)">
            <p v-if="component.kind === 'TEXT' && 'content' in component.props" class="static-text" data-testid="dashboard-text" :class="[`text-${component.props.size.toLowerCase()}`, `tone-${component.props.tone.toLowerCase()}`, `align-${component.props.align.toLowerCase()}`]">{{ component.props.content }}</p>
            <template v-else-if="component.kind === 'IMAGE'">
              <h3 v-if="component.props.title" class="static-image-title">{{ component.props.title }}</h3>
              <img data-testid="dashboard-image" class="static-image" :src="imageSource(component, resources)" :alt="component.props.alt" :style="{ objectFit: component.props.fit === 'COVER' ? 'cover' : 'contain' }" draggable="false" />
            </template>
          </article>
        </div>
      </div>
    </template>
  </section>
</template>

<style scoped>
.static-dashboard { --static-text: #172b4d; --static-secondary: #53657e; --static-primary: #155bc1; color: var(--static-text); background: #fff; min-width: 0; width: 100%; }
.static-dark { --static-text: #e5edf9; --static-secondary: #aabbcf; --static-primary: #81b7ff; background: #142033; }
.static-page-tabs { display: flex; flex-wrap: wrap; gap: 8px; padding-bottom: 12px; }
.static-page-tabs button { font: inherit; color: inherit; background: transparent; border: 1px solid currentColor; border-radius: 6px; padding: 8px 12px; cursor: pointer; overflow-wrap: anywhere; }
.static-page-tabs button[aria-current="page"] { color: var(--static-primary); font-weight: 700; }
.static-viewport { width: 100%; min-width: 0; position: relative; overflow: hidden; }
.static-fixed-viewport { height: min(70vh, 1080px); }
.static-canvas { min-width: 0; }
.static-grid { display: grid; grid-template-columns: repeat(24, minmax(0, 1fr)); grid-auto-rows: 8px; gap: 8px; }
.static-single { display: flex; flex-direction: column; gap: 8px; }
.static-fixed { position: absolute; width: 1920px; height: 1080px; left: 50%; top: 50%; transform-origin: center; }
.static-component { box-sizing: border-box; display: flex; flex-direction: column; min-width: 0; min-height: 0; overflow: auto; }
.static-text { margin: 0; white-space: pre-wrap; overflow-wrap: anywhere; max-width: 100%; line-height: 1.5; color: var(--static-text); }
.text-small { font-size: 14px; }.text-medium { font-size: 18px; }.text-large { font-size: 28px; }
.tone-secondary { color: var(--static-secondary); }.tone-primary { color: var(--static-primary); }
.align-left { text-align: left; }.align-center { text-align: center; }.align-right { text-align: right; }
.static-image-title { margin: 0 0 8px; font-size: 16px; overflow-wrap: anywhere; flex-shrink: 0; }
.static-image { display: block; width: 100%; min-height: 0; flex: 1 1 0; object-position: center; }
.static-single .static-image { flex-basis: auto; height: auto; max-width: 100%; }
</style>
