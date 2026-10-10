<!--
  项目列表按后端返回的当前角色分组：OWNER 显示在“我创建的项目”，其他成员显示在“我加入的项目”。
  角色只用于界面展示，**不构成授权**：服务端对每个操作独立校验。
-->
<template>
  <div class="console-page project-list">
    <section v-for="group in projectGroups" :key="group.key" class="project-list__section">
      <div class="project-list__header">
        <h2 class="console-heading">{{ group.title }}</h2>
        <div v-if="group.key === 'owned'" class="project-list__actions">
          <nav v-if="headerLinks.length" class="project-list__links" aria-label="项目关联入口">
            <ElButton v-for="link in headerLinks" :key="link.path" @click="router.push(link.path)">
              {{ link.label }}
            </ElButton>
          </nav>
          <ElButton type="primary" :icon="Plus" @click="router.push('/project/create')">
            {{ $t('project.create') }}
          </ElButton>
          <ElButton @click="router.push('/project/recycle-bin')">项目回收站</ElButton>
        </div>
        <div class="project-list__intro">
          <p class="project-list__description">{{ group.description }}</p>
        </div>
      </div>
      <div v-loading="loading" class="project-list__content" :aria-busy="loading">
        <ElTable
          :data="group.projects"
          row-key="id"
          height="100%"
          :row-class-name="
            ({ row }) => (row.id === currentProjectId ? '' : 'project-list__clickable-row')
          "
          @row-click="enterRow"
        >
          <ElTableColumn :label="$t('project.column.name')" min-width="200">
            <template #default="{ row }">
              <button
                class="project-list__entry"
                type="button"
                :aria-label="'进入项目：' + row.name"
                :disabled="row.id === currentProjectId || !!switchingId"
                @click.stop="enter(row)"
                >{{ row.name }}</button
              >
              <ElTag v-if="row.id === currentProjectId" size="small" type="success" class="ml-2">
                {{ $t('project.current') }}
              </ElTag>
            </template>
          </ElTableColumn>
          <ElTableColumn label="项目描述" min-width="240" show-overflow-tooltip>
            <template #default="{ row }">{{ row.description || '—' }}</template>
          </ElTableColumn>
          <ElTableColumn :label="$t('project.column.region')" width="120">
            <template #default="{ row }">{{ regionName(row.region) }}</template>
          </ElTableColumn>
          <ElTableColumn :label="$t('project.column.myRole')" width="140">
            <template #default="{ row }">
              <ElTag :type="roleTagType(row.myRole)" disable-transitions>{{ row.myRole }}</ElTag>
            </template>
          </ElTableColumn>
          <ElTableColumn label="订阅套餐" width="200">
            <template #default="{ row }">
              <span class="project-list__plan" :title="projectPlanTitle(row)">{{
                projectPlanLabel(row)
              }}</span>
            </template>
          </ElTableColumn>
          <ElTableColumn
            class-name="console-table-actions-cell"
            :label="$t('project.column.action')"
            width="180"
          >
            <template #default="{ row }">
              <div class="project-list__row-actions" @click.stop>
                <ConsoleTableAction
                  type="primary"
                  :disabled="row.id === currentProjectId"
                  :loading="switchingId === row.id"
                  @click="enter(row)"
                  :label="row.id === currentProjectId ? $t('project.entered') : $t('project.enter')"
                  icon="ri:login-box-line"
                />
                <ConsoleTableAction
                  type="primary"
                  :disabled="!row.projectKey"
                  @click="appEntryProject = row"
                  label="App 接入"
                  icon="ri:qr-code-line"
                />
                <template v-if="isOwner(row)">
                  <ConsoleTableAction
                    type="primary"
                    @click="openEditDialog(row)"
                    :label="$t('project.edit')"
                    icon="ri:pencil-line"
                  />
                  <ConsoleTableAction
                    type="danger"
                    :loading="deletingId === row.id"
                    @click="confirmDelete(row)"
                    :label="$t('project.delete')"
                    icon="ri:delete-bin-5-line"
                  />
                </template>
              </div>
            </template>
          </ElTableColumn>

          <template #empty>
            <div class="project-list__empty-panel">
              <ElEmpty
                v-if="selectedRegion"
                class="project-list__empty"
                :image-size="100"
                description="该区域暂无项目"
              />
              <ElEmpty
                v-else-if="group.key === 'owned'"
                class="project-list__empty"
                :image-size="100"
                :description="$t('project.ownedEmptyTitle')"
              >
                <p>{{ $t('project.ownedEmptyHint') }}</p>
              </ElEmpty>
              <ElEmpty
                v-else
                class="project-list__empty"
                :image-size="100"
                :description="$t('project.joinedEmptyTitle')"
              >
                <p>{{ $t('project.joinedEmptyHint') }}</p>
              </ElEmpty>
            </div>
          </template>
        </ElTable>
      </div>
    </section>

    <AppProjectEntryDialog :project="appEntryProject" @close="appEntryProject = undefined" />
    <ElDialog
      class="console-dialog"
      v-model="editVisible"
      :title="$t('project.edit')"
      width="420px"
    >
      <ElForm ref="editFormRef" :model="editForm" :rules="editRules" label-position="top">
        <ElFormItem prop="name" :label="$t('project.column.name')">
          <ElInput
            v-model.trim="editForm.name"
            :placeholder="$t('project.namePlaceholder')"
            maxlength="128"
            show-word-limit
            @keyup.enter="submitEdit"
          />
        </ElFormItem>
        <ElFormItem :label="$t('project.column.region')">
          <ElInput :model-value="regionName(editForm.region)" disabled />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="editVisible = false">{{ $t('project.cancel') }}</ElButton>
        <ElButton type="primary" :loading="submitting" @click="submitEdit">
          {{ $t('project.confirm') }}
        </ElButton>
      </template>
    </ElDialog>
  </div>
</template>

<script setup lang="ts">
  import AppProjectEntryDialog from './AppProjectEntryDialog.vue'
  import ConsoleTableAction from '@/components/ConsoleTableAction.vue'

  import { useI18n } from 'vue-i18n'
  import { useRoute, useRouter } from 'vue-router'
  import { Plus } from '@element-plus/icons-vue'
  import type { FormInstance, FormRules } from 'element-plus'
  import { fetchSwitchProject } from '@/api/auth'
  import {
    fetchProjects,
    fetchUpdateProject,
    fetchDeleteProject,
    type ProjectResponse
  } from '@/api/project'
  import { useUserStore } from '@/store/modules/user'
  import { HttpError } from '@/utils/http/error'
  import { useProjectSwitch } from '@/hooks/project/useProjectSwitch'
  import { useProjectRegionCatalog } from '@/hooks/project/useProjectRegionCatalog'

  defineOptions({ name: 'ProjectList' })

  const { t } = useI18n()
  const route = useRoute()
  const router = useRouter()
  const selectedRegion = computed(() =>
    typeof route.query.region === 'string' ? route.query.region : ''
  )

  const loading = ref(false)
  const submitting = ref(false)
  const deletingId = ref('')
  const editVisible = ref(false)
  const projects = ref<ProjectResponse[]>([])
  const appEntryProject = ref<ProjectResponse>()
  const editFormRef = ref<FormInstance>()
  const editTarget = ref<ProjectResponse>()
  const editForm = reactive({ name: '', region: '' })
  const { switchingId, switchProject } = useProjectSwitch()
  const { loadProjectRegions, regionName } = useProjectRegionCatalog()

  const userStore = useUserStore()
  const currentProjectId = computed(() => userStore.info.currentProjectId ?? '')
  const headerLinks = computed(() =>
    [
      { label: '成员与邀请', path: '/project/members', permission: 'member:read' },
      { label: '用量与项目设置', path: '/project/settings', permission: 'quota:read' }
    ].filter((link) => userStore.info.buttons?.includes(link.permission))
  )
  // 列表接口按每个项目的归属与成员资格返回订阅身份，不使用当前项目套餐替代其他项目。
  const projectPlanLabel = (project: ProjectResponse) =>
    project.subscribedPlan?.name || project.subscribedPlan?.code || '订阅版本暂不可用'

  const projectPlanTitle = (project: ProjectResponse) => {
    const plan = project.subscribedPlan
    if (!plan) return '接口未提供订阅版本，可能无活状态订阅或当前账号不具备租户套餐查看资格'
    return [plan.name || plan.code, plan.revision, plan.revisionNo ? `v${plan.revisionNo}` : '']
      .filter(Boolean)
      .join(' · ')
  }

  const enter = (project: ProjectResponse) => {
    if (!project.id || project.id === currentProjectId.value || switchingId.value) return
    return switchProject(project)
  }

  const enterRow = (project: ProjectResponse, _column: unknown, event: MouseEvent) => {
    if ((event.target as Element)?.closest('button, a, input, .console-table-actions-cell')) return
    return enter(project)
  }

  const editRules = computed<FormRules<typeof editForm>>(() => ({
    name: [{ required: true, message: t('project.nameRequired'), trigger: 'blur' }]
  }))

  /** 项目生命周期操作只给 OWNER 显示；服务端仍会独立校验 50003。 */
  const isOwner = (row: ProjectResponse) => row.myRole === 'OWNER'

  const visibleProjects = computed(() =>
    selectedRegion.value
      ? projects.value.filter((project) => project.region === selectedRegion.value)
      : projects.value
  )
  const ownedProjects = computed(() => visibleProjects.value.filter(isOwner))
  const joinedProjects = computed(() =>
    visibleProjects.value.filter((project) => !isOwner(project))
  )
  const projectGroups = computed(() => [
    {
      key: 'owned',
      title: '我创建的项目',
      description: '创建和管理您的物联网项目，连接设备、配置规则与应用，构建业务解决方案。',
      projects: ownedProjects.value
    },
    {
      key: 'joined',
      title: t('project.joinedTitle'),
      description: '参与团队共享的项目，按照您的角色权限协作开发与管理设备。',
      projects: joinedProjects.value
    }
  ])

  /** 角色对应的标签配色。OWNER 用主色突出，其余按权限递减冷却。 */
  const roleTagType = (role: string) => {
    switch (role) {
      case 'OWNER':
        return 'primary'
      case 'ADMIN':
        return 'success'
      case 'OPERATOR':
        return 'warning'
      default:
        return 'info'
    }
  }

  const load = async () => {
    loading.value = true
    try {
      projects.value = await fetchProjects()
    } catch (error) {
      // HttpError 已由 HTTP 层统一提示，这里不重复弹
      if (!(error instanceof HttpError)) {
        console.error('加载项目列表失败:', error)
      }
    } finally {
      loading.value = false
    }
  }

  const openEditDialog = (project: ProjectResponse) => {
    editTarget.value = project
    editForm.name = project.name ?? ''
    editForm.region = project.region ?? ''
    editVisible.value = true
  }

  const submitEdit = async () => {
    // 防重复提交：同 submit
    if (!editFormRef.value || !editTarget.value?.id || submitting.value) return
    submitting.value = true
    try {
      await editFormRef.value.validate()

      await fetchUpdateProject(editTarget.value.id, { name: editForm.name })

      ElMessage.success(t('project.editSuccess'))
      editVisible.value = false
      await load()
    } catch (error) {
      if (!(error instanceof HttpError)) {
        console.error('编辑项目失败:', error)
      }
    } finally {
      submitting.value = false
    }
  }

  const confirmDelete = async (project: ProjectResponse) => {
    if (!project.id) return
    try {
      await ElMessageBox.confirm(
        t('project.deleteConfirm', { name: project.name }),
        t('project.delete'),
        {
          confirmButtonText: t('project.confirm'),
          cancelButtonText: t('project.cancel'),
          type: 'warning'
        }
      )

      deletingId.value = project.id
      const deletingCurrent = project.id === currentProjectId.value
      if (deletingCurrent) {
        // 删除会使项目代次令牌立即失效，必须先取得账号作用域再执行删除。
        const { accessToken } = await fetchSwitchProject(null)
        if (!accessToken) throw new Error('退出当前项目响应中没有 accessToken')
        userStore.setToken(accessToken)
        userStore.setUserInfo({ ...userStore.info, currentProjectId: '' } as Api.Auth.UserInfo)
      }
      await fetchDeleteProject(project.id)
      ElMessage.success(t('project.deleteSuccess'))
      if (deletingCurrent) {
        window.location.reload()
        return
      }

      await load()
    } catch (error) {
      if (error === 'cancel' || error === 'close') return
      if (!(error instanceof HttpError)) {
        console.error('删除项目失败:', error)
      }
    } finally {
      deletingId.value = ''
    }
  }

  onMounted(() => {
    load()
    loadProjectRegions().catch((error) => {
      if (!(error instanceof HttpError)) {
        console.error('加载项目区域失败:', error)
      }
    })
  })
</script>

<style lang="scss" scoped>
  .project-list {
    display: flex;
    flex-direction: column;
    height: var(--art-full-height);
    min-height: 680px;
    padding: 10px;
    &__header,
    &__links,
    &__actions {
      display: flex;
      flex-wrap: wrap;
      gap: 10px;
      align-items: center;
    }
    &__header {
      flex-shrink: 0;
      row-gap: 8px;
      justify-content: space-between;
      min-height: 36px;
      padding-bottom: 18px;
      border-bottom: 1px solid var(--console-line);

      > .console-heading {
        margin-bottom: 0;
        font-size: 28px;
        font-weight: 400;
        line-height: 40px;
      }
    }
    &__intro {
      flex-basis: 100%;
      min-width: 0;
    }
    &__description {
      margin: 0 0 8px;
      font-size: 12px;
      line-height: 20px;
      color: var(--el-text-color-secondary);
    }
    &__links {
      margin-right: auto;
    }
    &__actions :deep(.el-button + .el-button) {
      margin-left: 0;
    }
    &__section {
      display: flex;
      flex: 1 1 0;
      flex-direction: column;
      min-height: 0;
    }
    &__section + &__section {
      margin-top: 28px;
    }
    &__content {
      flex: 1;
      min-height: 0;
      margin-top: 14px;
      overflow: auto;
    }
    :deep(.project-list__clickable-row) {
      cursor: pointer;
    }
    &__row-actions {
      display: flex;
      gap: 8px;
      align-items: center;
    }
    &__entry {
      padding: 0;
      font: inherit;
      color: var(--el-text-color-primary);
      text-align: left;
      cursor: pointer;
      background: none;
      border: 0;

      &:hover:not(:disabled) {
        color: var(--el-color-primary);
      }
    }
    &__empty-panel {
      box-sizing: border-box;
      display: flex;
      align-items: center;
      justify-content: center;
      min-height: 100%;
      padding: 12px;
    }
    &__empty {
      padding: 8px 12px;
      :deep(.el-empty__description p) {
        font-size: 16px;
        font-weight: 500;
        color: var(--art-text-gray-800);
      }
      p {
        margin: 0;
        color: var(--art-text-gray-600);
      }
    }
  }
</style>
