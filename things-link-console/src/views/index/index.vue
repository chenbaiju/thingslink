<!-- 布局容器 -->
<template>
  <div class="app-layout">
    <aside id="app-sidebar">
      <ArtSidebarMenu />
    </aside>

    <main id="app-main">
      <div id="app-header">
        <ArtHeaderBar :hide-navigation="usesProjectTitlebar" />
      </div>
      <div id="app-content">
        <ArtPageContent />
      </div>
    </main>

    <div id="app-global">
      <ArtGlobalComponent />
    </div>
  </div>
</template>

<script setup lang="ts">
  import { computed } from 'vue'
  import { useRoute } from 'vue-router'
  import { useUserStore } from '@/store/modules/user'

  defineOptions({ name: 'AppLayout' })

  const route = useRoute()
  const user = useUserStore()
  const usesProjectTitlebar = computed(
    () =>
      !user.info.currentProjectId &&
      [
        '/project/list',
        '/project/create',
        '/project/recycle-bin',
        '/plan-catalog',
        '/system-status',
        '/system/user-center'
      ].includes(route.path)
  )
</script>

<style lang="scss" scoped>
  @use './style';
</style>
