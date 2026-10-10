<template>
  <el-button
    class="console-fragment"
    v-if="canManage && !hideTrigger"
    data-testid="grants-open"
    :disabled="!available"
    @click="openDialog"
    >用户读取授权</el-button
  >
  <el-dialog
    class="console-dialog"
    v-if="canManage"
    data-testid="grants-dialog"
    v-model="opened"
    title="用户读取授权"
    width="680px"
    :close-on-click-modal="false"
    @close="closeDialog"
  >
    <slot name="context" />
    <ElAlert class="console-hint" type="info" show-icon :closable="false"
      >本操作仅允许选定项目用户读取当前看板，不创建用户、不修改项目角色，也不授予设备访问或匿名分享能力。</ElAlert
    >
    <ElAlert class="console-hint" type="info" show-icon :closable="false"
      >实际运行还须应用包含该看板、用户角色有效并满足设备授权；单独授予READ不保证完整运行可用。</ElAlert
    >
    <el-alert v-if="state.error" :title="state.error" type="error" :closable="false" show-icon />
    <el-alert v-if="state.notice" :title="state.notice" type="info" :closable="false" show-icon />
    <template v-if="!fixedUser">
      <el-button
        data-testid="grants-refresh-users"
        :disabled="!usable || busy"
        @click="grants.open()"
        >刷新用户目录</el-button
      >
      <p class="console-description" v-if="state.listLoaded && !state.users.length"
        >当前页没有项目用户。</p
      >
      <ul
        ><li v-for="user in state.users" :key="user.id">
          {{ user.displayName || user.username }}（{{ user.username }}）·
          {{ user.status === 'ACTIVE' ? '账号有效' : '账号不可写' }} /
          {{ user.roleStatus === 'ACTIVE' ? '项目角色有效' : '项目角色不可写' }}
          <el-button
            :disabled="
              !usable || busy || (!!state.pending && state.pending.intent.appUserId !== user.id)
            "
            @click="grants.selectUser(user.id)"
            >选择用户 {{ user.username }}</el-button
          >
        </li></ul
      >
      <el-button v-if="state.nextCursor" :disabled="!usable || busy" @click="grants.loadMore()"
        >下一页用户</el-button
      >
    </template>
    <section v-if="state.selectedUser">
      <ElDivider content-position="left"
        >目标用户：{{ state.selectedUser.displayName || state.selectedUser.username }}（{{
          state.selectedUser.username
        }}）</ElDivider
      >
      <ElAlert v-if="!activeUser" class="console-hint" type="info" show-icon :closable="false"
        >用户或项目角色非有效状态，仅查看历史，不允许授予或撤销。</ElAlert
      >
      <div
        class="designer-grants__status"
        v-if="state.grant"
        data-testid="grants-status"
        :data-status="state.grant.status"
        :data-revision="state.grant.revision"
      >
        <ElTag size="small" :type="state.grant.status === 'ACTIVE' ? 'success' : 'info'">
          {{ state.grant.status === 'ACTIVE' ? '已授予读取权限' : '已撤销读取权限' }}
        </ElTag>
        <span>修订版本：{{ state.grant.revision }}</span>
      </div>
      <ElAlert
        v-if="state.missingEligible"
        class="console-hint"
        type="warning"
        show-icon
        :closable="false"
        >未读到可确认的授权事实。可明确尝试首次授予，服务端仍会核对用户、角色及看板。</ElAlert
      >
      <div class="console-actions">
        <el-button
          data-testid="grants-grant"
          type="primary"
          :disabled="!canGrant"
          @click="confirmChange('ACTIVE')"
          >{{ state.missingEligible ? '尝试首次授予读取权限' : '授予读取权限' }}</el-button
        >
        <el-button
          data-testid="grants-revoke"
          :disabled="!canRevoke"
          @click="confirmChange('REVOKED')"
          >撤销读取权限</el-button
        >
      </div>
    </section>
    <section v-if="state.pending">
      <p class="console-description">待恢复原用户：{{ state.pending.intent.appUserId }}</p>
      <ElAlert
        v-if="state.pending.status === 'UNKNOWN'"
        class="console-hint"
        type="warning"
        show-icon
        :closable="false"
        >原操作结果未知；读取当前事实不能证明原操作从未执行，不会自动换用新操作。</ElAlert
      >
      <ElAlert v-else class="console-hint" type="info" show-icon :closable="false"
        >原操作已有完成记录，仍需成功读取当前事实；不能把读取失败理解为未授权。</ElAlert
      >
      <div class="console-actions">
        <el-button
          v-if="state.pending.status === 'UNKNOWN'"
          data-testid="grants-retry"
          :disabled="!usable || busy || state.retryBlocked || writable === false"
          @click="retryOriginal"
          >重试原授权操作</el-button
        >
        <el-button
          data-testid="grants-recover"
          :disabled="!usable || busy"
          @click="grants.refresh()"
          >读取原用户当前事实</el-button
        >
      </div>
    </section>
    <template #footer><el-button @click="closeDialog">关闭</el-button></template>
  </el-dialog>
</template>
<script setup lang="ts">
  import { computed, onBeforeUnmount, ref, shallowRef, watch } from 'vue'
  import { ElMessageBox } from 'element-plus'
  import { useUserStore } from '@/store/modules/user'
  import { currentIdentityEpoch } from '@/utils/http/identity-scope'
  import { createDashboardGrants, type GrantUser } from '@/features/dashboard/grant-model'
  import {
    fetchGrantUsers,
    fetchDashboardGrant,
    writeDashboardGrantIntent
  } from '@/api/dashboard-grants'
  const props = withDefaults(
    defineProps<{
      projectId: string
      dashboardId: string
      available: boolean
      canManage: boolean
      fixedUser?: GrantUser
      writable?: boolean
      hideTrigger?: boolean
    }>(),
    { writable: true, hideTrigger: false }
  )
  const emit = defineEmits<{ changed: [] }>()
  const user = useUserStore(),
    opened = ref(false),
    visible = ref(!document.hidden),
    confirming = ref(false)
  let epoch = 0
  const usable = computed(() => opened.value && visible.value && props.available && props.canManage)
  const context = () => ({
    projectId: props.projectId,
    dashboardId: props.dashboardId,
    canManage: props.canManage,
    available: usable.value,
    identity: currentIdentityEpoch()
  })
  const grants = createDashboardGrants({
    context,
    users: (projectId, cursor) =>
      props.fixedUser
        ? Promise.resolve({ items: [props.fixedUser], hasMore: false, nextCursor: null })
        : fetchGrantUsers(projectId, cursor),
    detail: fetchDashboardGrant,
    write: writeDashboardGrantIntent,
    newKey: () => crypto.randomUUID(),
    changed: (snapshot) => {
      const previous = state.value?.grant
      state.value = snapshot
      if (
        snapshot.grant &&
        (!previous ||
          previous.status !== snapshot.grant.status ||
          previous.revision !== snapshot.grant.revision)
      )
        emit('changed')
    }
  })
  const state = shallowRef(grants.getSnapshot())
  const busy = computed(
    () =>
      state.value.loading || state.value.detailLoading || state.value.writing || confirming.value
  )
  const activeUser = computed(
    () =>
      state.value.selectedUser?.status === 'ACTIVE' &&
      state.value.selectedUser.roleStatus === 'ACTIVE' &&
      !!state.value.selectedUser.role
  )
  const canWrite = computed(
    () =>
      usable.value &&
      props.writable !== false &&
      !busy.value &&
      !state.value.pending &&
      activeUser.value
  )
  const canGrant = computed(
    () => canWrite.value && (state.value.grant?.status === 'REVOKED' || state.value.missingEligible)
  )
  const canRevoke = computed(() => canWrite.value && state.value.grant?.status === 'ACTIVE')
  async function openTarget() {
    const generation = epoch
    await grants.open()
    if (generation === epoch && usable.value && props.fixedUser && !state.value.pending) {
      await grants.selectUser(props.fixedUser.id)
    }
  }
  function retryOriginal() {
    if (usable.value && !busy.value && props.writable !== false) void grants.retry()
  }
  function openDialog() {
    if (!props.available || !props.canManage) return
    opened.value = true
    void openTarget()
  }
  defineExpose({ openDialog })
  function closeDialog() {
    opened.value = false
    epoch++
    grants.suspend()
  }
  watch(
    [
      () => props.projectId,
      () => props.dashboardId,
      () => user.info.userId,
      () => user.info.tenantId,
      currentIdentityEpoch,
      () => props.fixedUser?.id
    ],
    () => {
      epoch++
      grants.reset()
      if (usable.value) void openTarget()
    },
    { flush: 'sync' }
  )
  watch(
    () => [props.available, visible.value, props.canManage],
    () => {
      epoch++
      grants.suspend()
      if (usable.value) void openTarget()
    },
    { flush: 'sync' }
  )
  watch(
    () => [
      props.fixedUser?.status,
      props.fixedUser?.role,
      props.fixedUser?.roleStatus,
      props.fixedUser?.displayName
    ],
    () => {
      epoch++
      grants.suspend()
      if (usable.value) void openTarget()
    },
    { flush: 'sync' }
  )
  const visibility = () => {
    visible.value = !document.hidden
  }
  document.addEventListener('visibilitychange', visibility)
  onBeforeUnmount(() => {
    document.removeEventListener('visibilitychange', visibility)
    epoch++
    grants.reset()
  })
  async function confirmChange(status: 'ACTIVE' | 'REVOKED') {
    if (status === 'ACTIVE' ? !canGrant.value : !canRevoke.value) return
    const before = JSON.stringify({
        context: context(),
        user: state.value.selectedUser?.id,
        revision: state.value.grant?.revision ?? '0'
      }),
      generation = epoch
    confirming.value = true
    try {
      await ElMessageBox.confirm(
        status === 'ACTIVE'
          ? '授予该用户当前看板的只读权限？这不会增加设备权限或修改项目角色。'
          : '撤销该用户当前看板的读取权限？后续运行请求将重新校验授权。',
        status === 'ACTIVE' ? '确认授予读取权限' : '确认撤销读取权限',
        {
          confirmButtonText: status === 'ACTIVE' ? '授予读取权限' : '撤销读取权限',
          cancelButtonText: '取消',
          type: 'warning'
        }
      )
      if (
        generation !== epoch ||
        !usable.value ||
        props.writable === false ||
        before !==
          JSON.stringify({
            context: context(),
            user: state.value.selectedUser?.id,
            revision: state.value.grant?.revision ?? '0'
          })
      )
        return
      await grants.change(status)
    } catch {
      /* 取消不发写请求；错误由授权模型提供固定安全文案。 */
    } finally {
      confirming.value = false
    }
  }
</script>

<style scoped lang="scss">
  .designer-grants__status {
    display: flex;
    flex-wrap: wrap;
    gap: 10px;
    align-items: center;
    margin-block: 12px;
    > span:not(.el-tag) {
      font-size: 12px;
      color: var(--el-text-color-secondary);
    }
  }
</style>
