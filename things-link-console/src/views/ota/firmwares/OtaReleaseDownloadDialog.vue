<template>
  <ElDialog
    v-model="visible"
    title="下载已发布固件"
    data-testid="ota-release-download-dialog"
    width="560px"
    destroy-on-close
  >
    <p
      >每次下载都由服务端重新核对当前资格，并读取固定发布版本。已就绪状态不代表当前可下载，也不代表设备升级资格。</p
    >
    <p v-if="reading">正在读取当前项目与固件状态…</p>
    <p v-else-if="qualified">当前项目有效，固件已就绪；申领时仍由服务端重新确权。</p>
    <ElAlert
      v-if="notice"
      :title="notice"
      type="info"
      :closable="false"
      data-testid="ota-release-download-notice"
    />
    <p v-if="expiresAt" data-testid="ota-release-download-expiry"
      >本次地址保守到期：{{ expiresAt }}</p
    >
    <template #footer>
      <ElButton @click="visible = false">关闭</ElButton>
      <ElButton
        data-testid="ota-release-download-refresh"
        :disabled="busy || reading"
        @click="refresh"
        >刷新当前资格</ElButton
      >
      <ElButton
        type="primary"
        data-testid="ota-release-download-issue"
        :disabled="!qualified || busy || reading || !permitted"
        :loading="busy"
        @click="issue"
        >{{ issueLabel }}</ElButton
      >
    </template>
  </ElDialog>
</template>

<script setup lang="ts">
  import { computed, onBeforeUnmount, ref, watch } from 'vue'
  import { createOtaReleaseDownload, fetchOtaFirmwareLifecycle } from '@/api/ota'
  import { fetchProjects } from '@/api/project'
  import { useUserStore } from '@/store/modules/user'
  import { currentIdentityEpoch } from '@/utils/http/identity-scope'
  import { HttpError } from '@/utils/http/error'
  import { otaMetadataUuid } from '@/features/ota/metadata-model'

  const props = defineProps<{
    modelValue: boolean
    projectId: string
    firmwareId: string
    authorized: boolean
  }>()
  const emit = defineEmits<{ 'update:modelValue': [boolean] }>()
  const user = useUserStore()
  const permitted = computed(
    () =>
      props.authorized &&
      otaMetadataUuid(props.projectId) &&
      otaMetadataUuid(props.firmwareId) &&
      props.projectId === user.info.currentProjectId &&
      !!user.info.userId &&
      !!user.info.tenantId &&
      user.info.roles?.some((role) => role === 'OWNER' || role === 'ADMIN') &&
      user.info.buttons?.includes('ota:deploy')
  )
  const scope = computed(() =>
    JSON.stringify([
      props.projectId,
      props.firmwareId,
      props.authorized,
      user.info.currentProjectId,
      user.info.userId,
      user.info.tenantId,
      user.info.roles,
      user.info.buttons,
      currentIdentityEpoch()
    ])
  )
  const qualified = ref(false),
    reading = ref(false),
    busy = ref(false),
    notice = ref(''),
    expiresAt = ref(''),
    phase = ref<'idle' | 'unknown' | 'pending' | 'completed' | 'issued' | 'expired'>('idle')
  // 幂等键和短地址只属于当前组件内存，不能成为模板、持久状态或错误诊断。
  let pendingKey: string | undefined,
    ticket: string | undefined,
    timer: ReturnType<typeof setTimeout> | undefined,
    generation = 0,
    sequence = 0
  const issueLabel = computed(() =>
    phase.value === 'unknown' || phase.value === 'pending'
      ? '使用原键重试'
      : phase.value === 'idle'
        ? '申领并下载'
        : '重新申领并下载'
  )
  function clearTicket() {
    ticket = undefined
    expiresAt.value = ''
    if (timer) clearTimeout(timer)
    timer = undefined
  }
  function clear() {
    generation++
    sequence++
    clearTicket()
    pendingKey = undefined
    qualified.value = false
    reading.value = false
    busy.value = false
    notice.value = ''
    phase.value = 'idle'
  }
  const visible = computed({
    get: () => props.modelValue,
    set: (value: boolean) => {
      if (!value) clear()
      emit('update:modelValue', value)
    }
  })
  function current(epoch: number, identity: string, request: number) {
    return (
      props.modelValue &&
      permitted.value &&
      generation === epoch &&
      scope.value === identity &&
      sequence === request
    )
  }
  function fields(value: unknown, names: string[]): value is Record<string, unknown> {
    return (
      !!value &&
      typeof value === 'object' &&
      !Array.isArray(value) &&
      Object.keys(value).sort().join(',') === names.sort().join(',')
    )
  }
  async function qualify(epoch: number, identity: string, request: number) {
    qualified.value = false
    reading.value = true
    try {
      const [lifecycle, projects] = await Promise.all([
        fetchOtaFirmwareLifecycle(props.projectId, props.firmwareId),
        fetchProjects()
      ])
      if (!current(epoch, identity, request)) return false
      const project = Array.isArray(projects)
        ? projects.filter((item) => item.id === props.projectId)
        : []
      qualified.value =
        fields(lifecycle, ['firmwareId', 'status', 'revision', 'deprecation', 'revocation']) &&
        lifecycle.firmwareId === props.firmwareId &&
        lifecycle.status === 'READY' &&
        typeof lifecycle.revision === 'string' &&
        /^[1-9]\d*$/.test(lifecycle.revision) &&
        BigInt(lifecycle.revision) <= 9223372036854775807n &&
        lifecycle.deprecation === null &&
        lifecycle.revocation === null &&
        project.length === 1 &&
        project[0]!.status === 'ACTIVE' &&
        ['OWNER', 'ADMIN'].includes(project[0]!.myRole ?? '')
      if (!qualified.value) {
        clearTicket()
        notice.value = '当前项目或固件状态不符合下载前置条件，请核对后刷新。'
      } else if (phase.value === 'idle') notice.value = ''
      return qualified.value
    } catch {
      if (current(epoch, identity, request)) {
        clearTicket()
        notice.value = '当前资格读取失败，请恢复登录与项目权限后刷新。'
      }
      return false
    } finally {
      if (current(epoch, identity, request)) reading.value = false
    }
  }
  async function refresh() {
    if (!props.modelValue || !permitted.value || busy.value || reading.value) return
    await qualify(generation, scope.value, ++sequence)
  }
  /** 原始字符串也需验证，避免URL解析器将控制符或非标准回环表示规范化后放行。 */
  function validTicket(
    value: unknown
  ): value is { firmwareId: string; downloadUrl: string; expiresAt: string } {
    if (
      !fields(value, ['firmwareId', 'downloadUrl', 'expiresAt']) ||
      value.firmwareId !== props.firmwareId ||
      typeof value.downloadUrl !== 'string' ||
      Array.from(value.downloadUrl).some((character) => {
        const code = character.charCodeAt(0)
        return code <= 32 || (code >= 127 && code <= 159)
      }) ||
      value.downloadUrl.includes('\\') ||
      value.downloadUrl.includes('#') ||
      typeof value.expiresAt !== 'string' ||
      !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/.test(value.expiresAt) ||
      !Number.isFinite(Date.parse(value.expiresAt)) ||
      new Date(value.expiresAt).toISOString().slice(0, 19) !== value.expiresAt.slice(0, 19)
    )
      return false
    try {
      const url = new URL(value.downloadUrl)
      return (
        !!url.hostname &&
        !url.username &&
        !url.password &&
        !value.downloadUrl.split('/')[2]?.includes('@') &&
        ((url.protocol === 'https:' && /^https:\/\//i.test(value.downloadUrl)) ||
          (url.protocol === 'http:' &&
            /^http:\/\/(?:localhost|127\.0\.0\.1|\[::1\])(?::\d+)?(?:[/?]|$)/i.test(
              value.downloadUrl
            )))
      )
    } catch {
      return false
    }
  }
  async function issue() {
    if (!qualified.value || !permitted.value || !props.modelValue || busy.value || reading.value)
      return
    const epoch = generation,
      identity = scope.value,
      request = ++sequence
    busy.value = true
    clearTicket()
    try {
      if (!(await qualify(epoch, identity, request)) || !current(epoch, identity, request)) return
      pendingKey ??= crypto.randomUUID()
      const startedAt = Date.now()
      const result = await createOtaReleaseDownload(props.projectId, props.firmwareId, pendingKey)
      if (!current(epoch, identity, request)) return
      if (!validTicket(result)) {
        phase.value = 'unknown'
        notice.value = '签发响应无法确认，地址已丢弃；请使用原键重试核对结果。'
        return
      }
      const deadline = Math.min(Date.parse(result.expiresAt), startedAt + 60_000)
      if (deadline <= Date.now()) {
        pendingKey = undefined
        phase.value = 'expired'
        notice.value = '本次地址已到期，请显式重新申领。'
        return
      }
      pendingKey = undefined
      ticket = result.downloadUrl
      expiresAt.value = new Date(deadline).toISOString()
      phase.value = 'issued'
      timer = setTimeout(() => {
        if (!props.modelValue || generation !== epoch || scope.value !== identity) return
        clearTicket()
        phase.value = 'expired'
        notice.value = '本次地址已到期，请显式重新申领。'
      }, deadline - Date.now())
      const link = document.createElement('a')
      try {
        link.href = ticket
        link.download = `firmware-${props.firmwareId}.bin`
        link.referrerPolicy = 'no-referrer'
        link.rel = 'noopener noreferrer'
        link.target = '_blank'
        link.click()
        notice.value =
          '已发起固定发布物下载；此提示不证明浏览器已收完文件。再次下载须显式重新申领。'
      } catch {
        notice.value = '本次签发已完成，但浏览器下载未能发起；如需下载，请显式重新申领。'
      } finally {
        link.remove()
      }
    } catch (error) {
      if (!current(epoch, identity, request)) return
      const code = error instanceof HttpError ? error.code : undefined
      if (!(error instanceof HttpError) || error.outcomeUnknown) {
        phase.value = 'unknown'
        notice.value = '签发结果未知，请使用原键重试；不要另建签发请求。'
      } else if (code === 10014) {
        pendingKey = undefined
        phase.value = 'completed'
        notice.value = '原签发已完成，但短时地址不能找回；如需下载，请显式重新申领。'
      } else if (code === 10010) {
        phase.value = 'pending'
        notice.value = '原签发仍在处理中，请稍后使用原键重试。'
      } else {
        pendingKey = undefined
        phase.value = 'idle'
        qualified.value = false
        notice.value = '服务端未授权本次下载，请核对发布物、有效期、项目状态与当前权限后刷新。'
      }
    } finally {
      if (current(epoch, identity, request)) busy.value = false
    }
  }
  watch(
    [scope, () => props.modelValue],
    ([identity, open], previous) => {
      clear()
      if (previous?.length && identity !== previous[0] && open) {
        emit('update:modelValue', false)
        return
      }
      if (open && permitted.value) void refresh()
      else if (open) emit('update:modelValue', false)
    },
    { immediate: true, flush: 'sync' }
  )
  onBeforeUnmount(clear)
</script>
