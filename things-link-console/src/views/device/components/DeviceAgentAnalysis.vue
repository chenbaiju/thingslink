<template>
  <section class="agent-analysis" data-testid="agent-analysis">
    <ElDivider content-position="left">模型分析</ElDivider>
    <ElAlert type="info" :closable="false" show-icon>
      先选择证据属性并查看模型状态；Key 启用不代表分析可用，须经审核并手动确认。
      <template v-if="allowed">{{ selectionLabel }} {{ !error ? statusLabel : '' }}</template>
    </ElAlert>
    <ElAlert
      v-if="!allowed"
      type="info"
      :closable="false"
      title="仅当前项目的所有者、管理员和操作员可查看模型状态；查看者可继续读取事实证据。"
      show-icon
    />
    <template v-else>
      <div class="console-toolbar">
        <ElSelect v-model="template" :disabled="parentBusy || busy" aria-label="固定分析问题">
          <ElOption label="设备状态摘要" value="STATUS_SUMMARY" />
          <ElOption label="活动告警说明" value="ALARM_EXPLANATION" />
        </ElSelect>
        <ElButton :disabled="parentBusy || busy" :loading="busy" @click="refresh">
          查看模型状态
        </ElButton>
        <ElButton :disabled="!canSubmit" @click="submit">确认并分析一次</ElButton>
      </div>
      <ElCheckbox v-model="consented" :disabled="!admitted || parentBusy || busy">
        我确认本次使用项目 Key 分析，可能产生费用；等待失败或取消不代表未扣费，不会自动重试。
      </ElCheckbox>
      <ElAlert v-if="error" type="error" :closable="false" :title="error" show-icon />
      <AnalysisResultPanel :run="result" />
      <AnalysisRecoveryPanel
        :project-id="projectId"
        :device-id="deviceId"
        :parent-busy="parentBusy || busy"
      />
    </template>
  </section>
</template>

<script setup lang="ts">
  import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
  import { useUserStore } from '@/store/modules/user'
  import { currentIdentityEpoch } from '@/utils/http/identity-scope'
  import { analysisIntentStorage } from '@/features/agent/analysis-intent'
  import { submitNewAnalysisIntent } from '@/features/agent/analysis-submit'
  import type { AnalysisRun } from '@/features/agent/analysis-result'
  import AnalysisResultPanel from '@/components/agent/AnalysisResultPanel.vue'
  import AnalysisRecoveryPanel from '@/components/agent/AnalysisRecoveryPanel.vue'
  import {
    readAnalysisAvailability,
    type AnalysisAvailability,
    type AnalysisTemplate
  } from '@/api/assistant-analysis'

  const props = defineProps<{
    projectId: string
    deviceId: string
    modelVersionId: string
    propertyKeys: string[]
    parentBusy: boolean
  }>()
  const user = useUserStore()
  const template = ref<AnalysisTemplate>('STATUS_SUMMARY')
  const availability = ref<AnalysisAvailability>()
  const busy = ref(false)
  const consented = ref(false)
  const error = ref('')
  const result = ref<AnalysisRun>()
  const allowed = computed(() =>
    Boolean(
      user.isLogin &&
        user.info.userId &&
        user.info.tenantId &&
        props.projectId &&
        props.deviceId &&
        props.projectId === user.info.currentProjectId &&
        user.info.roles?.some((role) => ['OWNER', 'ADMIN', 'OPERATOR'].includes(role))
    )
  )
  const admitted = computed(
    () =>
      availability.value?.businessAvailable === true &&
      availability.value.reason === 'REVIEWED_CONFIGURATION_AVAILABLE'
  )
  const canSubmit = computed(
    () =>
      allowed.value &&
      admitted.value &&
      consented.value &&
      !props.parentBusy &&
      !busy.value &&
      !!props.modelVersionId &&
      props.propertyKeys.length > 0 &&
      props.propertyKeys.length <= 10
  )
  const selectionLabel = computed(() =>
    props.modelVersionId && props.propertyKeys.length
      ? `已选择 ${props.propertyKeys.length} 个属性；分析开放后将由服务端重新读取证据。`
      : ''
  )
  const statusLabel = computed(() => {
    if (busy.value) return '正在读取模型状态…'
    if (!availability.value) return ''
    if (admitted.value) return '当前条件允许发起分析；不保证余额或供应商在线，提交时将再次核对。'
    if (availability.value.reason === 'PROJECT_MODEL_CONFIGURATION_DISABLED')
      return '项目模型配置尚未启用，请联系项目管理员。'
    return availability.value.reason === 'INTERNAL_TRANSPORT_DISABLED'
      ? '模型分析服务尚未启用。'
      : '模型分析条件尚未满足，暂不可用。'
  })
  let generation = 0
  let controller: AbortController | undefined
  function clear() {
    ++generation
    controller?.abort()
    controller = undefined
    availability.value = undefined
    consented.value = false
    result.value = undefined
    error.value = ''
    busy.value = false
  }
  async function refresh() {
    if (!allowed.value || props.parentBusy || busy.value) return
    clear()
    const run = generation
    const identity = currentIdentityEpoch()
    const current = () => run === generation && identity === currentIdentityEpoch() && allowed.value
    controller = new AbortController()
    busy.value = true
    try {
      const value = await readAnalysisAvailability(props.projectId, controller.signal)
      if (current()) availability.value = value
    } catch {
      if (current()) error.value = '模型状态读取失败，请确认当前项目权限后手动重新查看。'
    } finally {
      if (current()) busy.value = false
    }
  }
  async function submit() {
    if (!canSubmit.value) return
    clear()
    const run = generation,
      identity = currentIdentityEpoch()
    const current = () =>
      run === generation &&
      identity === currentIdentityEpoch() &&
      allowed.value &&
      !props.parentBusy
    controller = new AbortController()
    busy.value = true
    let reviewed = false
    const ready = () => {
      if (!current() || !reviewed) throw new Error('STALE_ANALYSIS_SCOPE')
    }
    try {
      const fresh = await readAnalysisAvailability(props.projectId, controller.signal)
      if (!current()) return
      reviewed =
        fresh.businessAvailable === true && fresh.reason === 'REVIEWED_CONFIGURATION_AVAILABLE'
      if (!reviewed) {
        availability.value = fresh
        error.value = '当前模型条件已变化，本次未提交分析，请重新核对。'
        return
      }
      ready()
      const value = await submitNewAnalysisIntent(
        analysisIntentStorage(window.localStorage, navigator.locks),
        { accountId: user.info.userId!, tenantId: user.info.tenantId!, projectId: props.projectId },
        {
          deviceId: props.deviceId,
          expectedModelVersionId: props.modelVersionId,
          propertyKeys: [...props.propertyKeys],
          template: template.value
        },
        controller.signal,
        ready
      )
      ready()
      result.value = value
    } catch {
      if (current())
        error.value = '分析尚未取得可确认结果；已有原意图请手动核对，不要重复提交或推断零费用。'
    } finally {
      if (current()) busy.value = false
    }
  }
  function storageChanged(event: StorageEvent) {
    if (event.key === null || event.key.startsWith('tc-agent-analysis-intent:v1:')) clear()
  }
  watch(template, clear, { flush: 'sync' })
  onMounted(() => window.addEventListener('storage', storageChanged))
  watch(
    () => [
      props.projectId,
      props.deviceId,
      props.modelVersionId,
      props.propertyKeys.join(','),
      props.parentBusy,
      currentIdentityEpoch(),
      user.isLogin,
      user.info.userId,
      user.info.tenantId,
      user.info.currentProjectId,
      user.info.roles?.join(','),
      user.accessToken
    ],
    () => {
      clear()
      template.value = 'STATUS_SUMMARY'
    },
    { flush: 'sync' }
  )
  onBeforeUnmount(() => {
    clear()
    window.removeEventListener('storage', storageChanged)
  })
</script>

<style scoped lang="scss">
  .agent-analysis {
    display: grid;
    gap: 12px;
  }
</style>
