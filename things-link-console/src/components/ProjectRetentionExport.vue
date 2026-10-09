<template>
  <section class="project-retention-export" aria-label="删除项目留存导出">
    <ElAlert class="console-hint" type="info" show-icon :closable="false"
      >导出删除时冻结项目的留存数据，不包含所有历史遥测。下载会重新核验资格，短时地址请勿转发。</ElAlert
    >
    <ElAlert v-if="notice" type="warning" :title="notice" :closable="false" show-icon />
    <div class="console-toolbar">
      <ElButton :disabled="busy" @click="loadLatest">刷新最新任务</ElButton>
      <ElButton type="primary" :disabled="busy || pending" @click="requestExport"
        >申请留存导出</ElButton
      >
      <ElButton v-if="job?.status === 'SUCCEEDED'" :disabled="busy" @click="download"
        >下载留存包</ElButton
      >
    </div>
    <p v-if="busy">处理中…</p>
    <p v-if="loaded && !job">当前删除代次尚无本人申请的导出任务。</p>
    <template v-if="job">
      <p>任务ID：{{ job.id }}；状态：{{ statusText }}；尝试次数：{{ job.attemptCount }}</p>
      <ElAlert
        v-if="job.failureCode"
        class="console-hint"
        type="warning"
        show-icon
        :closable="false"
        >失败分类：{{ job.failureCode }}；先核对原因再重新申请。</ElAlert
      >
      <p>申请时间：{{ formatTime(job.requestedAt) }}；快照时间：{{ formatTime(job.snapshotAt) }}</p>
      <p v-if="job.status === 'SUCCEEDED'"
        >对象到期：{{ formatTime(job.expiresAt) }}（浏览器本地时间）；大小：{{
          job.objectSize
        }}字节；SHA256：{{ job.objectSha256 }}</p
      >
    </template>
  </section>
</template>
<script setup lang="ts">
  import * as api from '@/api/project-exports'
  import { HttpError } from '@/utils/http/error'
  import { useUserStore } from '@/store/modules/user'
  import { currentIdentityEpoch } from '@/utils/http/identity-scope'
  import { formatTime } from '@/utils/time'
  const props = defineProps<{ projectId: string }>()
  const user = useUserStore()
  const job = ref<api.ProjectExportResponse>()
  const busy = ref(false),
    loaded = ref(false),
    notice = ref('')
  let generation = 0
  let timer: ReturnType<typeof setTimeout> | undefined
  const pending = computed(() => job.value?.status === 'QUEUED' || job.value?.status === 'RUNNING')
  const statuses: Record<string, string> = {
    QUEUED: '排队中',
    RUNNING: '执行中',
    SUCCEEDED: '已生成',
    FAILED: '生成失败',
    EXPIRED: '对象已到期',
    CANCELLED: '任务已取消'
  }
  const statusText = computed(() => statuses[job.value?.status ?? ''] ?? job.value?.status)
  const stop = () => {
    if (timer) clearTimeout(timer)
    timer = undefined
  }
  const accept = (result: api.ProjectExportResponse | undefined, sequence: number) => {
    if (sequence !== generation) return
    if (result && (result.projectId !== props.projectId || !result.id))
      throw new Error('任务身份不匹配')
    job.value = result
    loaded.value = true
    stop()
    if (pending.value) timer = setTimeout(() => void poll(sequence), 5000)
  }
  const poll = async (sequence: number) => {
    const id = job.value?.id
    if (sequence !== generation || !id) return
    try {
      accept(await api.fetchProjectExport(props.projectId, id), sequence)
    } catch {
      if (sequence === generation) {
        stop()
        notice.value = '状态读取失败，请手动刷新；不会自动重新申请。'
      }
    }
  }
  const loadLatest = async () => {
    if (!props.projectId || busy.value) return
    const sequence = ++generation
    stop()
    busy.value = true
    loaded.value = false
    job.value = undefined
    notice.value = ''
    try {
      accept(await api.fetchLatestProjectExport(props.projectId), sequence)
    } catch {
      if (sequence === generation) notice.value = '最新任务读取失败，请重新刷新。'
    } finally {
      if (sequence === generation) busy.value = false
    }
  }
  const requestExport = async () => {
    if (busy.value || pending.value || !props.projectId) return
    const sequence = ++generation
    stop()
    busy.value = true
    notice.value = ''
    try {
      accept(await api.requestProjectExport(props.projectId), sequence)
    } catch (error) {
      if (sequence === generation) {
        if (error instanceof HttpError && !error.outcomeUnknown) {
          notice.value =
            error.code === 50019
              ? '当前套餐的导出存储额度暂不可用，请联系管理员确认配置。'
              : `申请未成功：${error.message}`
          return
        }
        notice.value = '申请结果未确认，先读取最新任务核对；不会自动再次申请。'
        try {
          accept(await api.fetchLatestProjectExport(props.projectId), sequence)
        } catch {
          /* 保留未知结果提示，交由显式刷新处理。 */
        }
      }
    } finally {
      if (sequence === generation) busy.value = false
    }
  }
  const download = async () => {
    if (busy.value || job.value?.status !== 'SUCCEEDED' || !job.value.id) return
    const sequence = generation
    busy.value = true
    notice.value = ''
    try {
      const result = await api.downloadProjectExport(props.projectId, job.value.id)
      if (sequence !== generation) return
      if (!result.url || !/^https?:\/\//.test(result.url)) throw new Error('下载地址无效')
      const link = document.createElement('a')
      link.href = result.url
      link.download = `project-${props.projectId}.zip`
      link.referrerPolicy = 'no-referrer'
      link.rel = 'noreferrer'
      link.click()
      link.remove()
    } catch {
      if (sequence === generation)
        notice.value = '下载签发未成功，请刷新任务并核对当前资格后重新操作。'
    } finally {
      if (sequence === generation) busy.value = false
    }
  }
  watch(
    () => [props.projectId, user.info.userId, user.info.tenantId, currentIdentityEpoch()],
    () => {
      generation++
      stop()
      busy.value = false
      loaded.value = false
      job.value = undefined
      notice.value = ''
      void loadLatest()
    },
    { immediate: true, flush: 'sync' }
  )
  onBeforeUnmount(() => {
    generation++
    stop()
  })
</script>
<style scoped>
  .project-retention-export {
    width: 100%;
    padding: 12px;
    overflow-wrap: anywhere;
    background: var(--el-fill-color-light);
  }
</style>
