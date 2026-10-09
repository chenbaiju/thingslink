<template>
  <ElDialog
    class="console-dialog ota-firmware-feedback"
    v-model="visible"
    title="登记受控类型基线"
    width="720px"
    data-testid="ota-baseline-registration-dialog"
    destroy-on-close
  >
    <ElAlert
      title="仅登记服务器为本租户、项目与精确类型预配的受控基线。页面不接收基线JSON、能力开关或制造证据，不显示无法获知的待登记配置版本。"
      type="info"
      :closable="false"
      show-icon
    />
    <div class="ota-baseline-type-field">
      <label for="ota-baseline-registration-type-input">已发布设备类型</label>
      <ElSelect
        id="ota-baseline-registration-type-input"
        v-model="selectedType"
        aria-label="已发布设备类型"
        data-testid="ota-baseline-registration-type"
        :loading="typesLoading"
        :disabled="typesLoading || busy || checking || locked || types.length === 0"
      >
        <ElOption
          v-for="type in types"
          :key="type.id"
          :label="`${type.name}（${type.id}）`"
          :value="type.id"
        />
      </ElSelect>
    </div>
    <ElAlert
      v-if="!typesLoading && !typesError && !types.length"
      title="没有具备产品标识的已发布设备类型"
      type="info"
      :closable="false"
      show-icon
    />
    <ElAlert
      v-if="missingProduct"
      :title="`有 ${missingProduct} 个已发布类型缺少产品标识，不能作为OTA权威类型。`"
      type="warning"
      :closable="false"
      show-icon
    />
    <ElAlert show-icon v-if="typesError" :title="typesError" type="error" :closable="false" />
    <ElButton v-if="typesError" :disabled="busy || checking || locked" @click="loadTypes"
      >重读设备类型</ElButton
    >
    <ElButton
      data-testid="ota-baseline-registration-check"
      :disabled="!permitted || !selected || checking || busy || typesLoading"
      :loading="checking"
      @click="readCurrent"
      >读取当前登记</ElButton
    >
    <div
      v-if="revision !== undefined"
      class="ota-registration-revision"
      data-testid="ota-baseline-registration-revision"
    >
      <span>当前登记修订：</span>
      <span class="ota-registration-revision__value">{{ revision }}</span>
    </div>
    <ElAlert
      v-if="revision === '0'"
      title="服务端返回登记不存在或不可见；这不证明服务器已配置基线，也不能推断先前请求失败。首次登记仍由服务器核对受控来源与权威类型。"
      type="info"
      :closable="false"
      show-icon
    />
    <div v-if="currentState" data-testid="ota-baseline-registration-current">
      <p data-testid="ota-baseline-registration-version"
        >已登记基线版本：{{ currentState.baseline.baselineVersion }}</p
      >
      <p data-testid="ota-baseline-registration-hash"
        >已登记规范基线 SHA-256：{{ currentState.baselineHash }}</p
      >
      <p>首次登记（UTC）：{{ currentState.registeredAt }}</p>
      <p>最近登记（UTC）：{{ currentState.updatedAt }}</p>
      <dl
        ><div v-for="row in baselineRows" :key="row.label"
          ><dt>{{ row.label }}</dt
          ><dd>{{ row.value }}</dd></div
        ></dl
      >
      <ElAlert
        title="以上是已登记的受控声明，不代表本进程当前配置匹配、制造证据已验真或设备当前升级资格。缺能力的 false/0 不会被页面提升。"
        type="info"
        :closable="false"
        show-icon
      />
    </div>
    <ElCheckbox
      v-if="revision !== undefined && phase === 'idle'"
      v-model="configConfirmed"
      data-testid="ota-baseline-registration-config-confirm"
      :disabled="busy || checking"
    >
      已确认服务器已更新本项目与精确类型的受控基线配置
    </ElCheckbox>
    <ElAlert
      v-if="revision === '9223372036854775807'"
      title="当前修订已达上限，不能继续登记。"
      type="warning"
      :closable="false"
      show-icon
    />
    <ElAlert
      v-if="
        completedSlot !== undefined &&
        revision !== undefined &&
        BigInt(revision) <= BigInt(completedSlot)
      "
      data-testid="ota-baseline-registration-completed-fence"
      :title="`当前身份已知本类型修订槽 ${completedSlot} 的请求完成；当前读取得到同槽或更低修订，不能用新键重提原意图。`"
      type="info"
      :closable="false"
      show-icon
    />
    <ElAlert
      v-if="fenceFull"
      title="当前身份已达到64类型的完成记录预算，不能在新类型发起登记。"
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
      data-testid="ota-baseline-registration-notice"
    />
    <ElButton
      v-if="phase === 'completed' || phase === 'success'"
      data-testid="ota-baseline-registration-new-intent"
      :disabled="busy || checking || !permitted"
      @click="newIntent"
      >开始新的配置登记</ElButton
    >
    <template #footer>
      <ElButton @click="visible = false">关闭</ElButton>
      <ElButton
        data-testid="ota-baseline-registration-submit"
        type="primary"
        :loading="busy"
        :disabled="!canSubmit"
        @click="submit"
        >{{ locked ? '使用原键恢复' : '登记服务器配置' }}</ElButton
      >
    </template>
  </ElDialog>
</template>

<script setup lang="ts">
  import './ota-feedback.scss'
  import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
  import { fetchDeviceTypeDetail, fetchDeviceTypePage, type DeviceTypeResponse } from '@/api/device'
  import {
    fetchOtaTypeBaseline,
    registerOtaTypeBaseline,
    sha256Hex,
    type OtaTypeBaselineRegistration,
    type OtaTypeBaselineResponse
  } from '@/api/ota'
  import { fetchProjects } from '@/api/project'
  import { isOtaMetadataType, otaMetadataUuid, publicOtaPage } from '@/features/ota/metadata-model'
  import {
    isOtaBaselineResponse,
    otaBaselineCanonicalBytes,
    otaBaselineProductKey,
    type OtaBaselineScope
  } from '@/features/ota/baseline-registration-model'
  import { useUserStore } from '@/store/modules/user'
  import { currentIdentityEpoch } from '@/utils/http/identity-scope'
  import { HttpError } from '@/utils/http/error'
  const props = defineProps<{ modelValue: boolean; projectId: string; authorized: boolean }>()
  const emit = defineEmits<{ 'update:modelValue': [boolean] }>()
  const user = useUserStore()
  const permitted = computed(
    () =>
      props.modelValue &&
      props.authorized &&
      otaMetadataUuid(props.projectId) &&
      props.projectId === user.info.currentProjectId &&
      otaMetadataUuid(user.info.tenantId) &&
      !!user.info.userId &&
      user.info.roles?.some((role) => role === 'OWNER' || role === 'ADMIN') &&
      user.info.buttons?.includes('ota:deploy')
  )
  const scope = computed(() =>
    JSON.stringify([
      props.projectId,
      user.info.currentProjectId,
      user.info.tenantId,
      user.info.userId,
      user.info.roles,
      user.info.buttons,
      props.authorized,
      currentIdentityEpoch()
    ])
  )
  const types = ref<{ id: string; name: string; productKey: string }[]>([]),
    selectedType = ref(''),
    typesLoading = ref(false),
    typesError = ref(''),
    missingProduct = ref(0),
    checking = ref(false),
    busy = ref(false),
    notice = ref(''),
    configConfirmed = ref(false),
    checkedType = ref(''),
    projectActive = ref(false),
    absent = ref(false),
    currentState = ref<OtaTypeBaselineResponse>(),
    phase = ref<'idle' | 'unknown' | 'processing' | 'completed' | 'success'>('idle')
  let generation = 0,
    typesSequence = 0,
    readSequence = 0,
    pending:
      | {
          body: OtaTypeBaselineRegistration
          key: string
          typeId: string
          typeName: string
          productKey: string
          serverTenantId?: string
          priorVersion?: number
        }
      | undefined
  // 记录的是完成的CAS修订槽，绝不推断未公开的服务器配置版本。
  const completedSlots = reactive(new Map<string, string>())
  // 项目归属租户从已校验的完整服务端快照获知，与登录账号租户独立；最多绑定64类型。
  const verifiedServerTenants = new Map<string, string>()
  const completedSlot = computed(() => completedSlots.get(selectedType.value))
  const fenceFull = computed(
    () => !completedSlots.has(selectedType.value) && completedSlots.size >= 64
  )
  const selected = computed(() => types.value.find((type) => type.id === selectedType.value))
  const locked = computed(() => phase.value === 'unknown' || phase.value === 'processing')
  const revision = computed(() =>
    checkedType.value === selectedType.value
      ? (currentState.value?.revision ?? (absent.value ? '0' : undefined))
      : undefined
  )
  const canSubmit = computed(
    () =>
      permitted.value &&
      !busy.value &&
      !checking.value &&
      !typesLoading.value &&
      !!selected.value &&
      (locked.value
        ? !!pending
        : phase.value === 'idle' &&
          projectActive.value &&
          revision.value !== undefined &&
          revision.value !== '9223372036854775807' &&
          configConfirmed.value &&
          !fenceFull.value &&
          (completedSlot.value === undefined ||
            BigInt(revision.value) > BigInt(completedSlot.value)))
  )
  const baselineRows = computed(() => {
    const b = currentState.value?.baseline
    if (!b) return []
    return [
      ['合同', b.contractVersion],
      ['租户', b.tenantId],
      ['项目', b.projectId],
      ['设备类型', b.deviceTypeId],
      ['产品标识', b.productKey],
      ['信任域', b.trustDomain],
      ['离线根指纹', b.rootFingerprint],
      ['硬件型号', b.hardware.model],
      ['板级序号范围', `${b.hardware.boardRevisionMin} — ${b.hardware.boardRevisionMax}`],
      ['引导程序版本范围', `${b.bootloader.minimumVersion} — ${b.bootloader.maximumVersion}`],
      ['签名规格', b.signatureProfiles.join('、')],
      ['最大固件字节', String(b.maximumArtifactBytes)],
      ['可用RAM字节', String(b.availableRamBytes)],
      ['可用Flash字节', String(b.availableFlashBytes)],
      ['双槽支持', String(b.supportsAbSlots)],
      ['Range支持', String(b.supportsRangeDownload)],
      ['续传支持', String(b.supportsResumeDownload)],
      ['受保护计数器位数', String(b.protectedSecurityCounterBits)],
      ['压缩算法', b.compressionAlgorithms.join('、')],
      ['差分方式', b.deltaModes.join('、')],
      ['属性Profile', b.propertyProfile],
      ['受控来源索引', b.evidenceReference]
    ].map(([label, value]) => ({ label: label!, value: value! }))
  })
  function clearCurrent() {
    checkedType.value = ''
    projectActive.value = false
    absent.value = false
    currentState.value = undefined
    configConfirmed.value = false
  }
  function clear(resetIdentity = true) {
    generation++
    typesSequence++
    readSequence++
    const retain = !resetIdentity && !!pending && (locked.value || busy.value)
    if (resetIdentity) {
      completedSlots.clear()
      verifiedServerTenants.clear()
    }
    if (!retain) {
      pending = undefined
      phase.value = 'idle'
    } else if (!locked.value) phase.value = 'unknown'
    types.value = []
    selectedType.value = ''
    clearCurrent()
    missingProduct.value = 0
    typesLoading.value = false
    typesError.value = ''
    checking.value = false
    busy.value = false
    notice.value = ''
  }
  const visible = computed({
    get: () => props.modelValue,
    set: (open: boolean) => {
      if (!open) clear(false)
      emit('update:modelValue', open)
    }
  })
  function recordCompletion() {
    if (!pending) return
    const previous = completedSlots.get(pending.typeId)
    if (
      (previous === undefined || BigInt(pending.body.expectedRevision) > BigInt(previous)) &&
      (previous !== undefined || completedSlots.size < 64)
    )
      completedSlots.set(pending.typeId, pending.body.expectedRevision)
  }
  const current = (epoch: number, identity: string) =>
    permitted.value && generation === epoch && scope.value === identity
  function baselineScope(typeId: string, productKey: string): OtaBaselineScope {
    return {
      serverTenantId:
        (pending?.typeId === typeId ? pending.serverTenantId : undefined) ??
        verifiedServerTenants.get(typeId),
      projectId: props.projectId,
      deviceTypeId: typeId,
      productKey
    }
  }
  function recordServerTenant(value: OtaTypeBaselineResponse) {
    const { deviceTypeId, tenantId } = value.baseline
    if (verifiedServerTenants.has(deviceTypeId) || verifiedServerTenants.size < 64)
      verifiedServerTenants.set(deviceTypeId, tenantId)
  }
  async function validateSnapshot(value: unknown, target: OtaBaselineScope) {
    if (!isOtaBaselineResponse(value, target)) throw new Error('基线公开响应不符合合同')
    if (
      (await sha256Hex(otaBaselineCanonicalBytes(value.baseline).buffer as ArrayBuffer)) !==
      value.baselineHash
    )
      throw new Error('完整基线摘要不匹配')
    return value
  }
  async function loadTypes() {
    if (!permitted.value || typesLoading.value || busy.value || checking.value || locked.value)
      return
    const epoch = generation,
      identity = scope.value,
      request = ++typesSequence
    types.value = []
    selectedType.value = ''
    clearCurrent()
    typesLoading.value = true
    typesError.value = ''
    missingProduct.value = 0
    let cursor: string | undefined
    const seen = new Set<string>(),
      ids = new Set<string>(),
      found: typeof types.value = []
    let missing = 0
    try {
      do {
        const page = publicOtaPage(
          await fetchDeviceTypePage(props.projectId, cursor, 100),
          (value): value is DeviceTypeResponse => isOtaMetadataType(value, props.projectId)
        )
        if (!current(epoch, identity) || request !== typesSequence) return
        for (const item of page.items) {
          if (ids.has(item.id!)) throw new Error('重复类型')
          ids.add(item.id!)
          if (item.status === 'PUBLISHED') {
            if (otaBaselineProductKey(item.productKey))
              found.push({ id: item.id!, name: item.name!, productKey: item.productKey })
            else missing++
          }
        }
        cursor = page.hasMore ? page.nextCursor! : undefined
        if (cursor && seen.has(cursor)) throw new Error('重复游标')
        if (cursor) seen.add(cursor)
      } while (cursor)
      types.value = found
      missingProduct.value = missing
    } catch {
      if (current(epoch, identity) && request === typesSequence)
        typesError.value = '读取已发布设备类型失败；不能从坏页或失败推断为空集，请重新读取。'
    } finally {
      if (current(epoch, identity) && request === typesSequence) typesLoading.value = false
    }
  }
  async function activeContext(
    epoch: number,
    identity: string,
    typeId: string,
    productKey: string
  ) {
    const projects = await fetchProjects()
    if (!current(epoch, identity)) return false
    const matching = Array.isArray(projects)
      ? projects.filter((project) => project.id === props.projectId)
      : []
    projectActive.value =
      matching.length === 1 &&
      matching[0]!.status === 'ACTIVE' &&
      ['OWNER', 'ADMIN'].includes(matching[0]!.myRole ?? '')
    if (!projectActive.value) {
      notice.value = '当前项目须有效且本人须为 OWNER 或 ADMIN，不能登记。'
      return false
    }
    const detail = await fetchDeviceTypeDetail(props.projectId, typeId)
    if (!current(epoch, identity)) return false
    if (
      !isOtaMetadataType(detail, props.projectId) ||
      detail.id !== typeId ||
      detail.status !== 'PUBLISHED' ||
      detail.productKey !== productKey
    ) {
      projectActive.value = false
      notice.value = '已发布类型或产品身份已变化，请重新读取设备类型后核对。'
      return false
    }
    return true
  }
  function describeCurrent() {
    if (phase.value === 'completed')
      notice.value = currentState.value
        ? '原请求已完成，原响应不能重放。以下仅是当前权威登记快照，可能来自更高配置或其他登记，不能冒称恢复原响应。'
        : '原请求已完成，但当前登记不存在或不可见；不能据此判原写失败或换新键重提原意图。'
  }
  async function readCurrent() {
    if (!permitted.value || !selected.value || checking.value || busy.value || typesLoading.value)
      return
    const epoch = generation,
      identity = scope.value,
      type = { ...selected.value },
      request = ++readSequence
    clearCurrent()
    checking.value = true
    try {
      if (
        !(await activeContext(epoch, identity, type.id, type.productKey)) ||
        !current(epoch, identity) ||
        request !== readSequence
      )
        return
      try {
        const value = await fetchOtaTypeBaseline(props.projectId, type.id)
        if (!current(epoch, identity) || request !== readSequence || type.id !== selectedType.value)
          return
        const valid = await validateSnapshot(value, baselineScope(type.id, type.productKey))
        if (!current(epoch, identity) || request !== readSequence || type.id !== selectedType.value)
          return
        currentState.value = valid
        recordServerTenant(valid)
        checkedType.value = type.id
        if (phase.value === 'idle')
          notice.value = '已读取权威登记；服务器当前受控配置的目标版本并未由此API公开。'
      } catch (error) {
        if (!current(epoch, identity) || request !== readSequence || type.id !== selectedType.value)
          return
        if (error instanceof HttpError && error.code === 70031 && !error.outcomeUnknown) {
          absent.value = true
          checkedType.value = type.id
        } else throw error
      }
      describeCurrent()
    } catch {
      if (current(epoch, identity) && request === readSequence) {
        clearCurrent()
        notice.value =
          phase.value === 'completed'
            ? '原请求已完成，但当前权威快照无法读取；不能恢复原响应或判原写失败。'
            : '当前基线读取失败，不能把失败当首次修订0；请恢复权限后再读。'
      }
    } finally {
      if (current(epoch, identity) && request === readSequence) checking.value = false
    }
  }
  function newIntent() {
    if (
      !permitted.value ||
      busy.value ||
      checking.value ||
      !['completed', 'success'].includes(phase.value)
    )
      return
    pending = undefined
    phase.value = 'idle'
    readSequence++
    clearCurrent()
    // 原未知意图重开时只有冻结目标；新的意图必须恢复完整目录并重新选择类型。
    void loadTypes()
    notice.value =
      '新的配置登记意图须从重新读取的完整目录选择类型、读取当前登记，并确认服务器已更新本项目与精确类型的受控配置；不会沿用旧修订或原键。'
  }
  async function submit() {
    if (!canSubmit.value || !selected.value) return
    const epoch = generation,
      identity = scope.value,
      type = { ...selected.value }
    let sent = false
    busy.value = true
    try {
      if (
        !(await activeContext(
          epoch,
          identity,
          pending?.typeId ?? type.id,
          pending?.productKey ?? type.productKey
        )) ||
        !current(epoch, identity)
      )
        return
      if (!pending) {
        if (revision.value === undefined) return
        pending = {
          body: Object.freeze({ expectedRevision: revision.value }),
          key: crypto.randomUUID(),
          typeId: type.id,
          typeName: type.name,
          productKey: type.productKey,
          serverTenantId: verifiedServerTenants.get(type.id),
          priorVersion: currentState.value?.baseline.baselineVersion
        }
      }
      sent = true
      const value = await registerOtaTypeBaseline(
        props.projectId,
        pending.typeId,
        pending.body,
        pending.key
      )
      if (!current(epoch, identity)) return
      const valid = await validateSnapshot(value, baselineScope(pending.typeId, pending.productKey))
      if (!current(epoch, identity)) return
      if (
        BigInt(valid.revision) !== BigInt(pending.body.expectedRevision) + 1n ||
        (pending.priorVersion !== undefined &&
          valid.baseline.baselineVersion <= pending.priorVersion)
      )
        throw new Error('登记响应不匹配原意图')
      currentState.value = valid
      recordServerTenant(valid)
      checkedType.value = pending.typeId
      absent.value = false
      configConfirmed.value = false
      phase.value = 'success'
      recordCompletion()
      notice.value =
        '登记已确认，以下为服务器采用配置后的权威基线；不代表制造证据、实物能力或设备升级资格已验收。'
    } catch (error) {
      if (!current(epoch, identity)) return
      const code = error instanceof HttpError ? error.code : undefined
      if (!sent)
        notice.value = pending
          ? '原意图仍冻结；项目或类型资格读取失败，请恢复后使用原键恢复。'
          : '提交前资格读取失败，尚未发出登记请求，请重新核对。'
      else if (!(error instanceof HttpError) || error.outcomeUnknown) {
        phase.value = 'unknown'
        notice.value =
          '登记结果未知；原正文与原键已冻结，请使用原键恢复，不换键或改修订。' +
          (code === 70028 ? ' 服务端精确类型受控基线尚未配置或不可用，页面不能补配。' : '')
      } else if (code === 10010) {
        phase.value = 'processing'
        notice.value = '原请求仍在处理，原正文与原键保持；请稍后显式恢复。'
      } else if (code === 10014) {
        phase.value = 'completed'
        recordCompletion()
        configConfirmed.value = false
        notice.value =
          '原请求已完成，原意图永久结束，原响应不能重放；请只读当前登记核对，不能把墓碑当登记成功。'
      } else {
        pending = undefined
        phase.value = 'idle'
        clearCurrent()
        notice.value =
          code === 70030
            ? '修订、配置版本或不可变身份冲突；请重新读取并由服务器维护新的受控配置，页面不会改修订或能力。'
            : code === 70029
              ? '受控配置或权威类型身份校验失败，页面不能替代服务器配置。'
              : code === 50017
                ? '项目已非活动状态，不能登记受控类型基线。'
                : '服务端未授权本次登记，请核对当前项目、角色、精确类型和服务器配置后再读。'
      }
    } finally {
      if (current(epoch, identity)) busy.value = false
    }
  }
  watch(
    selectedType,
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
      else if (open && pending && locked.value) {
        types.value = [
          { id: pending.typeId, name: pending.typeName, productKey: pending.productKey }
        ]
        selectedType.value = pending.typeId
        notice.value = `上次请求结果未知或仍在处理，目标类型为 ${pending.typeName}（${pending.typeId}）；原正文和原键仅在当前身份内存保留，只允许显式恢复原意图。`
      } else if (open) void loadTypes()
    },
    { immediate: true, flush: 'sync' }
  )
  onBeforeUnmount(clear)
</script>
