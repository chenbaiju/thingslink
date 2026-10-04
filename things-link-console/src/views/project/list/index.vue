<!--
  项目列表按后端返回的当前角色分组：OWNER 显示在“我的项目”，其他成员显示在“我加入的项目”。
  角色只用于界面展示，**不构成授权**：服务端对每个操作独立校验。
-->
<template>
  <div class="console-page project-list">
    <div class="project-list__header console-toolbar console-page-actions">
      <ElButton type="primary" :icon="Plus" @click="openCreateDialog">
        {{ $t('project.create') }}
      </ElButton>
    </div>

    <section v-for="group in projectGroups" :key="group.key" class="project-list__section">
      <h4 class="project-list__section-title">{{ group.title }}</h4>
      <ElCard shadow="never" class="console-table-panel mt-2.5">
        <ElTable v-loading="loading" :data="group.projects" row-key="id">
          <ElTableColumn :label="$t('project.column.name')" min-width="200">
            <template #default="{ row }">
              {{ row.name }}
              <ElTag v-if="row.id === currentProjectId" size="small" type="success" class="ml-2">
                {{ $t('project.current') }}
              </ElTag>
            </template>
          </ElTableColumn>
          <ElTableColumn :label="$t('project.column.region')" width="120">
            <template #default="{ row }">{{ regionName(row.region) }}</template>
          </ElTableColumn>
          <ElTableColumn :label="$t('project.column.myRole')" width="140">
            <template #default="{ row }">
              <ElTag :type="roleTagType(row.myRole)" disable-transitions>{{ row.myRole }}</ElTag>
            </template>
          </ElTableColumn>
          <ElTableColumn :label="$t('project.column.createdAt')" width="200">
            <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
          </ElTableColumn>
          <ElTableColumn
            class-name="console-table-actions-cell"
            :label="$t('project.column.action')"
            width="144"
          >
            <template #default="{ row }">
              <ConsoleTableAction
                type="primary"
                :disabled="row.id === currentProjectId"
                :loading="switchingId === row.id"
                @click="enter(row)"
                :label="row.id === currentProjectId ? $t('project.entered') : $t('project.enter')"
                icon="ri:login-box-line"
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
            </template>
          </ElTableColumn>

          <template #empty>
            <ElEmpty
              v-if="group.key === 'owned'"
              class="project-list__empty"
              :image-size="120"
              :description="$t('project.ownedEmptyTitle')"
            >
              <p>{{ $t('project.ownedEmptyHint') }}</p>
            </ElEmpty>
            <ElEmpty
              v-else
              class="project-list__empty"
              :image-size="120"
              :description="$t('project.joinedEmptyTitle')"
            >
              <p>{{ $t('project.joinedEmptyHint') }}</p>
            </ElEmpty>
          </template>
        </ElTable>
      </ElCard>
    </section>

    <ElDialog
      class="console-dialog"
      v-model="dialogVisible"
      :title="$t('project.create')"
      width="420px"
    >
      <ElForm ref="formRef" :model="form" :rules="rules" label-position="top">
        <ElFormItem prop="name" :label="$t('project.column.name')">
          <ElInput
            v-model.trim="form.name"
            :placeholder="$t('project.namePlaceholder')"
            maxlength="128"
            show-word-limit
            @keyup.enter="submit"
          />
        </ElFormItem>
        <ElFormItem prop="region" :label="$t('project.column.region')">
          <ElSelect
            v-model="form.region"
            class="w-full"
            :loading="regionLoading"
            :placeholder="$t('project.regionRequired')"
          >
            <ElOptionGroup
              v-for="group in regionGroups"
              :key="group.areaCode"
              :label="group.areaName"
            >
              <ElOption
                v-for="region in group.regions"
                :key="region.code"
                :label="region.name"
                :value="region.code"
                :disabled="!region.projectCreationEnabled"
              />
            </ElOptionGroup>
          </ElSelect>
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="dialogVisible = false">{{ $t('project.cancel') }}</ElButton>
        <ElButton type="primary" :loading="submitting" @click="submit">
          {{ $t('project.confirm') }}
        </ElButton>
      </template>
    </ElDialog>

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
  import ConsoleTableAction from '@/components/ConsoleTableAction.vue'

  import { useI18n } from 'vue-i18n'
  import { Plus } from '@element-plus/icons-vue'
  import type { FormInstance, FormRules } from 'element-plus'
  import { formatTime } from '@/utils/time'
  import { fetchSwitchProject } from '@/api/auth'
  import {
    fetchProjects,
    fetchCreateProject,
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

  const loading = ref(false)
  const submitting = ref(false)
  const deletingId = ref('')
  const dialogVisible = ref(false)
  const editVisible = ref(false)
  const projects = ref<ProjectResponse[]>([])
  const formRef = ref<FormInstance>()
  const form = reactive({ name: '', region: 'sh-1' })
  const editFormRef = ref<FormInstance>()
  const editTarget = ref<ProjectResponse>()
  const editForm = reactive({ name: '', region: '' })
  const { switchingId, switchProject } = useProjectSwitch()
  const {
    defaultProjectRegion,
    loading: regionLoading,
    loadProjectRegions,
    regionGroups,
    regionName
  } = useProjectRegionCatalog()

  const userStore = useUserStore()
  const currentProjectId = computed(() => userStore.info.currentProjectId ?? '')

  const enter = switchProject

  const rules = computed<FormRules<typeof form>>(() => ({
    name: [{ required: true, message: t('project.nameRequired'), trigger: 'blur' }],
    region: [{ required: true, message: t('project.regionRequired'), trigger: 'change' }]
  }))

  const editRules = computed<FormRules<typeof editForm>>(() => ({
    name: [{ required: true, message: t('project.nameRequired'), trigger: 'blur' }]
  }))

  /** 项目生命周期操作只给 OWNER 显示；服务端仍会独立校验 50003。 */
  const isOwner = (row: ProjectResponse) => row.myRole === 'OWNER'

  const ownedProjects = computed(() => projects.value.filter(isOwner))
  const joinedProjects = computed(() => projects.value.filter((project) => !isOwner(project)))
  const projectGroups = computed(() => [
    { key: 'owned', title: t('project.ownedTitle'), projects: ownedProjects.value },
    { key: 'joined', title: t('project.joinedTitle'), projects: joinedProjects.value }
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

  const openCreateDialog = () => {
    form.name = ''
    form.region = defaultProjectRegion.value
    dialogVisible.value = true
  }

  const openEditDialog = (project: ProjectResponse) => {
    editTarget.value = project
    editForm.name = project.name ?? ''
    editForm.region = project.region ?? ''
    editVisible.value = true
  }

  const submit = async () => {
    // 防重复提交：submitting 在首个 await 前同步置位，二次点击在 validate 完成前就被挡下
    if (!formRef.value || submitting.value) return
    submitting.value = true
    try {
      await formRef.value.validate()

      await fetchCreateProject({ name: form.name, region: form.region })

      ElMessage.success(t('project.createSuccess'))
      dialogVisible.value = false
      // 重新拉取而不是把返回值 push 进数组：列表有排序规则（按创建时间倒序），
      // 本地拼接迟早会和服务端的顺序对不上
      await load()
    } catch (error) {
      if (!(error instanceof HttpError)) {
        console.error('创建项目失败:', error)
      }
    } finally {
      submitting.value = false
    }
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
      await fetchDeleteProject(project.id)
      ElMessage.success(t('project.deleteSuccess'))

      if (project.id === currentProjectId.value) {
        const { accessToken } = await fetchSwitchProject(null)
        if (accessToken) {
          userStore.setToken(accessToken)
          userStore.setUserInfo({
            ...userStore.info,
            currentProjectId: ''
          } as Api.Auth.UserInfo)
        }
        window.location.assign('/project/list')
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
    padding: 10px;

    &__header {
      display: flex;
      align-items: flex-start;
      justify-content: space-between;
    }

    &__title {
      margin: 0;
      font-size: 18px;
      font-weight: 500;
    }

    &__subtitle {
      margin: 6px 0 0;
      font-size: 13px;
      color: var(--art-text-gray-600);
    }

    &__section {
      margin-top: 10px;
    }

    &__section-title {
      margin: 0;
      font-size: 16px;
      font-weight: 500;
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
