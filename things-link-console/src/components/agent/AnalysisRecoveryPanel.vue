<template>
  <section
    v-if="allowed"
    class="analysis-recovery console-feedback"
    data-testid="analysis-recovery"
  >
    <ElAlert type="info" :closable="false" show-icon>
      选题不发送数据；分析正文仅本次可见，刷新后无法恢复。事实记录与报告需手动生成。
      <template v-if="!parentBusy && !localMessage">暂无待核对调用。</template>
    </ElAlert>
    <template v-if="localMessage">
      <ElDivider content-position="left">原分析调用核对</ElDivider>
      <p>{{ localMessage }}</p>
    </template>
    <template v-if="pending">
      <ElButton :disabled="busy || parentBusy" :loading="busy" @click="lookup">
        核对原调用状态
      </ElButton>
      <p v-if="call">调用编号：{{ call.id }}；{{ statusLabel }}</p>
      <ElAlert class="console-hint" type="info" show-icon :closable="false"
        >此操作只查询原调用，不重新分析。刷新后不能恢复模型正文，未知结果不能视为零费用。</ElAlert
      >
      <template v-if="canForget">
        <ElCheckbox v-model="confirmed" :disabled="busy || parentBusy">
          我已记录核对结果，理解清除本地意图不会取消远端调用，后续新分析可能再次产生费用。
        </ElCheckbox>
        <ElButton :disabled="!confirmed || busy || parentBusy" @click="forget">
          确认处置本地意图
        </ElButton>
      </template>
      <ElAlert v-else class="console-hint" type="info" show-icon :closable="false"
        >保留原意图，待原调用结束或原键到期并经过执行窗口后，再确认处置。</ElAlert
      >
    </template>
    <ElAlert v-if="error" type="warning" :closable="false" :title="error" show-icon />
  </section>
</template>

<script setup lang="ts">
  import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
  import { useUserStore } from '@/store/modules/user'
  import { currentIdentityEpoch } from '@/utils/http/identity-scope'
  import {
    analysisIntentStorage,
    type AnalysisIntent,
    type AnalysisScope
  } from '@/features/agent/analysis-intent'
  import {
    mayForgetAnalysisIntent,
    recoverAnalysisIntent
  } from '@/features/agent/analysis-recovery'
  import type { AnalysisCall } from '@/api/assistant-analysis'

  const props = defineProps<{ projectId: string; deviceId: string; parentBusy: boolean }>()
  const user = useUserStore()
  const allowed = computed(() =>
    Boolean(
      user.isLogin &&
        user.info.userId &&
        user.info.tenantId &&
        props.deviceId &&
        props.projectId === user.info.currentProjectId &&
        user.info.roles?.some((role) => ['OWNER', 'ADMIN', 'OPERATOR'].includes(role))
    )
  )
  const pending = ref<AnalysisIntent>()
  const call = ref<AnalysisCall>()
  const busy = ref(false)
  const confirmed = ref(false)
  const error = ref('')
  const localMessage = ref('')
  const now = ref(Date.now())
  const canForget = computed(
    () => !!pending.value && mayForgetAnalysisIntent(pending.value, call.value, now.value)
  )
  const statusLabel = computed(() => {
    switch (call.value?.status) {
      case 'SUCCEEDED':
        return '已结束（成功），状态查询不返回正文。'
      case 'FAILED':
        return '已结束（失败），不据此推断费用。'
      case 'UNKNOWN':
        return '执行结果未知，不能推断零费用。'
      default:
        return '仍待处理，请保留原意图。'
    }
  })
  let generation = 0
  let controller: AbortController | undefined
  function store() {
    return analysisIntentStorage(window.localStorage, navigator.locks)
  }
  function scope(): AnalysisScope {
    return {
      accountId: user.info.userId!,
      tenantId: user.info.tenantId!,
      projectId: props.projectId
    }
  }
  function clear() {
    ++generation
    controller?.abort()
    controller = undefined
    pending.value = undefined
    call.value = undefined
    busy.value = false
    confirmed.value = false
    error.value = ''
    localMessage.value = ''
  }
  function local() {
    clear()
    now.value = Date.now()
    if (!allowed.value || props.parentBusy) return
    try {
      const loaded = store().load(scope())
      if (loaded.kind === 'missing') return
      else if (loaded.kind === 'other-scope' || loaded.intent.request.deviceId !== props.deviceId)
        localMessage.value = '当前账号在其他范围有待核对意图，请回到原项目和设备处理。'
      else {
        pending.value = loaded.intent
        localMessage.value = '当前设备有待核对的原分析意图，请先核对，避免重复提交。'
      }
    } catch {
      localMessage.value = '本地分析意图暂时无法核对，请保留记录，不要重复提交。'
    }
  }
  function current(run: number, identity: number) {
    return (
      run === generation &&
      identity === currentIdentityEpoch() &&
      allowed.value &&
      !props.parentBusy
    )
  }
  async function lookup() {
    if (!allowed.value || props.parentBusy || busy.value || !pending.value) return
    const expected = structuredClone({
      ...pending.value,
      request: { ...pending.value.request, propertyKeys: [...pending.value.request.propertyKeys] }
    })
    const run = generation,
      identity = currentIdentityEpoch(),
      selected = scope()
    controller = new AbortController()
    busy.value = true
    call.value = undefined
    confirmed.value = false
    error.value = ''
    now.value = Date.now()
    const assertCurrent = () => {
      if (!current(run, identity)) throw new Error('STALE_ANALYSIS_SCOPE')
    }
    try {
      const value = await recoverAnalysisIntent(
        store(),
        selected,
        controller.signal,
        assertCurrent,
        expected
      )
      assertCurrent()
      call.value = value
    } catch {
      if (current(run, identity))
        error.value =
          '尚未取得可确认的原调用状态；原意图未自动清除。未找到记录不代表未消费，请勿重复提交。'
    } finally {
      if (current(run, identity)) {
        busy.value = false
        now.value = Date.now()
      }
    }
  }
  async function forget() {
    if (
      !allowed.value ||
      props.parentBusy ||
      busy.value ||
      !pending.value ||
      !confirmed.value ||
      !canForget.value
    )
      return
    const expected = pending.value,
      selected = scope(),
      run = generation,
      identity = currentIdentityEpoch()
    busy.value = true
    error.value = ''
    const assertCurrent = () => {
      if (
        !current(run, identity) ||
        !confirmed.value ||
        !mayForgetAnalysisIntent(expected, call.value, Date.now())
      )
        throw new Error('STALE_ANALYSIS_DISPOSITION')
    }
    try {
      await store().forget(selected, expected, assertCurrent)
      assertCurrent()
      local()
      localMessage.value = '已处置这一条本地意图；远端调用和台账未改变，没有发起新的分析。'
    } catch {
      if (current(run, identity)) error.value = '本地处置未能确认，请重新核对；远端调用不受影响。'
    } finally {
      if (current(run, identity)) busy.value = false
    }
  }
  function changed(event: StorageEvent) {
    if (event.key === null || event.key.startsWith('tc-agent-analysis-intent:v1:')) local()
  }
  watch(
    () => [
      props.projectId,
      props.deviceId,
      props.parentBusy,
      user.isLogin,
      user.info.userId,
      user.info.tenantId,
      user.info.currentProjectId,
      user.info.roles?.join(','),
      user.accessToken,
      currentIdentityEpoch()
    ],
    local,
    { immediate: true, flush: 'sync' }
  )
  onMounted(() => window.addEventListener('storage', changed))
  onBeforeUnmount(() => {
    clear()
    window.removeEventListener('storage', changed)
  })
</script>

<style scoped lang="scss">
  .analysis-recovery {
    display: grid;
    gap: 12px;
  }
</style>
