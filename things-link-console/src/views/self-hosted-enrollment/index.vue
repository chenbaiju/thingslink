<template>
  <div class="console-page">
    <ConsoleWorkspaceHeader
      project-style
      title="自部署授权 · 待审申请"
      description="接收现场申请与查询待审队列；登记不会自动审核或签发。"
      :links="[
        { label: '商业运营', path: '/commercial-operations', permission: 'commercial:adjust' },
        { label: '责任审核', path: '/self-hosted-review', permission: 'self_hosted:review' }
      ]"
    />
    <ElCard v-if="allowed" shadow="never" class="self-hosted-enrollment">
      <ElAlert
        type="info"
        :closable="false"
        title="这里只接收申请并查看待审队列。声明的租户尚未核验，登记不代表审核、签发或现场激活。"
        show-icon
      />
      <ElAlert
        v-if="error"
        class="spacing"
        type="warning"
        :closable="false"
        :title="error"
        show-icon
      />
      <section class="spacing">
        <ElDivider content-position="left">导入申请</ElDivider>
        <ElAlert class="console-hint" type="info" show-icon :closable="false"
          >选择 jagonzn 现场导出的 .tcshreq 文件；不会上传部署私钥目录。</ElAlert
        >
        <input
          data-testid="enrollment-file"
          type="file"
          accept=".tcshreq"
          :disabled="busy"
          @change="selectFile"
        />
        <ElSelect
          v-model="channel"
          data-testid="enrollment-channel"
          placeholder="选择申请来源"
          :disabled="busy"
        >
          <ElOption label="联网传递" value="ONLINE" />
          <ElOption label="离线介质" value="OFFLINE" />
        </ElSelect>
        <ElButton type="primary" :disabled="busy || !file || !channel" @click="receive">
          接收待审申请
        </ElButton>
        <p v-if="selectedFileName">已选择：{{ selectedFileName }}</p>
        <div v-if="registration" data-testid="enrollment-result">
          <strong>已登记或同封套重复提交 · 待审</strong>
          <p>申请：{{ registration.requestId }}</p>
          <p>部署：{{ registration.deploymentId }}</p>
          <p>声明租户：{{ registration.tenantId }}（未核验）</p>
          <p>首次来源：{{ channelLabel(registration.firstChannel) }}</p>
        </div>
      </section>
      <section class="spacing">
        <ElDivider content-position="left">待审队列</ElDivider>
        <ElButton :disabled="busy" @click="loadPage(0)">刷新第一页</ElButton>
        <ElTable :data="rows" data-testid="pending-enrollment-table" class="spacing">
          <ElTableColumn prop="requestId" label="申请 ID" min-width="280" />
          <ElTableColumn prop="deploymentId" label="部署 ID" min-width="280" />
          <ElTableColumn prop="claimedTenantId" label="声明租户 ID（未核验）" min-width="280" />
          <ElTableColumn prop="firstChannel" label="首次来源" min-width="120" />
          <ElTableColumn prop="receivedAt" label="接收时间 UTC" min-width="220" />
        </ElTable>
        <p v-if="!rows.length && !busy">当前页没有待审申请。</p>
        <div class="spacing">
          <ElButton :disabled="busy || pageIndex === 0" @click="loadPage(pageIndex - 1)"
            >上一页</ElButton
          >
          <span>第 {{ pageIndex + 1 }} 页</span>
          <ElButton :disabled="busy || rows.length < pageSize" @click="nextPage">下一页</ElButton>
        </div>
      </section>
    </ElCard>
    <ElResult
      v-else
      icon="warning"
      title="需要受控运营身份"
      sub-title="项目角色不授予待审申请接收资格。"
    />
  </div>
</template>

<script setup lang="ts">
  import ConsoleWorkspaceHeader from '@/components/business/ConsoleWorkspaceHeader.vue'
  import { computed, onBeforeUnmount, ref, watch } from 'vue'
  import { useUserStore } from '@/store/modules/user'
  import {
    fetchPendingEnrollments,
    receiveEnrollment,
    type EnrollmentChannel,
    type PendingEnrollment,
    type PendingRegistration
  } from '@/api/self-hosted-enrollment'

  const user = useUserStore()
  const allowed = computed(() => user.info.buttons?.includes('commercial:adjust') ?? false)
  const pageSize = 25
  const busy = ref(false)
  const error = ref('')
  const file = ref<File | null>(null)
  const selectedFileName = computed(() => file.value?.name ?? '')
  const channel = ref<EnrollmentChannel | ''>('')
  const registration = ref<PendingRegistration | null>(null)
  const rows = ref<PendingEnrollment[]>([])
  const pageIndex = ref(0)
  const cursors = ref<(PendingEnrollment | undefined)[]>([undefined])
  let generation = 0

  watch(
    () => [user.info.userId, allowed.value],
    () => {
      generation++
      busy.value = false
      error.value = ''
      file.value = null
      channel.value = ''
      registration.value = null
      rows.value = []
      pageIndex.value = 0
      cursors.value = [undefined]
      if (allowed.value) void loadPage(0)
    },
    { immediate: true }
  )
  onBeforeUnmount(() => generation++)

  function selectFile(event: Event) {
    const selected = (event.target as HTMLInputElement).files?.[0] ?? null
    error.value = ''
    registration.value = null
    file.value = null
    if (!selected) return
    if (
      !selected.name.toLowerCase().endsWith('.tcshreq') ||
      selected.size < 1 ||
      selected.size > 251
    ) {
      error.value = '请选择不超过 251 字节的有效 .tcshreq 申请文件。'
      return
    }
    file.value = selected
  }

  async function receive() {
    const selected = file.value
    const source = channel.value
    if (!allowed.value || busy.value || !selected || !source) return
    busy.value = true
    error.value = ''
    const current = generation
    try {
      const bytes = await selected.arrayBuffer()
      if (current !== generation || !allowed.value) return
      const result = await receiveEnrollment(bytes, source)
      if (current !== generation || !allowed.value) return
      registration.value = result
      await loadPage(0, true)
    } catch (failure) {
      if (current === generation)
        error.value =
          failure instanceof Error ? failure.message : '申请接收失败，请核对原申请后重试。'
    } finally {
      if (current === generation) busy.value = false
    }
  }

  async function loadPage(index: number, afterUpload = false) {
    if (
      !allowed.value ||
      (busy.value && !afterUpload) ||
      index < 0 ||
      index >= cursors.value.length
    )
      return
    if (!afterUpload) busy.value = true
    error.value = ''
    const current = generation
    try {
      const result = await fetchPendingEnrollments(pageSize, cursors.value[index])
      if (current !== generation || !allowed.value) return
      rows.value = result
      pageIndex.value = index
      if (index === 0) cursors.value = [undefined]
      if (result.length === pageSize) cursors.value[index + 1] = result[result.length - 1]
    } catch (failure) {
      if (current === generation)
        error.value = failure instanceof Error ? failure.message : '待审队列读取失败。'
    } finally {
      if (current === generation && !afterUpload) busy.value = false
    }
  }

  function nextPage() {
    if (rows.value.length === pageSize && cursors.value[pageIndex.value + 1])
      void loadPage(pageIndex.value + 1)
  }

  const channelLabel = (value: EnrollmentChannel) => (value === 'ONLINE' ? '联网传递' : '离线介质')
</script>

<style scoped>
  .spacing {
    margin-top: 16px;
  }
  .self-hosted-enrollment section {
    max-width: 100%;
  }
</style>
