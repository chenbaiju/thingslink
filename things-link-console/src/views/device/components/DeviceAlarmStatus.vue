<template>
  <span
    ref="element"
    class="device-alarm-status"
    :class="{ 'has-alarm': state === 'active' }"
    :title="observedAt ? `观测时间：${observedAt}` : undefined"
  >
    <ArtSvgIcon :icon="state === 'active' ? 'ri:alarm-warning-line' : 'ri:notification-off-line'" />
    {{ label }}
    <ElButton v-if="state === 'unavailable'" link size="small" @click.stop="retry">重试</ElButton>
  </span>
</template>

<script setup lang="ts">
  import { requestDeviceAlarmStatus } from '@/api/device-alarm-status'
  import { currentIdentityEpoch } from '@/utils/http/identity-scope'

  const props = defineProps<{ projectId: string; deviceId?: string; active: boolean }>()
  const element = ref<HTMLElement>()
  const visible = ref(false)
  const state = ref<'pending' | 'loading' | 'active' | 'none' | 'unavailable'>('pending')
  const label = computed(
    () =>
      ({
        pending: '待查询',
        loading: '查询中…',
        active: '有告警',
        none: '无告警',
        unavailable: '状态不可用'
      })[state.value]
  )
  let observer: IntersectionObserver | undefined
  const observedAt = ref('')
  let request: ReturnType<typeof requestDeviceAlarmStatus> | undefined
  let generation = 0
  const cancel = () => {
    generation++
    request?.cancel()
    request = undefined
    observedAt.value = ''
    if (state.value === 'loading') state.value = 'pending'
  }
  const load = async () => {
    if (!props.active || !visible.value || state.value !== 'pending') return
    const project = props.projectId
    const device = props.deviceId
    if (!project || !device) {
      state.value = 'unavailable'
      return
    }
    const token = ++generation
    const identity = currentIdentityEpoch()
    const current = () =>
      token === generation &&
      identity === currentIdentityEpoch() &&
      props.projectId === project &&
      props.deviceId === device &&
      props.active
    const currentRequest = requestDeviceAlarmStatus(project, device)
    request = currentRequest
    state.value = 'loading'
    try {
      const result = await currentRequest.result
      if (current()) {
        state.value = result.state === 'ACTIVE' ? 'active' : 'none'
        observedAt.value = result.observedAt
      }
    } catch {
      if (current()) state.value = 'unavailable'
    } finally {
      if (request === currentRequest) request = undefined
    }
  }

  const retry = () => {
    state.value = 'pending'
    void load()
  }
  watch(
    () => [props.projectId, props.deviceId],
    () => {
      cancel()
      state.value = 'pending'
      void load()
    }
  )
  watch(
    () => props.active,
    () => {
      cancel()
      state.value = 'pending'
      if (props.active) void load()
    }
  )
  watch(
    () => visible.value,
    () => {
      if (!props.active || !visible.value) cancel()
      else void load()
    }
  )
  onMounted(() => {
    if (typeof IntersectionObserver === 'undefined') {
      visible.value = true
      return
    }
    observer = new IntersectionObserver(([entry]) => {
      visible.value = !!entry?.isIntersecting
    })
    if (element.value) observer.observe(element.value)
  })
  onUnmounted(() => {
    observer?.disconnect()
    cancel()
  })
</script>

<style scoped>
  .device-alarm-status {
    display: inline-flex;
    gap: 6px;
    align-items: center;
    color: var(--el-text-color-secondary);
  }
  .device-alarm-status.has-alarm {
    color: var(--el-color-danger);
  }
</style>
