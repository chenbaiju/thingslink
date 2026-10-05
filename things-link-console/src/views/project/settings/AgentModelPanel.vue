<template>
  <ElCard v-if="allowed" shadow="never" class="agent-model-panel" data-testid="agent-model-panel">
    <template #header><h4 class="console-heading">Agent 模型配置</h4></template>
    <p class="console-description"
      >使用本项目的 DeepSeek Key，费用由对应供应商账号承担；平台不设置模型金额上限。</p
    >
    <p class="console-description"
      >保存新 Key 后自动启用，可随时停用。保存和启用不会发起模型请求；诊断功能尚未开放。</p
    >
    <ElAlert v-if="error" :title="error" type="warning" :closable="false" show-icon />
    <div v-if="configuration" class="agent-model-panel__status" aria-live="polite">
      <span data-testid="model-status">{{
        !configuration.configured ? '未配置' : configuration.enabled ? '已启用' : '已配置，已停用'
      }}</span>
      <span>配置版本：{{ configuration.revision }}</span>
      <span v-if="projectStatus !== 'ACTIVE'">项目当前只读</span>
    </div>
    <ElForm @submit.prevent="save" label-position="top" autocomplete="off">
      <ElFormItem label="新的 DeepSeek API Key">
        <ElInput
          v-model="secret"
          type="password"
          autocomplete="new-password"
          aria-label="新的 DeepSeek API Key"
          :maxlength="4096"
          :disabled="!writable"
          placeholder="仅输入新 Key，已保存的 Key 不会显示"
        />
      </ElFormItem>
      <div class="console-toolbar">
        <ElButton type="primary" :disabled="!writable || !secret" @click="save"
          >保存并启用</ElButton
        >
        <ElButton :disabled="!writable || !configuration?.configured" @click="toggle">
          {{ configuration?.enabled ? '停用' : '启用' }}
        </ElButton>
        <ElButton type="danger" :disabled="!writable || !configuration?.configured" @click="remove"
          >移除 Key</ElButton
        >
        <ElButton :disabled="busy" @click="load">刷新状态</ElButton>
      </div>
    </ElForm>
    <p v-if="uncertain" class="console-description"
      >提交结果尚未确认。请先刷新当前项目状态，再决定是否重新操作；刷新不会重发 Key。</p
    >
  </ElCard>
</template>

<script setup lang="ts">
  import { computed, onBeforeUnmount, ref, watch } from 'vue'
  import { ElMessageBox } from 'element-plus'
  import { useUserStore } from '@/store/modules/user'
  import { fetchProjects } from '@/api/project'
  import * as api from '@/api/assistant-model'

  const user = useUserStore()
  const project = computed(() => user.info.currentProjectId ?? '')
  const allowed = computed(
    () =>
      user.isLogin &&
      !!project.value &&
      !!user.info.roles?.some((role) => role === 'OWNER' || role === 'ADMIN')
  )
  const secret = ref('')
  const configuration = ref<api.ModelConfiguration>()
  const projectStatus = ref('')
  const busy = ref(false)
  const error = ref('')
  const uncertain = ref(false)
  const writable = computed(
    () =>
      allowed.value &&
      !!configuration.value &&
      projectStatus.value === 'ACTIVE' &&
      !busy.value &&
      !uncertain.value
  )
  let generation = 0
  let controller = new AbortController()

  function clear() {
    generation++
    controller.abort()
    controller = new AbortController()
    secret.value = ''
    configuration.value = undefined
    projectStatus.value = ''
    error.value = ''
    uncertain.value = false
    busy.value = false
  }
  async function load() {
    if (!allowed.value || busy.value) return
    const epoch = generation,
      pid = project.value
    secret.value = ''
    busy.value = true
    error.value = ''
    try {
      const [value, projects] = await Promise.all([
        api.readModelConfiguration(pid, controller.signal),
        fetchProjects()
      ])
      if (epoch !== generation || pid !== project.value || !allowed.value) return
      configuration.value = value
      projectStatus.value = projects.find((item) => item.id === pid)?.status ?? ''
      uncertain.value = false
    } catch {
      if (epoch === generation) {
        configuration.value = undefined
        projectStatus.value = ''
        error.value = '无法读取当前项目模型配置，请确认权限或稍后刷新。'
      }
    } finally {
      if (epoch === generation) busy.value = false
    }
  }
  async function change(
    action: (pid: string, revision: string, signal: AbortSignal) => Promise<api.ModelConfiguration>
  ) {
    if (!writable.value || !configuration.value) return
    const epoch = generation,
      pid = project.value,
      revision = configuration.value.revision
    busy.value = true
    error.value = ''
    try {
      const value = await action(pid, revision, controller.signal)
      if (epoch === generation && pid === project.value && allowed.value)
        configuration.value = value
    } catch (failure) {
      if (epoch === generation) {
        configuration.value = undefined
        uncertain.value = true
        error.value =
          (failure as { code?: number } | null)?.code === 50061
            ? '服务器模型凭据保护不可用。请联系管理员检查主密钥配置后刷新状态；不要重复提交 Key。'
            : '操作未确认或配置版本已变化。请刷新状态；若仍失败，请联系管理员检查服务端凭据配置。'
      }
    } finally {
      if (epoch === generation) {
        secret.value = ''
        busy.value = false
      }
    }
  }
  function save() {
    if (!writable.value || !secret.value) return
    if (!/^[!-~]{1,4096}$/.test(secret.value)) {
      secret.value = ''
      error.value = 'Key 不能包含空白或非 ASCII 字符。'
      return
    }
    const input = secret.value
    secret.value = ''
    return change((pid, revision, signal) =>
      api.replaceModelCredential(pid, { expectedRevision: revision, apiKey: input }, signal)
    )
  }
  function toggle() {
    if (!writable.value || !configuration.value?.configured) return
    const enabled = !configuration.value?.enabled
    secret.value = ''
    return change((pid, revision, signal) =>
      api.setModelEnabled(pid, { expectedRevision: revision, enabled }, signal)
    )
  }
  async function remove() {
    if (!writable.value || !configuration.value?.configured) return
    const epoch = generation
    secret.value = ''
    try {
      await ElMessageBox.confirm(
        '移除本项目保存的模型 Key？之后需要重新配置才能启用。',
        '移除 Key',
        { type: 'warning' }
      )
    } catch {
      return
    }
    if (epoch !== generation || !writable.value) return
    return change((pid, revision, signal) => api.removeModelCredential(pid, revision, signal))
  }
  watch(
    () => [project.value, user.info.userId, user.info.tenantId, user.isLogin, allowed.value],
    () => {
      clear()
      if (allowed.value) void load()
    },
    { immediate: true, flush: 'sync' }
  )
  onBeforeUnmount(clear)
</script>

<style scoped lang="scss">
  .agent-model-panel {
    margin-bottom: 10px;

    &__status {
      display: flex;
      flex-wrap: wrap;
      gap: 16px;
      margin: 16px 0;
    }
  }
</style>
