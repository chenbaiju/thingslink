<template>
  <ElDialog
    class="console-dialog ota-firmware-feedback"
    v-model="visible"
    title="导入受控信任包"
    width="640px"
    data-testid="ota-trust-import-dialog"
    destroy-on-close
  >
    <ElAlert
      title="只载入离线签发的公开包与签名；这里不接收或生成私钥，不配置受控根，不授予固件发布或设备升级资格。"
      type="info"
      :closable="false"
      show-icon
    />
    <ElForm class="ota-trust-import-form" label-width="110px" label-position="left">
      <ElFormItem label="绑定信任域"
        ><ElInput
          v-model="domain"
          aria-label="绑定信任域"
          data-testid="ota-trust-import-domain"
          :disabled="locked || busy || checking || loadingMaterial"
          maxlength="64"
      /></ElFormItem>
      <ElFormItem label="离线公开签包"
        ><input
          class="ota-trust-import-file"
          type="file"
          accept=".json,application/json"
          data-testid="ota-trust-import-file"
          :disabled="locked || busy || checking || loadingMaterial"
          @change="loadMaterial"
      /></ElFormItem>
    </ElForm>
    <p v-if="loadingMaterial">正在检查公开材料结构与包摘要…</p>
    <div v-if="ready">
      <p>材料绑定域：{{ materialDomain }}</p>
      <p data-testid="ota-trust-import-bundle-version">材料包版本：{{ materialVersion }}</p>
      <p data-testid="ota-trust-import-bundle-sha">材料规范包 SHA-256：{{ digest }}</p>
      <p v-if="materialRevision !== undefined">材料冻结的期望修订：{{ materialRevision }}</p>
      <ElAlert
        title="材料只经过结构与公开摘要检查；受控根签名及状态演进仍由服务端裁决。"
        type="info"
        :closable="false"
        show-icon
      />
      <ElAlert
        title="本次导入为完整包替换，历史键必须保留；撤销发布键可使相关活动安全暂停。请核对以下公开状态，页面不提供单键编辑。"
        type="info"
        :closable="false"
        show-icon
      />
      <ul data-testid="ota-trust-import-keys">
        <li v-for="key in previewKeys" :key="key.keyVersion">
          键版本：{{ key.keyVersion }}；签名规格：{{ key.signatureProfile }}；状态：{{
            key.state
          }}；指纹：{{ key.fingerprint }}； 生效时刻（UTC）：{{
            key.notBefore
          }}；截止时刻（UTC）：{{ key.notAfter }}
        </li>
      </ul>
    </div>
    <ElButton
      data-testid="ota-trust-import-check"
      :disabled="!permitted || !validDomain || busy || checking || loadingMaterial"
      :loading="checking"
      @click="readCurrent"
      >读取当前登记</ElButton
    >
    <div
      v-if="revision !== undefined"
      class="ota-registration-revision"
      data-testid="ota-trust-import-revision"
    >
      <span>当前登记修订：</span>
      <span class="ota-registration-revision__value">{{ revision }}</span>
    </div>
    <div v-if="currentState" data-testid="ota-trust-import-current">
      <p>当前包版本：{{ currentState.bundleVersion }}</p>
      <p>当前规范包 SHA-256：{{ currentState.bundleSha256 }}</p>
      <p>受控根指纹：{{ currentState.rootFingerprint }}</p>
      <p>更新时刻（UTC）：{{ currentState.updatedAt }}</p>
    </div>
    <template v-if="revision === '0'">
      <ElAlert
        show-icon
        title="服务端返回本域尚未登记或不可见；首次修订0仅允许在本项目与精确域已预配受控离线根时尝试，否则服务端会拒绝。此读取不证明受控根存在。"
        type="warning"
        :closable="false"
      />
      <ElCheckbox
        v-model="rootConfirmed"
        data-testid="ota-trust-import-root-confirm"
        :disabled="locked || busy"
        >已确认本项目与信任域已预配受控离线根</ElCheckbox
      >
    </template>
    <ElAlert
      v-if="ready && materialDomain !== domain"
      title="材料绑定域与所填域不同，不能导入。"
      type="warning"
      :closable="false"
      show-icon
    />
    <ElAlert
      v-if="
        phase === 'idle' &&
        ready &&
        revision !== undefined &&
        materialRevision !== undefined &&
        materialRevision !== revision
      "
      title="材料期望修订与当前登记不同；必须重新取得匹配材料，页面不会改写。"
      type="warning"
      :closable="false"
      show-icon
    />
    <ElAlert
      v-if="ready && currentState && BigInt(currentState.bundleVersion) >= BigInt(materialVersion)"
      title="当前包版本已等于或高于材料版本；不得用新键重复提交旧包。"
      type="warning"
      :closable="false"
      show-icon
    />
    <ElAlert
      v-if="
        ready &&
        completedVersion !== undefined &&
        BigInt(completedVersion) >= BigInt(materialVersion)
      "
      data-testid="ota-trust-import-completed-fence"
      :title="`当前身份已确认本域版本 ${completedVersion} 的请求完成；同版或更低材料不能新键重提，后续登记不可见也不表示原写失败。`"
      type="info"
      :closable="false"
      show-icon
    />
    <ElAlert
      v-if="fenceFull"
      title="当前身份已达到64域的完成记录内存预算，不能在新域发起导入。"
      type="warning"
      :closable="false"
      show-icon
    />
    <ElAlert
      show-icon
      v-if="notice"
      :title="notice"
      type="info"
      :closable="false"
      data-testid="ota-trust-import-notice"
    />
    <template #footer>
      <ElButton @click="visible = false">关闭</ElButton>
      <ElButton
        data-testid="ota-trust-import-submit"
        type="primary"
        :loading="busy"
        :disabled="!canSubmit"
        @click="submit"
        >{{ locked ? '使用原键恢复' : '导入签包' }}</ElButton
      >
    </template>
  </ElDialog>
</template>

<script setup lang="ts">
  import './ota-feedback.scss'
  import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
  import {
    fetchOtaTrustDomain,
    importOtaTrustBundle,
    sha256Hex,
    type OtaTrustImportRequest,
    type OtaTrustResponse
  } from '@/api/ota'
  import { fetchProjects } from '@/api/project'
  import {
    isOtaTrustState,
    parsePublicTrustMaterial,
    publicBase64,
    trustBundleCanonicalBytes,
    trustImportBody,
    trustImportDomain,
    type PublicTrustMaterial
  } from '@/features/ota/trust-import-model'
  import { otaMetadataUuid } from '@/features/ota/metadata-model'
  import { useUserStore } from '@/store/modules/user'
  import { currentIdentityEpoch } from '@/utils/http/identity-scope'
  import { HttpError } from '@/utils/http/error'
  const props = defineProps<{ modelValue: boolean; projectId: string; authorized: boolean }>()
  const emit = defineEmits<{ 'update:modelValue': [boolean] }>()
  const user = useUserStore()
  const permitted = computed(
    () =>
      props.authorized &&
      props.modelValue &&
      otaMetadataUuid(props.projectId) &&
      props.projectId === user.info.currentProjectId &&
      !!user.info.userId &&
      !!user.info.tenantId &&
      user.info.roles?.some((role) => role === 'OWNER' || role === 'ADMIN') &&
      user.info.buttons?.includes('ota:deploy')
  )
  const scope = computed(() =>
    JSON.stringify([
      props.projectId,
      user.info.currentProjectId,
      user.info.userId,
      user.info.tenantId,
      user.info.roles,
      user.info.buttons,
      props.authorized,
      currentIdentityEpoch()
    ])
  )
  const domain = ref(''),
    materialDomain = ref(''),
    materialVersion = ref(0),
    materialRevision = ref<string>(),
    digest = ref(''),
    ready = ref(false),
    checking = ref(false),
    loadingMaterial = ref(false),
    busy = ref(false),
    notice = ref(''),
    rootConfirmed = ref(false),
    checkedDomain = ref(''),
    projectActive = ref(false),
    absent = ref(false),
    currentState = ref<Required<OtaTrustResponse>>(),
    phase = ref<'idle' | 'unknown' | 'processing' | 'completed' | 'success'>('idle')
  const previewKeys = ref<
    {
      keyVersion: string
      signatureProfile: string
      state: string
      fingerprint: string
      notBefore: string
      notAfter: string
    }[]
  >([])
  // 只冻结当前身份内的公开材料与精确提交意图，不放入Store、URL、日志或持久存储。
  let material: PublicTrustMaterial | undefined,
    pending:
      | {
          body: OtaTrustImportRequest
          key: string
          domain: string
          version: string
          digest: string
        }
      | undefined,
    generation = 0,
    readSequence = 0,
    fileSequence = 0
  const validDomain = computed(() => trustImportDomain(domain.value))
  // 只记同身份已知完成的公开域/最高版本；关闭保留，身份切换或卸载清除，无正文或幂等键。
  const completedVersions = reactive(new Map<string, string>())
  const completedVersion = computed(() => completedVersions.get(domain.value))
  const fenceFull = computed(
    () => !completedVersions.has(domain.value) && completedVersions.size >= 64
  )
  const revision = computed(() =>
    checkedDomain.value === domain.value
      ? (currentState.value?.revision ?? (absent.value ? '0' : undefined))
      : undefined
  )
  const locked = computed(() => phase.value === 'unknown' || phase.value === 'processing')
  const canSubmit = computed(
    () =>
      permitted.value &&
      !busy.value &&
      !checking.value &&
      !loadingMaterial.value &&
      (locked.value
        ? !!pending
        : phase.value === 'idle' &&
          ready.value &&
          projectActive.value &&
          materialDomain.value === domain.value &&
          revision.value !== undefined &&
          (materialRevision.value === undefined || materialRevision.value === revision.value) &&
          (revision.value !== '0' || rootConfirmed.value) &&
          !fenceFull.value &&
          (completedVersion.value === undefined ||
            BigInt(completedVersion.value) < BigInt(materialVersion.value)) &&
          (!currentState.value ||
            BigInt(currentState.value.bundleVersion) < BigInt(materialVersion.value)))
  )
  function clearCurrent() {
    currentState.value = undefined
    absent.value = false
    checkedDomain.value = ''
    projectActive.value = false
    rootConfirmed.value = false
  }
  function clearMaterial() {
    material = undefined
    ready.value = false
    materialDomain.value = ''
    materialVersion.value = 0
    materialRevision.value = undefined
    digest.value = ''
    previewKeys.value = []
  }
  function clear(resetCompleted = true) {
    if (resetCompleted) completedVersions.clear()
    generation++
    readSequence++
    fileSequence++
    clearCurrent()
    clearMaterial()
    pending = undefined
    domain.value = ''
    checking.value = false
    busy.value = false
    loadingMaterial.value = false
    notice.value = ''
    phase.value = 'idle'
  }
  const visible = computed({
    get: () => props.modelValue,
    set: (open: boolean) => {
      if (!open) clear(false)
      emit('update:modelValue', open)
    }
  })
  function current(epoch: number, identity: string) {
    return permitted.value && generation === epoch && scope.value === identity
  }
  function recordCompletion() {
    if (!pending) return
    const previous = completedVersions.get(pending.domain)
    if (previous === undefined || BigInt(pending.version) > BigInt(previous)) {
      if (previous !== undefined || completedVersions.size < 64)
        completedVersions.set(pending.domain, pending.version)
    }
  }
  async function activeProject(epoch: number, identity: string): Promise<boolean> {
    const projects = await fetchProjects()
    if (!current(epoch, identity)) return false
    const matching = Array.isArray(projects)
      ? projects.filter((item) => item.id === props.projectId)
      : []
    projectActive.value =
      matching.length === 1 &&
      matching[0]!.status === 'ACTIVE' &&
      ['OWNER', 'ADMIN'].includes(matching[0]!.myRole ?? '')
    if (!projectActive.value)
      notice.value = '当前项目须有效且本人须为 OWNER 或 ADMIN，请核对后重新读取。'
    return projectActive.value
  }
  function describeCurrent() {
    if (phase.value === 'completed') {
      const same =
        currentState.value &&
        pending &&
        currentState.value.bundleVersion === pending.version &&
        currentState.value.bundleSha256 === pending.digest
      notice.value = same
        ? '原请求已完成；当前公开包摘要与原材料一致。这里只观察当前快照，未恢复原响应，也不提供旧包新键重提。'
        : '原请求已完成，但当前快照已更高、不同或不可读取，无法恢复原响应；不能据此判原写失败，也不能用新键重提旧包。'
    }
  }
  async function readCurrent() {
    if (
      !permitted.value ||
      !validDomain.value ||
      checking.value ||
      busy.value ||
      loadingMaterial.value
    )
      return
    const epoch = generation,
      identity = scope.value,
      requestedDomain = domain.value,
      request = ++readSequence
    clearCurrent()
    checking.value = true
    try {
      if (
        !(await activeProject(epoch, identity)) ||
        !current(epoch, identity) ||
        request !== readSequence
      )
        return
      try {
        const result = await fetchOtaTrustDomain(props.projectId, requestedDomain)
        if (
          !current(epoch, identity) ||
          request !== readSequence ||
          domain.value !== requestedDomain
        )
          return
        if (!isOtaTrustState(result, requestedDomain)) throw new Error('公开登记摘要无效')
        currentState.value = result
        checkedDomain.value = requestedDomain
        if (phase.value === 'idle')
          notice.value = '已读取当前公开登记；请核对完整离线签包，读取不授权写入。'
      } catch (error) {
        if (
          !current(epoch, identity) ||
          request !== readSequence ||
          domain.value !== requestedDomain
        )
          return
        if (error instanceof HttpError && error.code === 70013 && !error.outcomeUnknown) {
          absent.value = true
          checkedDomain.value = requestedDomain
        } else throw error
      }
      describeCurrent()
    } catch {
      if (current(epoch, identity) && request === readSequence) {
        clearCurrent()
        notice.value =
          phase.value === 'completed'
            ? '原请求已完成，但当前公开快照无法读取；不能恢复原响应或判原写失败。'
            : '当前登记读取失败，不能把失败当首次修订0；请恢复权限后再读。'
      }
    } finally {
      if (current(epoch, identity) && request === readSequence) checking.value = false
    }
  }
  async function loadMaterial(event: Event) {
    const input = event.target as HTMLInputElement,
      file = input.files?.[0]
    input.value = ''
    if (
      !file ||
      !permitted.value ||
      locked.value ||
      busy.value ||
      checking.value ||
      loadingMaterial.value
    )
      return
    const epoch = generation,
      identity = scope.value,
      request = ++fileSequence
    clearMaterial()
    clearCurrent()
    pending = undefined
    phase.value = 'idle'
    loadingMaterial.value = true
    notice.value = ''
    try {
      if (file.size > 65_536) throw new Error('公开材料超预算')
      const parsed = parsePublicTrustMaterial(new Uint8Array(await file.arrayBuffer()))
      const canonical = trustBundleCanonicalBytes(parsed)
      const hash = await sha256Hex(canonical.buffer as ArrayBuffer)
      for (const entry of parsed.bundle.keys) {
        const spki = publicBase64(entry.spki, 128)!
        if ((await sha256Hex(spki.buffer as ArrayBuffer)) !== entry.fingerprint)
          throw new Error('公开SPKI指纹不匹配')
      }
      if (!current(epoch, identity) || request !== fileSequence) return
      material = parsed
      materialDomain.value = parsed.bundle.trustDomain
      materialVersion.value = parsed.bundle.bundleVersion
      materialRevision.value = parsed.expectedRevision
      digest.value = hash
      ready.value = true
      previewKeys.value = parsed.bundle.keys.map((entry) => ({
        keyVersion: entry.keyVersion,
        signatureProfile: entry.signatureProfile,
        state: entry.state,
        fingerprint: entry.fingerprint,
        notBefore: new Date(entry.notBefore * 1000).toISOString(),
        notAfter: new Date(entry.notAfter * 1000).toISOString()
      }))
    } catch {
      if (current(epoch, identity) && request === fileSequence) {
        clearMaterial()
        notice.value =
          '公开材料检查失败：只接受预算内、无重复字段的离线签包；不接收私钥或根配置，不展示原文。'
      }
    } finally {
      if (current(epoch, identity) && request === fileSequence) loadingMaterial.value = false
    }
  }
  async function submit() {
    if (!canSubmit.value) return
    const epoch = generation,
      identity = scope.value
    let sent = false
    busy.value = true
    try {
      if (!(await activeProject(epoch, identity)) || !current(epoch, identity)) return
      if (!pending) {
        if (!material || revision.value === undefined) return
        pending = {
          body: trustImportBody(material, revision.value),
          key: crypto.randomUUID(),
          domain: domain.value,
          version: String(materialVersion.value),
          digest: digest.value
        }
      }
      sent = true
      const result = await importOtaTrustBundle(
        props.projectId,
        pending.domain,
        pending.body,
        pending.key
      )
      if (!current(epoch, identity)) return
      if (
        !isOtaTrustState(result, pending.domain) ||
        result.bundleVersion !== pending.version ||
        result.bundleSha256 !== pending.digest ||
        BigInt(result.revision) !== BigInt(pending.body.expectedRevision) + 1n
      ) {
        phase.value = 'unknown'
        notice.value = '响应无法确认，保留原正文与原键；请显式恢复，不能换键重提。'
        return
      }
      currentState.value = result
      checkedDomain.value = pending.domain
      absent.value = false
      phase.value = 'success'
      recordCompletion()
      notice.value = '导入已确认，当前公开摘要匹配本次签包；不代表当前发布资格或设备已收到信任包。'
    } catch (error) {
      if (!current(epoch, identity)) return
      const code = error instanceof HttpError ? error.code : undefined
      if (!sent) {
        // 原键恢复的前置读取失败不能释放既有未知意图；首次提交前失败则尚未发出写请求。
        if (!pending) {
          phase.value = 'idle'
          notice.value = '提交前检查失败，尚未发出导入请求；请核对材料预算与当前登记后再试。'
        } else notice.value = '原意图仍冻结；当前项目资格读取失败，请恢复后使用原键恢复。'
      } else if (!(error instanceof HttpError) || error.outcomeUnknown) {
        phase.value = 'unknown'
        notice.value =
          '导入结果未知；原正文及原键已冻结，请使用原键恢复，不改修订或材料。' +
          (code === 70010 ? ' 服务端受控根未配置或不可用，页面不能补配根。' : '')
      } else if (code === 10010) {
        phase.value = 'processing'
        notice.value = '原请求仍在处理，原正文及原键保持；请稍后显式恢复。'
      } else if (code === 10014) {
        phase.value = 'completed'
        recordCompletion()
        notice.value =
          '原请求已完成，原响应不能重放；请读取当前公开登记辅助核对，不能用新键重提旧包。'
      } else {
        pending = undefined
        phase.value = 'idle'
        clearCurrent()
        notice.value =
          code === 70010
            ? '本项目与精确域的受控离线根未配置或不可用，页面不能补配根。'
            : code === 70011
              ? '服务端拒绝签包结构、公开密钥或根签名；原材料未导入。'
              : code === 70012
                ? '当前修订、包版本或历史键状态冲突；重新读取后取得新的完整签包，不自动改修订。'
                : code === 50017
                  ? '项目已非活动状态，不能导入受控签包。'
                  : '服务端未授权本次导入，请核对当前项目、权限、精确域和受控材料后再读。'
      }
    } finally {
      if (current(epoch, identity)) busy.value = false
    }
  }
  watch(
    domain,
    () => {
      readSequence++
      checking.value = false
      clearCurrent()
      if (!locked.value) {
        pending = undefined
        phase.value = 'idle'
        notice.value = ''
      }
    },
    { flush: 'sync' }
  )
  watch(
    [scope, () => props.modelValue],
    ([identity, open], previous) => {
      clear(!previous?.length || identity !== previous[0])
      if (open && ((previous?.length && identity !== previous[0]) || !permitted.value))
        emit('update:modelValue', false)
    },
    { immediate: true, flush: 'sync' }
  )
  onBeforeUnmount(clear)
</script>
