<template>
  <ElDialog
    class="console-dialog app-project-entry"
    :model-value="!!project"
    title="ThingsX App 接入"
    width="min(680px, 94vw)"
    @update:model-value="(value: boolean) => !value && emit('close')"
  >
    <ElAlert class="console-hint" type="info" show-icon :closable="false"
      >使用 Console 分配的 App 账号登录。二维码只配置项目入口，不包含密码或授权。</ElAlert
    >
    <ElForm label-position="top" @submit.prevent>
      <ElFormItem label="平台地址">
        <ElInput
          v-model="address"
          :placeholder="localAddress || 'https://平台地址'"
          maxlength="2048"
        />
      </ElFormItem>
      <ElFormItem label="项目标识">
        <ElInput :model-value="project?.projectKey || ''" readonly />
      </ElFormItem>
    </ElForm>
    <ElAlert v-if="localDevelopment" class="console-hint" type="info" show-icon :closable="false">
      本机地址 http://127.0.0.1:8080 仅适用于 Debug
      App。iOS模拟器直接连接；Android模拟器需先映射8080端口。
    </ElAlert>
    <ElAlert v-else class="console-hint" type="info" show-icon :closable="false"
      >请填写手机可访问的 HTTPS 后端地址。真机上的 localhost 不指向开发机。</ElAlert
    >
    <p v-if="!address.trim()">填写平台地址后，将自动生成项目接入二维码。</p>
    <ElAlert v-if="error" :title="error" type="warning" :closable="false" />
    <section v-if="entry" class="app-project-entry__result">
      <ElDivider content-position="left">项目接入信息</ElDivider>
      <div class="app-project-entry__content">
        <div
          v-if="entry.qrAvailable"
          class="app-project-entry__qr"
          role="img"
          aria-label="项目接入二维码"
        >
          <QrcodeVue :value="entry.payload" :size="220" level="M" render-as="svg" :margin="4" />
        </div>
        <div class="app-project-entry__text">
          <p v-if="!entry.qrAvailable"
            >接入信息较长，请复制下方文本，在 App 中选择“从接入信息导入”。</p
          >
          <label>接入信息</label>
          <p v-if="entry.omitLabel">项目名称超出入口显示限制，已省略；项目标识不变。</p>
          <ElInput
            :model-value="entry.payload"
            type="textarea"
            :rows="4"
            readonly
            aria-label="接入信息"
          />
          <p class="app-project-entry__caption"
            >扫描二维码或复制接入信息，在 ThingsX App 中导入项目。</p
          >
        </div>
      </div>
      <p v-if="copyResult" role="status">{{ copyResult }}</p>
    </section>
    <template #footer>
      <ElButton @click="emit('close')">关闭</ElButton>
      <ElButton type="primary" :disabled="!entry" @click="copy">复制接入信息</ElButton>
    </template>
  </ElDialog>
</template>

<script setup lang="ts">
  import { computed, ref, watch } from 'vue'
  import QrcodeVue from 'qrcode.vue'
  import type { ProjectResponse } from '@/api/project'
  import { appProjectEntry, defaultAppProjectAddress } from '@/utils/app-project-entry'

  const props = defineProps<{ project?: ProjectResponse }>()
  const emit = defineEmits<{ close: [] }>()
  const localAddress = defaultAppProjectAddress(window.location.hostname)
  const localDevelopment = !!localAddress
  const address = ref(localAddress)
  const copyResult = ref('')
  const result = computed(() => {
    if (!props.project || !address.value.trim()) return {}
    try {
      return { entry: appProjectEntry(props.project, address.value) }
    } catch (error) {
      return { error: error instanceof Error ? error.message : '接入信息不可用' }
    }
  })
  const entry = computed(() => result.value.entry)
  const error = computed(() => result.value.error)
  watch(
    () => props.project?.id,
    () => {
      address.value = localAddress
      copyResult.value = ''
    },
    { immediate: true }
  )
  watch(address, () => {
    copyResult.value = ''
  })
  async function copy() {
    if (!entry.value) return
    try {
      await navigator.clipboard.writeText(entry.value.payload)
      copyResult.value = '已复制接入信息'
    } catch {
      copyResult.value = '自动复制不可用，请手动选择上方文本复制。'
    }
  }
</script>

<style scoped>
  .app-project-entry__content {
    display: flex;
    gap: 20px;
    align-items: center;
  }
  .app-project-entry__qr {
    display: flex;
    flex: 0 0 220px;
    justify-content: center;
    background: white;
    border: 1px solid var(--el-border-color-lighter);
    border-radius: var(--el-border-radius-base);
  }
  .app-project-entry__qr :deep(svg) {
    max-width: 100%;
    height: auto;
  }
  .app-project-entry__text {
    flex: 1;
    min-width: 0;
  }
  .app-project-entry__text label {
    display: block;
    margin-bottom: 6px;
    font-weight: 500;
  }
  .app-project-entry__text :deep(textarea) {
    font-family: var(--el-font-family);
    overflow-wrap: anywhere;
  }
  .app-project-entry__caption {
    margin: 8px 0 0;
    font-size: 12px;
    line-height: 20px;
    color: var(--el-text-color-secondary);
  }
  @media (width <= 600px) {
    .app-project-entry__content {
      flex-direction: column;
      align-items: stretch;
    }
    .app-project-entry__qr {
      flex-basis: auto;
      align-self: center;
    }
  }
</style>
