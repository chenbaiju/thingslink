<template>
  <ElDialog
    v-model="visible"
    title="公开发布证明"
    width="min(760px, 94vw)"
    destroy-on-close
    data-testid="ota-release-proof-dialog"
  >
    <p class="release-proof__boundary"
      >材料由服务端复核当前管理读取资格后提供。公钥本身不是设备信任来源，展示不代表设备验签通过或获得升级资格。</p
    >
    <p v-if="busy" role="status">正在读取当前资格与发布证明…</p>
    <ElAlert
      v-if="notice"
      :title="notice"
      :closable="false"
      type="warning"
      data-testid="ota-release-proof-notice"
    />
    <div v-if="proof" class="release-proof" data-testid="ota-release-proof-content">
      <dl class="release-proof__summary">
        <dt>固件 ID</dt><dd>{{ proof.firmwareId }}</dd> <dt>发布 ID</dt
        ><dd>{{ proof.publicationId }}</dd> <dt>发布时间</dt><dd>{{ proof.releaseCreatedAt }}</dd>
        <dt>制品大小</dt><dd>{{ proof.artifactSize }} 字节</dd> <dt>制品 SHA-256</dt
        ><dd>{{ proof.artifactSha256 }}</dd> <dt>签名协议</dt><dd>{{ proof.signatureProfile }}</dd>
        <dt>公钥指纹</dt><dd>{{ proof.keyFingerprint }}</dd>
      </dl>
      <details class="release-proof__raw">
        <summary>查看原始公开材料（Base64）</summary>
        <p>保留服务端返回的精确编码。Manifest 为规范 UTF-8 字节，未重新排版或签名。</p>
        <label
          >Manifest<textarea readonly :value="proof.manifestBase64" aria-label="Manifest Base64" />
        </label>
        <label
          >签名<textarea readonly :value="proof.signatureBase64" aria-label="签名 Base64" />
        </label>
        <label
          >发布公钥 SPKI<textarea
            readonly
            :value="proof.publicKeySpkiBase64"
            aria-label="发布公钥 SPKI Base64"
          />
        </label>
      </details>
      <p v-if="copyNotice" role="status">{{ copyNotice }}</p>
    </div>
    <template #footer>
      <ElButton @click="visible = false">关闭</ElButton>
      <ElButton :disabled="!proof || busy" data-testid="ota-release-proof-copy" @click="copyProof"
        >复制公开证明</ElButton
      >
      <ElButton
        :disabled="!permitted || busy"
        :loading="busy"
        data-testid="ota-release-proof-refresh"
        @click="readProof"
        >刷新证明</ElButton
      >
    </template>
  </ElDialog>
</template>

<script setup lang="ts">
  import { computed, onBeforeUnmount, ref, watch } from 'vue'
  import { fetchOtaRelease, fetchOtaFirmwareLifecycle, type OtaReleaseResponse } from '@/api/ota'
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
  const proof = ref<OtaReleaseResponse>()
  const busy = ref(false),
    notice = ref(''),
    copyNotice = ref('')
  let generation = 0
  function clear() {
    generation++
    proof.value = undefined
    busy.value = false
    notice.value = ''
    copyNotice.value = ''
  }
  const visible = computed({
    get: () => props.modelValue,
    set: (value: boolean) => {
      if (!value) clear()
      emit('update:modelValue', value)
    }
  })
  function current(epoch: number, identity: string) {
    return props.modelValue && permitted.value && generation === epoch && scope.value === identity
  }
  /** 只接受公开闭集响应；校验格式不等于完成密码学验签。 */
  function validProof(value: OtaReleaseResponse) {
    const fields = [
      'firmwareId',
      'publicationId',
      'manifestBase64',
      'signatureBase64',
      'publicKeySpkiBase64',
      'signatureProfile',
      'keyFingerprint',
      'artifactSize',
      'artifactSha256',
      'releaseCreatedAt'
    ]
    const base64 = (text: unknown) =>
      typeof text === 'string' &&
      text.length > 0 &&
      /^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(text)
    return (
      !!value &&
      Object.keys(value).sort().join(',') === fields.sort().join(',') &&
      Object.values(value).every((field) => typeof field === 'string' && field.length > 0) &&
      value.firmwareId === props.firmwareId &&
      otaMetadataUuid(value.publicationId) &&
      base64(value.manifestBase64) &&
      base64(value.signatureBase64) &&
      base64(value.publicKeySpkiBase64) &&
      ['TC_OTA_ED25519_V1', 'TC_OTA_ES256_P1363_V1'].includes(value.signatureProfile) &&
      /^[0-9a-f]{64}$/.test(value.keyFingerprint) &&
      /^[0-9a-f]{64}$/.test(value.artifactSha256) &&
      /^[1-9][0-9]{0,15}$/.test(value.artifactSize) &&
      /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z$/.test(value.releaseCreatedAt) &&
      Number.isFinite(Date.parse(value.releaseCreatedAt))
    )
  }
  async function readProof() {
    if (!props.modelValue || !permitted.value || busy.value) return
    clear()
    const epoch = generation,
      identity = scope.value
    busy.value = true
    try {
      const [lifecycle, projects] = await Promise.all([
        fetchOtaFirmwareLifecycle(props.projectId, props.firmwareId),
        fetchProjects()
      ])
      if (!current(epoch, identity)) return
      const project = projects.filter((item) => item.id === props.projectId)
      if (
        lifecycle.firmwareId !== props.firmwareId ||
        lifecycle.status !== 'READY' ||
        project.length !== 1 ||
        project[0]!.status !== 'ACTIVE' ||
        !['OWNER', 'ADMIN'].includes(project[0]!.myRole ?? '')
      ) {
        notice.value = '当前项目、角色或固件状态不符合读取条件，请核对后刷新。'
        return
      }
      const result = await fetchOtaRelease(props.projectId, props.firmwareId)
      if (!current(epoch, identity)) return
      if (!validProof(result)) {
        notice.value = '公开发布证明响应无法确认，请刷新重试。'
        return
      }
      proof.value = result
    } catch (error) {
      if (!current(epoch, identity)) return
      const code = error instanceof HttpError ? error.code : undefined
      notice.value =
        code === 70021
          ? '发布证明不存在或当前不可见，请核对固件后刷新。'
          : code === 70022
            ? '发布证明当前不可读取，请核对发布物和信任配置后刷新。'
            : code === 70024
              ? '当前角色无权读取发布证明，请恢复项目管理权限后刷新。'
              : '发布证明读取失败，请核对网络与当前资格后刷新。'
    } finally {
      if (current(epoch, identity)) busy.value = false
    }
  }
  async function copyProof() {
    if (!proof.value || !props.modelValue || !permitted.value || busy.value) return
    const epoch = generation,
      identity = scope.value
    try {
      await navigator.clipboard.writeText(JSON.stringify(proof.value, null, 2))
      if (current(epoch, identity)) copyNotice.value = '公开证明已复制。'
    } catch {
      if (current(epoch, identity)) copyNotice.value = '复制失败，请展开原始材料手动选择复制。'
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
      if (open && permitted.value) void readProof()
      else if (open) emit('update:modelValue', false)
    },
    { immediate: true, flush: 'sync' }
  )
  onBeforeUnmount(clear)
</script>

<style scoped lang="scss">
  .release-proof {
    &__boundary {
      margin-bottom: 16px;
      color: var(--el-text-color-secondary);
    }
    &__summary {
      display: grid;
      grid-template-columns: 100px minmax(0, 1fr);
      gap: 12px;
      margin: 16px 0;
    }
    dt {
      color: var(--el-text-color-secondary);
    }
    dd {
      margin: 0;
      overflow-wrap: anywhere;
    }
    &__raw {
      padding-top: 16px;
      border-top: 1px solid var(--el-border-color-light);
    }
    summary {
      cursor: pointer;
    }
    label {
      display: block;
      margin-top: 12px;
    }
    textarea {
      width: 100%;
      min-height: 70px;
      padding: 8px;
      margin-top: 4px;
      color: var(--el-text-color-primary);
      overflow-wrap: anywhere;
      background: var(--el-fill-color-light);
      border: 1px solid var(--el-border-color);
      border-radius: 4px;
    }
  }
</style>
