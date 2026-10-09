<script setup lang="ts">
  import { computed } from 'vue'
  import { useRouter } from 'vue-router'
  import { useUserStore } from '@/store/modules/user'

  const props = withDefaults(
    defineProps<{
      title: string
      projectStyle?: boolean
      description?: string
      links?: { label: string; path: string; permission?: string }[]
    }>(),
    { description: '', links: () => [] }
  )
  const user = useUserStore()
  const router = useRouter()
  const visibleLinks = computed(() =>
    props.links.filter((link) => !link.permission || user.info.buttons?.includes(link.permission))
  )
</script>

<template>
  <header
    class="console-page-header workspace-header"
    :class="{ 'workspace-header--project': projectStyle }"
  >
    <div>
      <h1 class="console-heading">{{ title }}</h1>
      <p v-if="description" class="console-description">{{ description }}</p>
    </div>
    <div class="console-actions">
      <slot name="leading-actions" />
      <nav v-if="visibleLinks.length" class="console-actions" :aria-label="`${title}关联入口`">
        <ElButton v-for="link in visibleLinks" :key="link.path" @click="router.push(link.path)">
          {{ link.label }}
        </ElButton>
      </nav>
      <slot name="actions" />
    </div>
  </header>
</template>

<style scoped lang="scss">
  .workspace-header--project {
    padding-bottom: 18px;
    margin-bottom: 14px;
    border-bottom: 1px solid var(--console-line);

    .console-heading {
      margin: 0 0 8px;
      font-size: 28px;
      font-weight: 400;
      line-height: 40px;
    }
    .console-description {
      margin: 0;
      font-size: 12px;
      line-height: 20px;
    }
  }
</style>
