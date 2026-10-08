<template>
  <nav class="project-navigation" :aria-label="pageLabel + '导航'">
    <ElSelect
      v-if="isProjectList"
      v-model="selectedRegion"
      class="project-navigation__region"
      aria-label="项目区域"
      :loading="loading"
      :empty-values="[null, undefined]"
      :title="loadError || '按区域查看项目'"
      @visible-change="loadRegionsWhenOpened"
    >
      <ElOption label="全部区域" value="" />
      <ElOptionGroup v-for="group in regionGroups" :key="group.areaCode" :label="group.areaName">
        <ElOption
          v-for="region in group.regions"
          :key="region.code"
          :label="region.name"
          :value="region.code"
        />
      </ElOptionGroup>
    </ElSelect>
    <button
      class="project-navigation__refresh"
      type="button"
      :title="'刷新' + pageLabel"
      @click="refresh"
    >
      {{ pageLabel }}
    </button>
  </nav>
</template>

<script setup lang="ts">
  import { computed, onMounted, ref } from 'vue'
  import { useRoute, useRouter } from 'vue-router'
  import { useCommon } from '@/hooks/core/useCommon'
  import { useProjectRegionCatalog } from '@/hooks/project/useProjectRegionCatalog'

  const route = useRoute()
  const router = useRouter()
  const isProjectList = computed(() => route.path === '/project/list')
  const pageLabel = computed(
    () =>
      ({
        '/project/list': '我的项目',
        '/project/create': '添加项目',
        '/project/recycle-bin': '项目回收站',
        '/plan-catalog': '套餐与权益',
        '/system-status': '系统状态',
        '/system/user-center': '个人中心'
      })[route.path] || '我的项目'
  )
  const { refresh } = useCommon()
  const { loading, regionGroups, loadProjectRegions } = useProjectRegionCatalog()
  const loadError = ref('')

  // 区域只筛选当前列表，不切换项目身份；刷新时保留筛选条件。
  const selectedRegion = computed({
    get: () => (typeof route.query.region === 'string' ? route.query.region : ''),
    set: (region: string) => {
      void router.replace({ query: { ...route.query, region: region || undefined } })
    }
  })

  const loadRegions = async () => {
    try {
      await loadProjectRegions()
      loadError.value = ''
    } catch {
      loadError.value = '区域加载失败，请重新展开下拉框重试'
    }
  }

  const loadRegionsWhenOpened = (visible: boolean) => {
    if (visible) void loadRegions()
  }

  onMounted(() => {
    if (isProjectList.value) void loadRegions()
  })
</script>

<style scoped lang="scss">
  .project-navigation {
    display: flex;
    gap: 20px;
    align-items: center;
    min-width: 0;
    margin-left: 28px;
    line-height: normal;

    &__region {
      flex-shrink: 0;
      width: 104px;

      :deep(.el-select__wrapper) {
        background: transparent;
        box-shadow: none;
      }

      :deep(.el-select__wrapper.is-focused) {
        box-shadow: 0 0 0 1px var(--el-color-primary) inset;
      }
    }

    &__refresh {
      flex-shrink: 0;
      padding: 8px 10px;
      font-size: 14px;
      font-weight: 500;
      color: var(--el-text-color-primary);
      cursor: pointer;
      background: transparent;
      border: 0;
      border-radius: 6px;

      &:hover {
        color: var(--el-color-primary);
        background: var(--el-fill-color-light);
      }

      &:focus-visible {
        outline: 2px solid var(--el-color-primary);
        outline-offset: 2px;
      }
    }
  }

  @media (width <= 640px) {
    .project-navigation {
      gap: 4px;
      margin-left: 8px;
    }
  }
</style>
