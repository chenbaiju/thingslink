<!--
  项目成员。

  这是第一个**按角色显示不同界面**的页面：OWNER / ADMIN 能看到添加与操作按钮，
  OPERATOR / VIEWER 只能看名单。判断依据是后端下发的权限点
  （`member:invite` / `member:update_role` / `member:remove`）。

  隐藏按钮**不构成授权** —— 服务端对每个操作独立校验（架构文档 7.2）。
  这里做的是「别让用户点一个注定失败的按钮」，不是安全措施。

  页面作用于**当前选中的项目**。没选项目时这个菜单根本不会下发（它要 member:read，
  而未选项目时没有任何项目角色），所以正常进不到这里；真进来了就提示去选一个。
-->
<template>
  <div class="console-page project-members console-page--single-panel">
    <ConsoleWorkspaceHeader
      title="项目成员与邀请"
      description="管理共同开发此项目的成员和邀请。应用终端用户使用独立账号与授权。"
      :links="[
        { label: '项目列表', path: '/project/list' },
        { label: '终端用户', path: '/project/end-users', permission: 'enduser:read' }
      ]"
    />
    <div class="project-members__header console-toolbar console-page-actions">
      <div class="project-members__actions console-actions">
        <ElButton v-if="canLeave" type="danger" plain :loading="leaving" @click="confirmLeave">
          {{ $t('member.leave') }}
        </ElButton>
        <ElButton v-if="canInvite" type="primary" :icon="Plus" @click="openInviteDialog">
          {{ $t('member.add') }}
        </ElButton>
      </div>
    </div>

    <ElAlert
      v-if="!projectId"
      type="info"
      show-icon
      :closable="false"
      class="mt-2.5"
      :title="$t('member.noProject')"
    />

    <ElCard v-else shadow="never" class="console-table-panel console-page__main-panel">
      <ElTable v-loading="loading" :data="members" row-key="accountId">
        <ElTableColumn :label="$t('member.column.user')" min-width="220">
          <template #default="{ row }">
            <div class="project-members__user">
              <span class="project-members__name">{{ row.displayName }}</span>
              <span class="project-members__email">{{ row.email }}</span>
            </div>
          </template>
        </ElTableColumn>
        <ElTableColumn :label="$t('member.column.role')" width="140">
          <template #default="{ row }">
            <ElTag :type="roleTagType(row.role)" disable-transitions>{{ row.role }}</ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn :label="$t('member.column.joinedAt')" width="200">
          <template #default="{ row }">{{ formatTime(row.joinedAt) }}</template>
        </ElTableColumn>
        <ElTableColumn
          class-name="console-table-actions-cell"
          v-if="canManage"
          :label="$t('member.column.action')"
          width="200"
        >
          <template #default="{ row }">
            <!--
            OWNER 与自己都不可操作。这两条与服务端的规则一一对应
            （50013 / 50014），提示文案说的是**为什么**不能，而不是「无权限」——
            后者会让人以为是自己角色不够。
          -->
            <template v-if="isImmutable(row)">
              <span class="project-members__hint">{{ immutableHint(row) }}</span>
            </template>
            <template v-else>
              <ConsoleTableAction
                v-if="canUpdateRole"
                type="primary"
                :disabled="removingAccountId === row.accountId"
                @click="openRoleDialog(row)"
                :label="$t('member.changeRole')"
                icon="ri:user-settings-line"
              />
              <ConsoleTableAction
                v-if="canTransferOwnership"
                type="warning"
                :loading="transferringAccountId === row.accountId"
                :disabled="removingAccountId === row.accountId"
                @click="confirmTransferOwnership(row)"
                :label="$t('member.transferOwner')"
                icon="ri:exchange-line"
              />
              <ConsoleTableAction
                v-if="canRemove"
                type="danger"
                :loading="removingAccountId === row.accountId"
                @click="confirmRemove(row)"
                :label="$t('member.remove')"
                icon="ri:user-unfollow-line"
              />
            </template>
          </template>
        </ElTableColumn>

        <template #empty>
          <ElEmpty :description="$t('member.empty')" />
        </template>
      </ElTable>
    </ElCard>

    <ProjectInvitations
      v-if="projectId && canInvite"
      ref="invitationList"
      :project-id="projectId"
    />

    <ElDialog
      class="console-dialog"
      v-model="inviteVisible"
      :title="$t('member.add')"
      width="460px"
    >
      <ElAlert
        type="info"
        show-icon
        :closable="false"
        class="mb-4"
        :title="$t('member.addNotice')"
      />
      <ElForm ref="inviteFormRef" :model="inviteForm" :rules="inviteRules" label-position="top">
        <ElFormItem prop="email" :label="$t('member.column.email')">
          <ElInput
            v-model.trim="inviteForm.email"
            :placeholder="$t('member.emailPlaceholder')"
            maxlength="254"
            @keyup.enter="submitInvite"
          />
        </ElFormItem>
        <ElFormItem prop="role" :label="$t('member.column.role')">
          <ElSelect v-model="inviteForm.role" class="w-full">
            <ElOption
              v-for="role in assignableRoles"
              :key="role"
              :value="role"
              :label="`${role} — ${$t(`member.roleDesc.${role}`)}`"
            />
          </ElSelect>
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="inviteVisible = false">{{ $t('member.cancel') }}</ElButton>
        <ElButton type="primary" :loading="submitting" @click="submitInvite">
          {{ $t('member.confirm') }}
        </ElButton>
      </template>
    </ElDialog>

    <ElDialog
      class="console-dialog"
      v-model="roleVisible"
      :title="$t('member.changeRole')"
      width="420px"
    >
      <p class="project-members__target console-description">{{ roleTarget?.email }}</p>
      <ElSelect v-model="roleForm.role" class="w-full">
        <ElOption
          v-for="role in assignableRoles"
          :key="role"
          :value="role"
          :label="`${role} — ${$t(`member.roleDesc.${role}`)}`"
        />
      </ElSelect>
      <template #footer>
        <ElButton @click="roleVisible = false">{{ $t('member.cancel') }}</ElButton>
        <ElButton type="primary" :loading="submitting" :disabled="!roleChanged" @click="submitRole">
          {{ $t('member.confirm') }}
        </ElButton>
      </template>
    </ElDialog>
  </div>
</template>

<script setup lang="ts">
  import ConsoleWorkspaceHeader from '@/components/business/ConsoleWorkspaceHeader.vue'
  import ConsoleTableAction from '@/components/ConsoleTableAction.vue'
  import ProjectInvitations from '@/components/ProjectInvitations.vue'
  import { createProjectInvitation } from '@/api/project-invitations'

  import { useI18n } from 'vue-i18n'
  import { Plus } from '@element-plus/icons-vue'
  import type { FormInstance, FormRules } from 'element-plus'
  import { formatTime } from '@/utils/time'
  import { fetchSwitchProject } from '@/api/auth'
  import {
    fetchProjectMembers,
    fetchUpdateMemberRole,
    fetchTransferOwnership,
    fetchRemoveMember,
    fetchLeaveProject,
    type ProjectMemberResponse,
    type AssignableRole
  } from '@/api/project'
  import { useUserStore } from '@/store/modules/user'
  import { HttpError } from '@/utils/http/error'

  defineOptions({ name: 'ProjectMembers' })

  const { t } = useI18n()
  const userStore = useUserStore()

  const invitationList = ref<InstanceType<typeof ProjectInvitations>>()
  const loading = ref(false)
  const submitting = ref(false)
  const members = ref<ProjectMemberResponse[]>([])

  const inviteVisible = ref(false)
  const inviteFormRef = ref<FormInstance>()
  const inviteForm = reactive<{ email: string; role: AssignableRole }>({
    email: '',
    role: 'VIEWER'
  })

  const roleVisible = ref(false)
  const roleTarget = ref<ProjectMemberResponse>()
  const roleForm = reactive<{ role: AssignableRole }>({ role: 'VIEWER' })
  const removingAccountId = ref('')
  const transferringAccountId = ref('')
  const leaving = ref(false)

  /**
   * OWNER 不在可分配的角色里：每个项目只有一个所有者，由创建项目产生。
   * 转让所有权是另一个操作（服务端返回 50012 拒绝），不该混进「改个角色」的下拉框。
   */
  const assignableRoles: AssignableRole[] = ['ADMIN', 'OPERATOR', 'VIEWER']

  const projectId = computed(() => userStore.info.currentProjectId ?? '')

  /** 后端契约里叫 accountId，store 里叫 userId（映射在 src/api/auth.ts）。 */
  const myAccountId = computed(() => userStore.info.userId ?? '')

  /**
   * 权限点由后端按**当前项目角色**下发，前端只读不判。
   *
   * store 里的字段叫 `buttons`（模板的命名），值是后端下发的 `permissions`，
   * 形如 `member:invite`。改这里的判断不会让任何人多出实际权限，
   * 只会让界面显示出点了必然 403 的按钮。
   */
  const has = (code: string) => (userStore.info.buttons ?? []).includes(code)
  const canInvite = computed(() => has('member:invite'))
  const canUpdateRole = computed(() => has('member:update_role'))
  const canRemove = computed(() => has('member:remove'))
  const canManage = computed(() => canUpdateRole.value || canRemove.value)
  const roleChanged = computed(() => roleTarget.value?.role !== roleForm.role)
  const myMember = computed(() =>
    members.value.find((member) => member.accountId === myAccountId.value)
  )
  const isProjectOwner = computed(() => myMember.value?.role === 'OWNER')
  const canTransferOwnership = computed(() => isProjectOwner.value)
  const canLeave = computed(() =>
    Boolean(projectId.value && myMember.value && !isProjectOwner.value)
  )

  /** OWNER 与自己都动不了，与服务端的 50013 / 50014 一一对应。 */
  const isImmutable = (row: ProjectMemberResponse) =>
    row.role === 'OWNER' || row.accountId === myAccountId.value

  const immutableHint = (row: ProjectMemberResponse) =>
    row.role === 'OWNER' ? t('member.ownerImmutable') : t('member.selfImmutable')

  const roleTagType = (role?: string) => {
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

  const inviteRules = computed<FormRules<typeof inviteForm>>(() => ({
    email: [
      { required: true, message: t('member.emailRequired'), trigger: 'blur' },
      { type: 'email', message: t('member.emailInvalid'), trigger: 'blur' }
    ]
  }))

  const load = async () => {
    if (!projectId.value) return
    loading.value = true
    try {
      members.value = await fetchProjectMembers(projectId.value)
    } catch (error) {
      // HttpError 已由 HTTP 层统一提示，这里不重复弹
      if (!(error instanceof HttpError)) {
        console.error('加载成员列表失败:', error)
      }
    } finally {
      loading.value = false
    }
  }

  const openInviteDialog = () => {
    inviteForm.email = ''
    inviteForm.role = 'VIEWER'
    inviteVisible.value = true
  }

  const submitInvite = async () => {
    if (!inviteFormRef.value || submitting.value) return
    submitting.value = true
    try {
      await inviteFormRef.value.validate()

      await createProjectInvitation(projectId.value, {
        email: inviteForm.email,
        role: inviteForm.role
      })

      ElMessage.success(t('member.addSuccess'))
      inviteVisible.value = false
      // 创建邀请不改变成员列表，只刷新邀请状态。
      await invitationList.value?.refresh()
    } catch (error) {
      if (!(error instanceof HttpError)) {
        console.error('邀请成员失败:', error)
      }
    } finally {
      submitting.value = false
    }
  }

  const openRoleDialog = (row: ProjectMemberResponse) => {
    roleTarget.value = row
    roleForm.role = (row.role as AssignableRole) ?? 'VIEWER'
    roleVisible.value = true
  }

  const submitRole = async () => {
    if (!roleTarget.value?.accountId) return
    if (!roleChanged.value) {
      roleVisible.value = false
      return
    }
    submitting.value = true
    try {
      await fetchUpdateMemberRole(projectId.value, roleTarget.value.accountId, roleForm.role)
      ElMessage.success(t('member.changeRoleSuccess'))
      roleVisible.value = false
      await load()
    } catch (error) {
      if (!(error instanceof HttpError)) {
        console.error('修改角色失败:', error)
      }
    } finally {
      submitting.value = false
    }
  }

  /**
   * 移除前必须确认，而且确认文案要说清楚**账号不会被删除**。
   *
   * 不说的话，操作者只看到「移除 张三」，很容易以为自己在删一个人的账号 ——
   * 于是该做的操作不敢做，或者做完了去找管理员「恢复账号」。
   */
  const confirmRemove = async (row: ProjectMemberResponse) => {
    try {
      await ElMessageBox.confirm(
        t('member.removeConfirm', { name: row.displayName ?? row.email }),
        t('member.remove'),
        {
          type: 'warning',
          confirmButtonText: t('member.confirm'),
          cancelButtonText: t('member.cancel')
        }
      )
    } catch {
      // 用户取消，不是错误
      return
    }

    if (!row.accountId) return
    removingAccountId.value = row.accountId
    try {
      await fetchRemoveMember(projectId.value, row.accountId)
      ElMessage.success(t('member.removeSuccess'))
      await load()
    } catch (error) {
      if (!(error instanceof HttpError)) {
        console.error('移除成员失败:', error)
      }
    } finally {
      removingAccountId.value = ''
    }
  }

  const confirmTransferOwnership = async (row: ProjectMemberResponse) => {
    if (!row.accountId) return
    try {
      await ElMessageBox.confirm(
        t('member.transferOwnerConfirm', { name: row.displayName ?? row.email }),
        t('member.transferOwner'),
        {
          type: 'warning',
          confirmButtonText: t('member.confirm'),
          cancelButtonText: t('member.cancel')
        }
      )
    } catch {
      return
    }

    transferringAccountId.value = row.accountId
    try {
      await fetchTransferOwnership(projectId.value, row.accountId)
      ElMessage.success(t('member.transferOwnerSuccess'))
      await load()
    } catch (error) {
      if (!(error instanceof HttpError)) {
        console.error('转让项目所有权失败:', error)
      }
    } finally {
      transferringAccountId.value = ''
    }
  }

  const confirmLeave = async () => {
    try {
      await ElMessageBox.confirm(t('member.leaveConfirm'), t('member.leave'), {
        type: 'warning',
        confirmButtonText: t('member.confirm'),
        cancelButtonText: t('member.cancel')
      })
    } catch {
      return
    }

    leaving.value = true
    try {
      await fetchLeaveProject(projectId.value)
      const { accessToken } = await fetchSwitchProject(null)
      if (accessToken) {
        userStore.setToken(accessToken)
        userStore.setUserInfo({
          ...userStore.info,
          currentProjectId: '',
          roles: [],
          buttons: []
        } as Api.Auth.UserInfo)
      }
      ElMessage.success(t('member.leaveSuccess'))
      window.location.assign('/project/list')
    } catch (error) {
      if (!(error instanceof HttpError)) {
        console.error('退出项目失败:', error)
      }
    } finally {
      leaving.value = false
    }
  }

  onMounted(load)
</script>

<style lang="scss" scoped>
  .project-members {
    padding: 10px;

    &__header {
      display: flex;
      gap: 10px;
      align-items: flex-start;
      justify-content: space-between;
    }

    &__actions {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
      justify-content: flex-end;
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

    &__user {
      display: flex;
      flex-direction: column;
      line-height: 1.4;
    }

    &__email {
      font-size: 12px;
      color: var(--art-text-gray-600);
    }

    &__target {
      margin: 0 0 12px;
      font-size: 13px;
      color: var(--art-text-gray-600);
    }

    &__hint {
      font-size: 12px;
      color: var(--art-text-gray-500);
    }
  }
</style>
