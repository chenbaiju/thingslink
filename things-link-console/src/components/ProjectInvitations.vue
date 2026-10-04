<template>
  <ElCard shadow="never" class="project-invitations">
    <template #header>
      <div class="console-toolbar">
        <h4>{{ projectId ? '项目邀请记录' : '我的项目邀请' }}</h4>
        <ElButton :loading="loading" @click="load()">刷新</ElButton>
      </div>
    </template>
    <p class="console-description"
      >邀请不会立即添加成员。收件人完成邮箱验证并明确接受后才会加入项目。</p
    >
    <ElAlert v-if="failed" type="error" :closable="false" title="邀请读取失败，请刷新重试" />
    <ElTable v-loading="loading" :data="rows" row-key="id">
      <ElTableColumn prop="projectName" label="项目" min-width="150" />
      <ElTableColumn prop="targetEmail" label="收件邮箱" min-width="210" />
      <ElTableColumn prop="role" label="角色" width="110" />
      <ElTableColumn label="状态" width="120">
        <template #default="{ row }">{{ states[row.status] ?? '状态不可用' }}</template>
      </ElTableColumn>
      <ElTableColumn v-if="projectId" label="投递" min-width="165">
        <template #default="{ row }">{{ deliveries[row.deliveryStatus] ?? '状态不可用' }}</template>
      </ElTableColumn>
      <ElTableColumn label="到期时间" min-width="185">
        <template #default="{ row }">{{ formatTime(row.expiresAt) }}</template>
      </ElTableColumn>
      <ElTableColumn label="操作" width="170">
        <template #default="{ row }">
          <template v-if="projectId && ['PENDING', 'EXPIRED'].includes(row.status)">
            <ElButton link type="primary" :disabled="!!acting" @click="act(row, 'resend')"
              >重发</ElButton
            >
            <ElButton link type="danger" :disabled="!!acting" @click="act(row, 'revoke')"
              >撤回</ElButton
            >
          </template>
          <ElButton
            v-else-if="!projectId && row.status === 'PENDING' && row.code"
            link
            type="primary"
            :loading="acting === row.id"
            :disabled="!!acting"
            @click="act(row, 'accept')"
            >确认接受</ElButton
          >
        </template>
      </ElTableColumn>
      <template #empty>
        <ElEmpty
          :description="failed ? '邀请状态不可用' : loading ? '正在读取邀请' : '暂无邀请记录'"
        />
      </template>
    </ElTable>
    <div class="console-page-actions">
      <ElButton :disabled="loading || !nextCursor" @click="load(nextCursor)">下一页</ElButton>
      <ElButton :disabled="loading || !onLaterPage" @click="load()">回到第一页</ElButton>
    </div>
  </ElCard>
</template>

<script setup lang="ts">
  import { ElMessage, ElMessageBox } from 'element-plus'
  import { useUserStore } from '@/store/modules/user'
  import { formatTime } from '@/utils/time'
  import { mittBus } from '@/utils/sys'
  import {
    fetchProjectInvitations,
    fetchMyProjectInvitations,
    acceptProjectInvitation,
    resendProjectInvitation,
    revokeProjectInvitation,
    type ProjectInvitation
  } from '@/api/project-invitations'

  const props = defineProps<{ projectId?: string }>()
  const user = useUserStore()
  const rows = ref<ProjectInvitation[]>([])
  const loading = ref(false)
  const failed = ref(false)
  const acting = ref('')
  const nextCursor = ref<string>()
  const onLaterPage = ref(false)
  let epoch = 0
  let controller: AbortController | undefined
  const states: Record<string, string> = {
    PENDING: '待接受',
    ACCEPTED: '已接受',
    REVOKED: '已撤回',
    EXPIRED: '已过期'
  }
  const deliveries: Record<string, string> = {
    INBOX: '已放入站内收件箱',
    QUEUED: '等待邮件投递，可重发恢复',
    SENT: '已交给邮件服务',
    FAILED: '投递失败，请重发'
  }

  async function load(cursor?: string) {
    const run = ++epoch
    controller?.abort()
    controller = new AbortController()
    rows.value = []
    failed.value = false
    nextCursor.value = undefined
    loading.value = true
    onLaterPage.value = !!cursor
    try {
      const page = props.projectId
        ? await fetchProjectInvitations(props.projectId, cursor, controller.signal)
        : await fetchMyProjectInvitations(cursor, controller.signal)
      if (run !== epoch) return
      if (!Array.isArray(page.items)) throw new Error('不完整的邀请响应')
      rows.value = page.items
      nextCursor.value = page.nextCursor ?? undefined
    } catch {
      if (run === epoch) failed.value = true
    } finally {
      if (run === epoch) loading.value = false
    }
  }
  async function act(row: ProjectInvitation, action: 'accept' | 'resend' | 'revoke') {
    if (!row.id || acting.value) return
    const run = epoch
    const project = props.projectId
    acting.value = row.id
    try {
      await ElMessageBox.confirm(
        action === 'accept'
          ? `接受邀请后将以 ${row.role} 身份加入 ${row.projectName}。`
          : action === 'revoke'
            ? '撤回后原邀请链接将失效。'
            : '重发后旧码失效，邀请有效期重新计算；每次重发至少间隔60秒。',
        '确认操作'
      )
      if (run !== epoch) return
      if (action === 'accept' && row.code) {
        await acceptProjectInvitation(row.id, row.code)
        mittBus.emit('projectsChanged')
      } else if (project && action === 'resend') await resendProjectInvitation(project, row.id)
      else if (project && action === 'revoke') await revokeProjectInvitation(project, row.id)
      else return
      if (run !== epoch) return
      ElMessage.success(action === 'accept' ? '已加入项目，可从项目列表或切换器进入' : '邀请已更新')
      await load()
    } catch {
      // 取消不提示错误；接口失败由统一HTTP处理，保留当前列表供重试。
    } finally {
      acting.value = ''
    }
  }
  watch(
    () => [props.projectId, user.info.userId, user.info.tenantId],
    () => {
      acting.value = ''
      void load()
    },
    { immediate: true }
  )
  onBeforeUnmount(() => {
    ++epoch
    controller?.abort()
  })
  defineExpose({ refresh: () => load() })
</script>
