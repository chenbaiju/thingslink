<template>
  <section v-if="canManage" class="device-claim-token">
    <h4>设备认领令牌</h4>
    <p
      >令牌用于终端用户认领本设备，签发不等于绑定给指定用户。仅无主控设备可认领；令牌仅本次响应可见，请勿转发。</p
    >
    <ElAlert v-if="notice" :title="notice" type="warning" :closable="false" />
    <ElButton :disabled="busy || projectStatus !== 'ACTIVE' || primaryKnown" @click="issue"
      >签发认领令牌</ElButton
    >
    <p v-if="primaryKnown">已知当前设备已有主控，不能再次签发认领令牌。</p>
    <template v-if="token">
      <ElInput
        :model-value="token"
        aria-label="一次性认领令牌"
        type="password"
        show-password
        readonly
        autocomplete="off"
      />
      <p>到期：{{ formatTime(expiresAt) }}（浏览器本地时间）；关闭展示不会撤销已经签发的令牌。</p>
      <ElButton @click="clear">关闭令牌展示</ElButton>
    </template>
  </section>
</template>
<script setup lang="ts">
  import { ElMessageBox } from 'element-plus'
  import { issueDeviceClaimToken } from '@/api/end-users'
  import { fetchProjects } from '@/api/project'
  import { useUserStore } from '@/store/modules/user'
  import { currentIdentityEpoch } from '@/utils/http/identity-scope'
  import { HttpError } from '@/utils/http/error'
  import { formatTime } from '@/utils/time'
  const props = defineProps<{ projectId: string; deviceId: string; primaryKnown: boolean }>()
  const user = useUserStore()
  const canManage = computed(() => (user.info.buttons || []).includes('enduser:manage'))
  const token = ref(''),
    expiresAt = ref(''),
    notice = ref(''),
    busy = ref(false),
    projectStatus = ref<string>()
  let generation = 0,
    timer: ReturnType<typeof setTimeout> | undefined
  function clear() {
    token.value = ''
    expiresAt.value = ''
    if (timer) clearTimeout(timer)
    timer = undefined
  }
  async function issue() {
    if (busy.value || !canManage.value || projectStatus.value !== 'ACTIVE' || props.primaryKnown)
      return
    const epoch = generation,
      project = props.projectId,
      device = props.deviceId
    busy.value = true
    notice.value = ''
    try {
      try {
        await ElMessageBox.confirm(
          '签发后由有效项目身份的终端用户完成认领。新签发不会承诺旧令牌失效；响应丢失不能找回原明文，请先核对。',
          '确认签发认领令牌',
          { type: 'warning' }
        )
      } catch {
        return
      }
      if (
        epoch !== generation ||
        !canManage.value ||
        projectStatus.value !== 'ACTIVE' ||
        props.primaryKnown
      )
        return
      clear()
      const result = await issueDeviceClaimToken(project, device)
      if (epoch !== generation) return
      const remaining = Date.parse(result.expiresAt || '') - Date.now()
      if (!result.token || !Number.isFinite(remaining) || remaining <= 0)
        throw new Error('认领令牌响应无效或已到期')
      token.value = result.token
      expiresAt.value = result.expiresAt!
      timer = setTimeout(clear, Math.min(remaining, 10 * 60_000))
    } catch (error) {
      if (epoch === generation) {
        clear()
        notice.value =
          error instanceof HttpError && !error.outcomeUnknown
            ? `签发未成功：${error.message}`
            : '签发结果未知或响应无效，无法回查原明文；不会自动补签，请先核对设备关系。'
      }
    } finally {
      if (epoch === generation) busy.value = false
    }
  }
  watch(
    () => [
      props.projectId,
      props.deviceId,
      props.primaryKnown,
      user.info.userId,
      user.info.tenantId,
      currentIdentityEpoch(),
      canManage.value
    ],
    async () => {
      const epoch = ++generation
      clear()
      busy.value = false
      notice.value = ''
      projectStatus.value = undefined
      if (!canManage.value || !props.projectId || !props.deviceId) return
      try {
        const result = await fetchProjects()
        if (epoch === generation)
          projectStatus.value = result.find((project) => project.id === props.projectId)?.status
      } catch {
        if (epoch === generation) notice.value = '项目资格读取失败，暂时不能签发。'
      }
    },
    { immediate: true, flush: 'sync' }
  )
  onBeforeUnmount(() => {
    generation++
    clear()
  })
</script>
