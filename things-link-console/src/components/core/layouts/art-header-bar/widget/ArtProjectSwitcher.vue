<template>
  <ElDropdown
    v-if="projects.length"
    trigger="click"
    popper-class="project-switcher-popper"
    @command="handleCommand"
  >
    <button class="project-switcher" type="button">
      <ArtSvgIcon icon="ri:folder-3-line" class="project-switcher__icon" />
      <span class="project-switcher__text">
        <span class="project-switcher__name">
          {{ currentProject?.name ?? $t('project.select') }}
        </span>
        <span class="project-switcher__region">
          {{ currentProject ? regionLabel(currentProject.region) : $t('project.noCurrent') }}
        </span>
      </span>
      <ArtSvgIcon icon="ri:arrow-down-s-line" class="project-switcher__arrow" />
    </button>
    <template #dropdown>
      <ElDropdownMenu>
        <ElDropdownItem
          v-for="project in projects"
          :key="project.id"
          :command="project.id"
          :disabled="project.id === currentProjectId || switchingId === project.id"
        >
          <div class="project-switcher-item">
            <span class="project-switcher-item__name">{{ project.name }}</span>
            <span class="project-switcher-item__meta">
              {{ regionLabel(project.region) }} · {{ project.myRole }}
            </span>
          </div>
        </ElDropdownItem>
      </ElDropdownMenu>
    </template>
  </ElDropdown>
</template>

<script setup lang="ts">
  import { mittBus } from '@/utils/sys'
  import { fetchProjects, type ProjectResponse } from '@/api/project'
  import { useUserStore } from '@/store/modules/user'
  import { HttpError } from '@/utils/http/error'
  import { useProjectRegionCatalog } from '@/hooks/project/useProjectRegionCatalog'
  import { useProjectSwitch } from '@/hooks/project/useProjectSwitch'

  defineOptions({ name: 'ArtProjectSwitcher' })

  const userStore = useUserStore()
  const projects = ref<ProjectResponse[]>([])
  const { switchingId, switchProject } = useProjectSwitch()
  const { loadProjectRegions, regionName: regionLabel } = useProjectRegionCatalog()

  const currentProjectId = computed(() => userStore.info.currentProjectId ?? '')
  const currentProject = computed(() =>
    projects.value.find((item) => item.id === currentProjectId.value)
  )

  const handleCommand = async (projectId: string) => {
    const project = projects.value.find((item) => item.id === projectId)
    if (project) {
      await switchProject(project)
    }
  }

  const load = async () => {
    try {
      projects.value = await fetchProjects()
    } catch (error) {
      if (!(error instanceof HttpError)) {
        console.error('加载项目切换器失败:', error)
      }
    }
  }

  onMounted(() => {
    mittBus.on('projectsChanged', load)
    load()
    loadProjectRegions().catch((error) => {
      if (!(error instanceof HttpError)) {
        console.error('加载项目区域失败:', error)
      }
    })
  })
  onBeforeUnmount(() => mittBus.off('projectsChanged', load))
</script>

<style lang="scss" scoped>
  .project-switcher {
    display: inline-flex;
    align-items: center;
    max-width: 240px;
    height: 36px;
    padding: 0 10px;
    color: var(--art-text-gray-800);
    cursor: pointer;
    background: var(--art-gray-100);
    border: 1px solid var(--art-border-color);
    border-radius: 6px;

    &__icon,
    &__arrow {
      flex: 0 0 auto;
      font-size: 16px;
    }

    &__text {
      display: flex;
      flex-direction: column;
      min-width: 0;
      margin: 0 6px;
      line-height: 1.1;
      text-align: left;
    }

    &__name,
    &__region {
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    &__name {
      font-size: 13px;
      font-weight: 500;
    }

    &__region {
      margin-top: 2px;
      font-size: 11px;
      color: var(--art-text-gray-500);
    }
  }

  .project-switcher-item {
    display: flex;
    flex-direction: column;
    min-width: 180px;
    line-height: 1.3;

    &__name {
      font-size: 13px;
      color: var(--art-text-gray-800);
    }

    &__meta {
      margin-top: 2px;
      font-size: 12px;
      color: var(--art-text-gray-500);
    }
  }
</style>
