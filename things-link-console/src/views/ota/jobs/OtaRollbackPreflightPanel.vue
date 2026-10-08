<template>
  <section role="region" aria-label="回退准备观察与当前评估" data-testid="ota-rollback-preflight">
    <ElDivider content-position="left">回退准备观察与当前评估</ElDivider>
    <p
      >这里只读取既有报告，不创建准备查询或触发回退。可准备分类仍不授予执行权；回退必须满足受控原子提交互斥要求。</p
    >
    <ElButton
      data-testid="ota-rollback-preflight-refresh"
      :disabled="loading || !permitted"
      @click="refresh"
      >刷新回退准备观察</ElButton
    >
    <p v-if="loading">正在读取回退准备观察…</p>
    <p v-else-if="missing" data-testid="ota-rollback-preflight-no-report"
      >当前范围没有可读取的回退准备报告。这不等于设备报告的 UNKNOWN（未知）分类。</p
    >
    <ElAlert
      v-else-if="error"
      :title="error"
      type="info"
      :closable="false"
      data-testid="ota-rollback-preflight-error"
    />
    <div v-if="snapshot" data-testid="ota-rollback-preflight-result">
      <ElAlert
        v-if="stale"
        title="按当前浏览器时间已到原截止，或服务端判定查询/报告不再当前且新鲜。以下保留当时观察与服务端重验快照，请重新读取；不能据此执行回退。"
        type="warning"
        :closable="false"
        data-testid="ota-rollback-preflight-stale"
      />
      <h4>当时观察</h4>
      <ElDescriptions :column="1" border>
        <ElDescriptionsItem label="当时分类"
          ><span data-testid="ota-rollback-observed-disposition">{{
            rollbackPreflightDispositionLabel(snapshot.observedDisposition)
          }}</span></ElDescriptionsItem
        >
        <ElDescriptionsItem label="当时原因"
          ><span data-testid="ota-rollback-observed-reason"
            >{{ snapshot.observedReason }} ·
            {{ rollbackPreflightReasonLabel(snapshot.observedReason) }}</span
          ></ElDescriptionsItem
        >
        <ElDescriptionsItem label="接纳时间（UTC）"
          ><span data-testid="ota-rollback-observed-at">{{
            snapshot.observedAt
          }}</span></ElDescriptionsItem
        >
      </ElDescriptions>
      <h4>服务端当次重验</h4>
      <ElDescriptions :column="1" border>
        <ElDescriptionsItem label="当次分类"
          ><span data-testid="ota-rollback-current-disposition">{{
            rollbackPreflightDispositionLabel(snapshot.currentDisposition)
          }}</span></ElDescriptionsItem
        >
        <ElDescriptionsItem label="当次原因"
          ><span data-testid="ota-rollback-current-reason"
            >{{ snapshot.currentReason }} ·
            {{ rollbackPreflightReasonLabel(snapshot.currentReason) }}</span
          ></ElDescriptionsItem
        >
        <ElDescriptionsItem label="重验时间（UTC）"
          ><span data-testid="ota-rollback-checked-at">{{
            snapshot.checkedAt
          }}</span></ElDescriptionsItem
        >
        <ElDescriptionsItem label="原查询截止（UTC）"
          ><span data-testid="ota-rollback-query-expiry">{{
            snapshot.queryExpiresAt
          }}</span></ElDescriptionsItem
        >
        <ElDescriptionsItem label="执行授权"
          ><span data-testid="ota-rollback-execution-authorized"
            >false（无执行权）</span
          ></ElDescriptionsItem
        >
        <ElDescriptionsItem label="仍需原子提交互斥"
          ><span data-testid="ota-rollback-atomic-fence">true</span></ElDescriptionsItem
        >
        <ElDescriptionsItem label="查询 ID">{{ snapshot.queryId }}</ElDescriptionsItem>
        <ElDescriptionsItem label="报告 ID">{{ snapshot.reportId }}</ElDescriptionsItem>
        <ElDescriptionsItem label="设备日志修订">{{
          snapshot.operationRevision
        }}</ElDescriptionsItem>
        <ElDescriptionsItem label="来源槽 → 候选槽"
          >{{ snapshot.sourceSlot }} → {{ snapshot.targetSlot }}</ElDescriptionsItem
        >
      </ElDescriptions>
    </div>
  </section>
</template>

<script setup lang="ts">
  import { computed, onBeforeUnmount, ref, watch } from 'vue'
  import { fetchOtaRollbackPreflight, type OtaRollbackPreflightResponse } from '@/api/ota'
  import { fetchProjects } from '@/api/project'
  import {
    isOtaRollbackPreflight,
    rollbackPreflightDispositionLabel,
    rollbackPreflightReasonLabel,
    rollbackPreflightStale
  } from '@/features/ota/rollback-preflight-model'
  import { otaMetadataUuid } from '@/features/ota/metadata-model'
  import { useUserStore } from '@/store/modules/user'
  import { currentIdentityEpoch } from '@/utils/http/identity-scope'
  import { HttpError } from '@/utils/http/error'
  const props = defineProps<{
    active: boolean
    projectId: string
    campaignId: string
    jobId: string
  }>()
  const user = useUserStore()
  const permitted = computed(
    () =>
      props.active &&
      otaMetadataUuid(props.projectId) &&
      otaMetadataUuid(props.campaignId) &&
      otaMetadataUuid(props.jobId) &&
      props.projectId === user.info.currentProjectId &&
      !!user.info.userId &&
      !!user.info.tenantId &&
      user.info.roles?.some((role) => role === 'OWNER' || role === 'ADMIN') &&
      user.info.buttons?.includes('ota:read')
  )
  const scope = computed(() =>
    JSON.stringify([
      props.projectId,
      props.campaignId,
      props.jobId,
      user.info.currentProjectId,
      user.info.userId,
      user.info.tenantId,
      user.info.roles,
      user.info.buttons,
      currentIdentityEpoch()
    ])
  )
  const snapshot = ref<OtaRollbackPreflightResponse>(),
    loading = ref(false),
    missing = ref(false),
    error = ref(''),
    now = ref(Date.now())
  const stale = computed(
    () => !!snapshot.value && rollbackPreflightStale(snapshot.value, now.value)
  )
  let generation = 0,
    timer: ReturnType<typeof setTimeout> | undefined
  function clear() {
    generation++
    snapshot.value = undefined
    loading.value = false
    missing.value = false
    error.value = ''
    if (timer) clearTimeout(timer)
    timer = undefined
  }
  function current(epoch: number, identity: string) {
    return permitted.value && generation === epoch && scope.value === identity
  }
  async function refresh() {
    if (!permitted.value || loading.value) return
    clear()
    const epoch = generation,
      identity = scope.value
    loading.value = true
    try {
      const projects = await fetchProjects()
      if (!current(epoch, identity)) return
      const project = Array.isArray(projects)
        ? projects.filter((item) => item.id === props.projectId)
        : []
      if (
        project.length !== 1 ||
        project[0]!.status !== 'ACTIVE' ||
        !['OWNER', 'ADMIN'].includes(project[0]!.myRole ?? '')
      ) {
        error.value = '当前项目须有效且本人须为 OWNER 或 ADMIN，不能读取回退准备观察。'
        return
      }
      const result = await fetchOtaRollbackPreflight(props.projectId, props.campaignId, props.jobId)
      if (!current(epoch, identity)) return
      if (!isOtaRollbackPreflight(result)) {
        error.value = '回退准备响应不符合公开只读合同，未展示任何资格。'
        return
      }
      snapshot.value = result
      now.value = Date.now()
      const remaining = Date.parse(result.queryExpiresAt) - now.value
      if (remaining > 0 && remaining <= 2_147_483_647)
        timer = setTimeout(() => {
          if (current(epoch, identity)) now.value = Date.now()
        }, remaining)
    } catch (failure) {
      if (!current(epoch, identity)) return
      const code = failure instanceof HttpError ? failure.code : undefined
      if (code === 70044) missing.value = true
      else if (code === 70042) error.value = '回退能力未配置或当前不可用，尚无可读取的准备资格。'
      else if (code === 401 || code === 403 || code === 70043)
        error.value = '登录或项目管理权限已变化，请重新进入项目。'
      else if (code === 50017) error.value = '项目已非活动状态，不能读取回退准备观察。'
      else error.value = '回退准备读取失败，请核对当前范围后重试。'
    } finally {
      if (current(epoch, identity)) loading.value = false
    }
  }
  watch(
    [scope, () => props.active],
    () => {
      clear()
      if (permitted.value) void refresh()
    },
    { immediate: true, flush: 'sync' }
  )
  onBeforeUnmount(clear)
</script>
