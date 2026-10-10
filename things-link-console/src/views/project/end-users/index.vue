<template>
  <div class="console-page project-end-users">
    <ConsoleWorkspaceHeader
      project-style
      title="项目终端用户"
      description="为应用使用者管理账号、项目角色、设备绑定与看板授权。"
    />
    <ElAlert
      class="project-end-users__notice"
      title="终端用户使用独立登录身份。目录只列本项目已分配角色的账号；账号状态与项目角色状态分别展示。"
      type="info"
      :closable="false"
      show-icon
    />
    <ElAlert v-if="notice" :title="notice" type="warning" :closable="false" />
    <ElAlert v-if="!projectId" title="请先进入项目" type="info" :closable="false" />
    <template v-else>
      <ElCard v-if="canManage" shadow="never">
        <ElAlert
          class="project-end-users__notice"
          title="预置账号与查找"
          description="预置只创建账号，不自动分配项目角色。若用户名已存在或申请结果未知，请按精确用户名找回并核对身份。"
          type="info"
          :closable="false"
          show-icon
        />
        <ElForm class="end-user-provision" label-position="top" @submit.prevent>
          <ElFormItem label="精确用户名"
            ><ElInput
              v-model="form.username"
              aria-label="精确用户名"
              maxlength="64"
              :disabled="busy"
              autocomplete="off"
          /></ElFormItem>
          <ElFormItem label="显示名称"
            ><ElInput
              v-model="form.displayName"
              aria-label="显示名称"
              maxlength="128"
              :disabled="busy"
          /></ElFormItem>
          <ElFormItem label="初始口令（至少8位，仅预置时使用）"
            ><ElInput
              v-model="form.password"
              aria-label="初始口令"
              type="password"
              maxlength="64"
              :disabled="busy"
              autocomplete="new-password"
          /></ElFormItem>
          <div class="console-actions">
            <ElButton :disabled="busy || !form.username.trim()" @click="lookup">查找账号</ElButton>
            <ElButton
              type="primary"
              :disabled="busy || !writable || !form.username.trim() || form.password.length < 8"
              @click="provision"
              >预置账号</ElButton
            >
          </div>
        </ElForm>
      </ElCard>
      <ElCard v-if="canManage && selected" class="end-user-selection" shadow="never">
        <ElDivider content-position="left">当前账号</ElDivider>
        <div class="end-user-selection__identity">
          <strong>{{ selected.username }}</strong>
          <span class="end-user-selection__id">用户 ID：{{ selected.id }}</span>
        </div>
        <div class="end-user-selection__statuses">
          <span
            >账号状态
            <ElTag :type="selected.status === 'ACTIVE' ? 'success' : 'info'">{{
              selected.status
            }}</ElTag></span
          >
          <span
            >本项目角色 <ElTag>{{ selected.role || '尚未分配' }}</ElTag></span
          >
          <span
            >项目角色状态
            <ElTag :type="selected.roleStatus === 'ACTIVE' ? 'success' : 'info'">{{
              selected.roleStatus || '尚未分配'
            }}</ElTag></span
          >
        </div>
        <div class="end-user-selection__actions">
          <label>目标项目角色</label>
          <ElSelect v-model="selectedRole" aria-label="目标项目角色" :disabled="busy || !writable">
            <ElOption v-for="role in roles" :key="role" :label="roleLabel(role)" :value="role" />
          </ElSelect>
          <ElButton
            type="primary"
            :disabled="busy || !writable || (!!selected.role && selected.role === selectedRole)"
            @click="changeRole"
            >{{ selected.role ? '修改项目角色' : '分配项目角色' }}</ElButton
          >
          <ElButton
            v-if="selected.roleStatus === 'ACTIVE'"
            :disabled="busy || !writable"
            @click="changeStatus('suspend')"
            >停用项目角色</ElButton
          >
          <ElButton
            v-if="selected.roleStatus === 'DISABLED'"
            :disabled="busy || !writable"
            @click="changeStatus('restore')"
            >恢复项目角色</ElButton
          >
        </div>
        <ElAlert class="console-hint" type="info" show-icon :closable="false"
          >这些操作只影响本项目。恢复角色不会重新打开停用时关闭的设备关系。</ElAlert
        >
      </ElCard>
      <ElCard v-if="canManage && selected?.role" shadow="never">
        <EndUserNotificationContact
          :project-id="projectId"
          :app-user-id="selected.id || ''"
          :allow-write="writable && !busy"
        />
      </ElCard>
      <ElCard v-if="selected" shadow="never">
        <EndUserDevices
          :project-id="projectId"
          :app-user-id="selected.id || ''"
          :allow-write="writable && !busy"
        />
      </ElCard>
      <ElCard v-if="canManage && selected" shadow="never">
        <EndUserDashboardGrants
          :project-id="projectId"
          :account="selected"
          :allow-write="writable && !busy"
        />
      </ElCard>
      <ElCard shadow="never">
        <ElDivider content-position="left">本项目角色目录</ElDivider>
        <ElTable v-loading="loading" :data="rows" row-key="id">
          <ElTableColumn prop="username" label="用户名" min-width="160" />
          <ElTableColumn prop="displayName" label="显示名称" min-width="160" />
          <ElTableColumn prop="status" label="账号状态" min-width="120" />
          <ElTableColumn prop="role" label="项目角色" min-width="240" show-overflow-tooltip>
            <template #default="{ row }">{{ roleLabel(row.role) }}</template>
          </ElTableColumn>
          <ElTableColumn prop="roleStatus" label="项目角色状态" min-width="140" />
          <ElTableColumn label="操作" width="110"
            ><template #default="{ row }"
              ><ElButton :disabled="busy" @click="select(row)">{{
                canManage ? '管理角色' : '查看设备'
              }}</ElButton></template
            ></ElTableColumn
          >
          <template #empty
            ><ElEmpty
              :description="
                failed
                  ? '目录读取失败，请重新进入页面'
                  : loading
                    ? '正在读取'
                    : '本项目尚无已分配角色的终端用户'
              "
          /></template>
        </ElTable>
        <div
          class="project-end-users__pagination"
          role="navigation"
          aria-label="本项目角色目录分页"
        >
          <ElButton :disabled="busy || loading || pageIndex === 0" @click="load(pageIndex - 1)"
            >上一页</ElButton
          >
          <span>第 {{ pageIndex + 1 }} 页</span>
          <ElButton :disabled="busy || loading || failed || !nextCursor" @click="next"
            >下一页</ElButton
          >
        </div>
      </ElCard>
    </template>
  </div>
</template>
<script setup lang="ts">
  import ConsoleWorkspaceHeader from '@/components/business/ConsoleWorkspaceHeader.vue'
  import { ElMessageBox } from 'element-plus'
  import * as api from '@/api/end-users'
  import EndUserDevices from './EndUserDevices.vue'
  import EndUserNotificationContact from './EndUserNotificationContact.vue'
  import EndUserDashboardGrants from './EndUserDashboardGrants.vue'
  import { fetchProjects } from '@/api/project'
  import { HttpError } from '@/utils/http/error'
  import { currentIdentityEpoch } from '@/utils/http/identity-scope'
  import { useUserStore } from '@/store/modules/user'
  const user = useUserStore()
  const projectId = computed(() => user.info.currentProjectId || '')
  const canManage = computed(() => (user.info.buttons || []).includes('enduser:manage'))
  const projectStatus = ref<string>()
  const writable = computed(() => projectStatus.value === 'ACTIVE' && canManage.value)
  const roles = ['APP_ADMIN', 'MAINTAINER', 'OPERATOR', 'OBSERVER'] as const
  const roleNames: Record<api.EndUserRole, string> = {
    APP_ADMIN: '应用管理员',
    MAINTAINER: '维护者',
    OPERATOR: '操作员',
    OBSERVER: '观察者'
  }
  const roleLabel = (role?: string | null) =>
    role ? `${roleNames[role as api.EndUserRole] || '未知角色'}（${role}）` : '尚未分配'
  const selectedRole = ref<api.EndUserRole>('OBSERVER')
  const form = reactive({ username: '', displayName: '', password: '' })
  const rows = ref<api.EndUser[]>([]),
    selected = ref<api.EndUser>()
  const busy = ref(false),
    loading = ref(false),
    failed = ref(false),
    notice = ref('')
  const pageIndex = ref(0),
    nextCursor = ref<string>()
  let cursors: (string | undefined)[] = [undefined],
    identity = 0,
    read = 0
  const valid = (row: api.EndUser) =>
    !!row.id &&
    !!row.username &&
    !!row.status &&
    (!row.role || roles.includes(row.role as (typeof roles)[number])) &&
    (!row.roleStatus || ['ACTIVE', 'DISABLED'].includes(row.roleStatus))
  const select = (row: api.EndUser) => {
    if (!busy.value && valid(row)) {
      selected.value = row
      selectedRole.value = (row.role as api.EndUserRole) || 'OBSERVER'
    }
  }
  async function load(index: number) {
    const sequence = ++read,
      epoch = identity,
      id = projectId.value
    rows.value = []
    projectStatus.value = undefined
    nextCursor.value = undefined
    failed.value = false
    loading.value = true
    pageIndex.value = index
    if (!id) {
      loading.value = false
      return
    }
    try {
      const [page, projects] = await Promise.all([
        api.fetchEndUsers(id, cursors[index]),
        fetchProjects()
      ])
      if (sequence !== read || epoch !== identity) return
      if (
        !Array.isArray(page.items) ||
        page.items.some((row) => !valid(row) || !row.role || !row.roleStatus)
      )
        throw new Error('角色目录无效')
      projectStatus.value = projects.find((project) => project.id === id)?.status
      rows.value = page.items
      nextCursor.value = page.nextCursor || undefined
    } catch {
      if (sequence === read && epoch === identity) {
        failed.value = true
        projectStatus.value = undefined
      }
    } finally {
      if (sequence === read && epoch === identity) loading.value = false
    }
  }
  function refresh() {
    cursors = [undefined]
    void load(0)
  }
  function next() {
    if (!busy.value && !loading.value && nextCursor.value) {
      cursors[pageIndex.value + 1] = nextCursor.value
      void load(pageIndex.value + 1)
    }
  }
  async function lookup() {
    if (busy.value || !canManage.value || !form.username.trim()) return
    const epoch = identity,
      id = projectId.value,
      name = form.username.trim()
    busy.value = true
    selected.value = undefined
    form.password = ''
    notice.value = ''
    try {
      const row = await api.lookupEndUser(id, name)
      if (epoch !== identity) return
      if (!valid(row) || row.username !== name.toLowerCase()) throw new Error('查找响应无效')
      selected.value = row
      selectedRole.value = (row.role as api.EndUserRole) || 'OBSERVER'
    } catch (error) {
      if (epoch === identity)
        notice.value =
          error instanceof HttpError && error.code === 60001
            ? '此项目归属租户中未找到该用户名。'
            : '查找失败，请核对权限后重试。'
    } finally {
      if (epoch === identity) busy.value = false
    }
  }
  async function provision() {
    if (busy.value || !writable.value || !form.username.trim() || form.password.length < 8) return
    const epoch = identity,
      id = projectId.value,
      body = { ...form, username: form.username.trim() }
    busy.value = true
    selected.value = undefined
    notice.value = ''
    form.password = ''
    try {
      const row = await api.provisionEndUser(id, body)
      if (epoch !== identity) return
      if (!valid(row) || row.role || row.username !== body.username.toLowerCase())
        throw new Error('预置响应无效')
      selected.value = row
      selectedRole.value = 'OBSERVER'
      notice.value = '账号已预置，尚未分配项目角色。请核对后明确分配。'
    } catch (error) {
      if (epoch === identity)
        notice.value =
          error instanceof HttpError && !error.outcomeUnknown
            ? `${error.message}；可按精确用户名查找已有账号。`
            : '预置结果未知，请按原用户名查找核对；不会自动重新预置。'
    } finally {
      body.password = ''
      if (epoch === identity) busy.value = false
    }
  }
  async function write(action: 'role' | 'suspend' | 'restore') {
    const target = selected.value
    if (busy.value || !writable.value || !target?.id || !target.username) return
    const epoch = identity,
      id = projectId.value,
      role = selectedRole.value
    busy.value = true
    notice.value = ''
    try {
      const text =
        action === 'suspend'
          ? '停用会关闭本项目的有效设备关系；恢复角色不会重新打开这些关系。'
          : action === 'restore'
            ? '只恢复本项目角色，不恢复旧设备关系，也不改变账号登录状态。'
            : `确认将本项目角色设为${role}？`
      try {
        await ElMessageBox.confirm(text, '确认终端用户项目操作', { type: 'warning' })
      } catch {
        return
      }
      if (epoch !== identity || !writable.value) return
      try {
        if (action === 'role')
          await (target.role ? api.updateEndUserRole : api.assignEndUserRole)(id, target.id, role)
        else await api.setEndUserRoleStatus(id, target.id, action)
        if (epoch !== identity) return
        notice.value = '操作完成，已重新读取当前项目事实。'
      } catch (error) {
        if (epoch !== identity) return
        notice.value =
          error instanceof HttpError && !error.outcomeUnknown
            ? `操作未成功：${error.message}`
            : '操作结果未知，先读取当前事实核对；不会自动重试。'
      }
      // 同一用户回读期间保留子面板内的未知授权意图，写入口由busy禁用。
      const row = await api.lookupEndUser(id, target.username)
      if (epoch !== identity) return
      if (!valid(row) || row.id !== target.id || row.username !== target.username)
        throw new Error('角色响应无效')
      selected.value = row
      selectedRole.value = (row.role as api.EndUserRole) || 'OBSERVER'
      refresh()
    } catch {
      if (epoch === identity) {
        selected.value = undefined
        notice.value += ' 最新角色读取失败，请按用户名重新查找。'
      }
    } finally {
      if (epoch === identity) busy.value = false
    }
  }
  const changeRole = () => void write('role')
  const changeStatus = (action: 'suspend' | 'restore') => void write(action)
  watch(
    () => [
      projectId.value,
      user.info.userId,
      user.info.tenantId,
      currentIdentityEpoch(),
      canManage.value
    ],
    () => {
      identity++
      read++
      busy.value = false
      loading.value = false
      selected.value = undefined
      projectStatus.value = undefined
      notice.value = ''
      form.username = ''
      form.displayName = ''
      form.password = ''
      refresh()
    },
    { immediate: true, flush: 'sync' }
  )
  onBeforeUnmount(() => {
    identity++
    read++
    form.password = ''
  })
</script>

<style scoped lang="scss">
  .end-user-provision {
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 0 16px;
    :deep(.el-form-item__label) {
      height: auto;
      margin-bottom: 6px;
      line-height: 20px;
    }
    .console-actions {
      grid-column: 1 / -1;
    }
  }
  .end-user-selection__identity {
    display: flex;
    flex-wrap: wrap;
    gap: 6px 16px;
    align-items: baseline;
    margin-bottom: 12px;
    strong {
      font-size: 16px;
    }
  }
  .end-user-selection__id {
    font-size: 12px;
    color: var(--el-text-color-secondary);
    overflow-wrap: anywhere;
  }
  .end-user-selection__statuses {
    display: flex;
    flex-wrap: wrap;
    gap: 12px 24px;
    margin-bottom: 16px;
    > span {
      display: flex;
      gap: 8px;
      align-items: center;
    }
  }
  .end-user-selection__actions {
    display: flex;
    flex-wrap: wrap;
    gap: 10px;
    align-items: center;
    margin-bottom: 12px;
    > .el-select {
      width: 280px;
      max-width: 100%;
    }
    > .el-button {
      margin: 0;
    }
  }
  @media (width <= 680px) {
    .end-user-provision {
      grid-template-columns: 1fr;
    }
  }

  .project-end-users__pagination {
    box-sizing: border-box;
    display: flex;
    flex-wrap: wrap;
    gap: 10px;
    align-items: center;
    justify-content: flex-end;
    width: 100%;
    padding-block: 5px;
    margin-top: 5px;

    > .el-button {
      margin: 0;
    }
  }

  .project-end-users__notice {
    margin-bottom: 10px;

    :deep(.el-alert__title),
    :deep(.el-alert__description) {
      font-size: 12px;
      font-weight: normal;
      line-height: 20px;
    }
    :deep(.el-alert__icon) {
      width: 14px;
      height: 14px;
      font-size: 14px;
    }
  }
</style>
