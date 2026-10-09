<template>
  <ElDrawer
    class="ota-firmware-feedback"
    v-model="visible"
    title="OTA 信任与类型基线"
    size="min(960px, 95vw)"
    destroy-on-close
    data-testid="ota-metadata-drawer"
  >
    <ElAlert
      title="这里只展示服务端登记的公开元数据；密钥状态、有效期和基线版本不代表当前设备升级资格。"
      type="info"
      :closable="false"
      show-icon
    />

    <ElDivider content-position="left">信任域</ElDivider>
    <div class="ota-metadata-toolbar">
      <ElButton
        :loading="domainsLoading"
        :disabled="domainsLoading"
        data-testid="ota-trust-domains-refresh"
        @click="loadDomains()"
        >刷新信任域</ElButton
      >
    </div>
    <ElAlert
      show-icon
      v-if="domainsError"
      :title="domainsError"
      type="error"
      :closable="false"
      data-testid="ota-trust-domains-error"
    />
    <ElTable
      v-loading="domainsLoading"
      :data="domains.items"
      row-key="trustDomain"
      data-testid="ota-trust-domains"
    >
      <ElTableColumn prop="trustDomain" label="信任域" min-width="160" />
      <ElTableColumn prop="revision" label="修订" width="80" />
      <ElTableColumn prop="bundleVersion" label="包版本" width="90" />
      <ElTableColumn prop="policyRevision" label="根配置修订" width="110" />
      <ElTableColumn prop="activeKeyVersion" label="当前 ACTIVE 发布键" min-width="180" />
      <ElTableColumn label="公开摘要" min-width="250">
        <template #default="{ row }">
          <div>根算法：{{ row.rootProfile }}</div>
          <div>根指纹：{{ row.rootFingerprint }}</div>
          <div>包摘要：{{ row.bundleSha256 }}</div>
          <div>发布键指纹：{{ row.activeKeyFingerprint }}</div>
          <div>首次登记：{{ formatTime(row.createdAt) }}</div>
          <div>最近导入：{{ formatTime(row.updatedAt) }}</div>
        </template>
      </ElTableColumn>
      <ElTableColumn label="操作" width="120">
        <template #default="{ row }">
          <ElButton
            text
            type="primary"
            data-testid="ota-trust-keys-open"
            :aria-label="`查看 ${row.trustDomain} 发布键`"
            @click="openKeys(row.trustDomain)"
            >查看发布键</ElButton
          >
        </template>
      </ElTableColumn>
      <template #empty
        ><ElEmpty
          :image-size="70"
          :description="
            domainsError
              ? '信任域读取失败'
              : domainsLoading
                ? '正在读取信任域'
                : '本项目尚未登记信任域'
          "
      /></template>
    </ElTable>
    <ElButton
      v-if="domains.hasMore"
      :loading="domainsLoading"
      :disabled="domainsLoading"
      data-testid="ota-trust-domains-more"
      @click="loadDomains(domains.cursor)"
      >加载更多信任域</ElButton
    >

    <ElDivider content-position="left">发布键公开元数据</ElDivider>
    <p v-if="selectedDomain" data-testid="ota-trust-selected-domain"
      >信任域：{{ selectedDomain }}</p
    >
    <div v-if="selectedDomain" class="ota-metadata-toolbar">
      <ElButton
        :loading="keysLoading"
        :disabled="keysLoading"
        data-testid="ota-trust-keys-refresh"
        @click="loadKeys()"
        >刷新发布键</ElButton
      >
    </div>
    <ElAlert
      show-icon
      v-if="keysReset"
      title="信任包已变化，已清除旧包发布键并重新读取。"
      type="info"
      :closable="false"
      data-testid="ota-trust-keys-reset"
    />
    <ElAlert
      show-icon
      v-if="keysError"
      :title="keysError"
      type="error"
      :closable="false"
      data-testid="ota-trust-keys-error"
    />
    <ElTable
      v-loading="keysLoading"
      :data="keys.items"
      row-key="keyVersion"
      data-testid="ota-trust-keys"
    >
      <ElTableColumn prop="keyVersion" label="发布键版本" min-width="140" />
      <ElTableColumn label="状态" width="110"
        ><template #default="{ row }"
          ><ElTag :type="otaTrustKeyStateTag(row.state)">{{
            otaTrustKeyStateLabel(row.state)
          }}</ElTag></template
        ></ElTableColumn
      >
      <ElTableColumn prop="signatureProfile" label="签名算法" min-width="190" />
      <ElTableColumn prop="fingerprint" label="公钥指纹" min-width="250" />
      <ElTableColumn label="有效期（UTC）" min-width="340"
        ><template #default="{ row }">{{
          otaTrustKeyValidity(row.notBefore, row.notAfter)
        }}</template></ElTableColumn
      >
      <template #empty
        ><ElEmpty
          :image-size="70"
          :description="
            !selectedDomain
              ? '请先选择信任域'
              : keysError
                ? '发布键读取失败'
                : keysLoading
                  ? '正在读取发布键'
                  : '当前信任包没有发布键'
          "
      /></template>
    </ElTable>
    <ElButton
      v-if="keys.hasMore"
      :loading="keysLoading"
      :disabled="keysLoading"
      data-testid="ota-trust-keys-more"
      @click="loadKeys(keys.cursor)"
      >加载更多发布键</ElButton
    >

    <ElDivider content-position="left">类型基线版本历史</ElDivider>
    <ElAlert
      show-icon
      v-if="typesError"
      :title="typesError"
      type="error"
      :closable="false"
      data-testid="ota-baseline-types-error"
    />
    <ElButton v-if="typesError" :loading="typesLoading" @click="loadTypes()">重读设备类型</ElButton>
    <ElAlert
      title="基线历史读取要求类型已发布并已生成产品标识；尚未登记基线时显示空集。"
      type="info"
      :closable="false"
      show-icon
    />
    <div class="ota-metadata-filter">
      <div class="ota-metadata-toolbar">
        <label for="ota-baseline-type">已发布设备类型</label>
        <ElSelect
          id="ota-baseline-type"
          v-model="selectedType"
          aria-label="已发布设备类型"
          data-testid="ota-baseline-type"
          :loading="typesLoading"
          :disabled="typesLoading || types.length === 0"
          placeholder="选择已发布设备类型"
          clearable
          @change="changeType"
        >
          <ElOption
            v-for="type in types"
            :key="type.id"
            :label="`${type.name}（${type.id}）`"
            :value="type.id"
          />
        </ElSelect>
        <ElButton
          v-if="selectedType"
          :loading="baselineLoading"
          :disabled="baselineLoading"
          data-testid="ota-baseline-refresh"
          @click="loadBaseline()"
          >刷新基线历史</ElButton
        >
      </div>
    </div>
    <ElAlert
      v-if="!typesLoading && !typesError && types.length === 0"
      title="没有已发布设备类型"
      type="info"
      :closable="false"
      show-icon
    />
    <ElAlert
      show-icon
      v-if="baselineError"
      :title="baselineError"
      type="error"
      :closable="false"
      data-testid="ota-baseline-error"
    />
    <ElTable
      v-loading="baselineLoading"
      :data="baseline.items"
      row-key="baselineVersion"
      data-testid="ota-baseline-versions"
    >
      <ElTableColumn prop="baselineVersion" label="基线版本" width="120" />
      <ElTableColumn prop="baselineHash" label="规范摘要" min-width="300" />
      <ElTableColumn label="登记时间" min-width="180"
        ><template #default="{ row }">{{ formatTime(row.registeredAt) }}</template></ElTableColumn
      >
      <template #empty
        ><ElEmpty
          :image-size="70"
          :description="
            !selectedType
              ? '请先选择已发布设备类型'
              : baselineError
                ? '基线历史读取失败'
                : baselineLoading
                  ? '正在读取基线历史'
                  : '该类型尚未登记基线版本'
          "
      /></template>
    </ElTable>
    <ElButton
      v-if="baseline.hasMore"
      :loading="baselineLoading"
      :disabled="baselineLoading"
      data-testid="ota-baseline-versions-more"
      @click="loadBaseline(baseline.cursor)"
      >加载更多基线版本</ElButton
    >
    <template #footer><ElButton @click="visible = false">关闭</ElButton></template>
  </ElDrawer>
</template>

<script setup lang="ts">
  import './ota-feedback.scss'
  import { fetchDeviceTypePage } from '@/api/device'
  import {
    fetchOtaTrustDomains,
    fetchOtaTrustKeys,
    fetchOtaBaselineVersions,
    type OtaTrustDomainSummaryResponse,
    type OtaTrustKeyResponse,
    type OtaTypeBaselineVersionResponse
  } from '@/api/ota'
  import {
    emptyOtaTrustDomainHistory,
    emptyOtaTrustKeyHistory,
    mergeOtaTrustDomainPage,
    mergeOtaTrustKeyPage,
    otaTrustKeyStateLabel,
    otaTrustKeyStateTag,
    otaTrustKeyValidity
  } from '@/features/ota/trust-model'
  import {
    emptyOtaBaselineVersionHistory,
    mergeOtaBaselineVersionPage
  } from '@/features/ota/baseline-model'
  import {
    isOtaTrustDomain,
    isOtaTrustKey,
    isOtaBaselineVersion,
    isOtaMetadataType,
    otaMetadataUuid,
    publicOtaPage
  } from '@/features/ota/metadata-model'
  import { useUserStore } from '@/store/modules/user'
  import { currentIdentityEpoch } from '@/utils/http/identity-scope'
  import { HttpError } from '@/utils/http/error'
  import { formatTime } from '@/utils/time'

  const props = defineProps<{ modelValue: boolean; projectId: string; authorized: boolean }>()
  const emit = defineEmits<{ 'update:modelValue': [boolean] }>()
  const user = useUserStore()
  const permitted = computed(
    () =>
      props.authorized &&
      otaMetadataUuid(props.projectId) &&
      user.info.currentProjectId === props.projectId &&
      !!user.info.userId &&
      !!user.info.tenantId &&
      user.info.roles?.some((role) => role === 'OWNER' || role === 'ADMIN') &&
      user.info.buttons?.includes('ota:read')
  )
  const scope = computed(() =>
    JSON.stringify([
      props.projectId,
      user.info.currentProjectId,
      user.info.tenantId,
      user.info.userId,
      currentIdentityEpoch(),
      props.authorized,
      user.info.roles,
      user.info.buttons
    ])
  )
  const domains = ref(emptyOtaTrustDomainHistory<OtaTrustDomainSummaryResponse>())
  const keys = ref(emptyOtaTrustKeyHistory<OtaTrustKeyResponse>())
  const baseline = ref(emptyOtaBaselineVersionHistory<OtaTypeBaselineVersionResponse>())
  const types = ref<{ id: string; name: string }[]>([])
  const selectedDomain = ref(''),
    selectedType = ref('')
  const domainsLoading = ref(false),
    keysLoading = ref(false),
    baselineLoading = ref(false),
    typesLoading = ref(false)
  const domainsError = ref(''),
    keysError = ref(''),
    baselineError = ref(''),
    typesError = ref('')
  const keysReset = ref(false)
  let generation = 0,
    domainsSequence = 0,
    keysSequence = 0,
    baselineSequence = 0,
    typesSequence = 0
  const visible = computed({
    get: () => props.modelValue && !!permitted.value,
    set: (value: boolean) => {
      if (!value) invalidate()
      emit('update:modelValue', value)
    }
  })
  function invalidate() {
    generation++
    domainsSequence++
    keysSequence++
    baselineSequence++
    typesSequence++
    domains.value = emptyOtaTrustDomainHistory()
    keys.value = emptyOtaTrustKeyHistory()
    baseline.value = emptyOtaBaselineVersionHistory()
    types.value = []
    selectedDomain.value = ''
    selectedType.value = ''
    domainsError.value = ''
    keysError.value = ''
    baselineError.value = ''
    typesError.value = ''
    domainsLoading.value = false
    keysLoading.value = false
    baselineLoading.value = false
    typesLoading.value = false
    keysReset.value = false
  }
  function current(epoch: number, identity: string) {
    return props.modelValue && permitted.value && generation === epoch && scope.value === identity
  }
  /** 只显示固定错误分类，异常正文及未知字段不会流入页面或公共日志。 */
  function message(error: unknown, fallback: string) {
    const code = error instanceof HttpError ? error.code : undefined
    if (code === 401 || code === 403 || code === 70015 || code === 70032)
      return '登录或读取权限已变化，请重新进入项目。'
    if (code === 70013) return '信任域不存在或当前项目不可见。'
    if (code === 70031) return '设备类型不存在、未发布、尚未生成产品标识或当前项目不可见。'
    if (code === 50001) return '项目不存在或当前账号不可见。'
    if (code === 10001) return '分页参数已失效，请刷新后重试。'
    return fallback
  }
  async function loadDomains(cursor?: string) {
    if (!visible.value || domainsLoading.value) return
    const epoch = generation,
      identity = scope.value,
      sequence = ++domainsSequence,
      project = props.projectId
    domainsLoading.value = true
    domainsError.value = ''
    if (!cursor) {
      domains.value = emptyOtaTrustDomainHistory()
      selectedDomain.value = ''
      keysSequence++
      keys.value = emptyOtaTrustKeyHistory()
      keysError.value = ''
      keysLoading.value = false
      keysReset.value = false
    }
    try {
      const page = publicOtaPage(
        await fetchOtaTrustDomains(project, { cursor, limit: 20 }),
        isOtaTrustDomain
      )
      if (!current(epoch, identity) || sequence !== domainsSequence) return
      domains.value = mergeOtaTrustDomainPage(domains.value, page, !!cursor)
    } catch (error) {
      if (current(epoch, identity) && sequence === domainsSequence)
        domainsError.value = message(error, '读取信任域失败，请重试。')
    } finally {
      if (current(epoch, identity) && sequence === domainsSequence) domainsLoading.value = false
    }
  }
  function openKeys(domain: string) {
    if (!visible.value || !domains.value.items.some((item) => item.trustDomain === domain)) return
    keysSequence++
    selectedDomain.value = domain
    keys.value = emptyOtaTrustKeyHistory()
    keysLoading.value = false
    keysError.value = ''
    keysReset.value = false
    void loadKeys()
  }
  async function loadKeys(cursor?: string) {
    if (!visible.value || !selectedDomain.value || keysLoading.value) return
    const epoch = generation,
      identity = scope.value,
      sequence = ++keysSequence,
      project = props.projectId,
      domain = selectedDomain.value
    const active = () =>
      current(epoch, identity) && sequence === keysSequence && domain === selectedDomain.value
    keysLoading.value = true
    keysError.value = ''
    if (!cursor) {
      keys.value = emptyOtaTrustKeyHistory()
      keysReset.value = false
    }
    try {
      let page
      try {
        page = publicOtaPage(
          await fetchOtaTrustKeys(project, domain, { cursor, limit: 20 }),
          isOtaTrustKey
        )
      } catch (error) {
        if (!active()) return
        if (!cursor || !(error instanceof HttpError) || error.code !== 10001) throw error
        // 服务端拒绝旧包游标时先丢弃全部旧包事实；只读首页恢复最多执行一次。
        keys.value = emptyOtaTrustKeyHistory()
        keysReset.value = true
        page = publicOtaPage(await fetchOtaTrustKeys(project, domain, { limit: 20 }), isOtaTrustKey)
        if (!active()) return
        keys.value = mergeOtaTrustKeyPage(keys.value, page, false)
        return
      }
      if (!active()) return
      keys.value = mergeOtaTrustKeyPage(keys.value, page, !!cursor)
    } catch (error) {
      if (active()) keysError.value = message(error, '读取发布键失败，请重试。')
    } finally {
      if (active()) keysLoading.value = false
    }
  }
  async function loadTypes() {
    if (!visible.value || typesLoading.value) return
    const epoch = generation,
      identity = scope.value,
      sequence = ++typesSequence,
      project = props.projectId
    typesLoading.value = true
    typesError.value = ''
    types.value = []
    let cursor: string | undefined
    const seen = new Set<string>(),
      ids = new Set<string>(),
      found: { id: string; name: string }[] = []
    try {
      do {
        const page = publicOtaPage(
          await fetchDeviceTypePage(project, cursor, 100),
          (value): value is import('@/api/device').DeviceTypeResponse =>
            isOtaMetadataType(value, project)
        )
        if (!current(epoch, identity) || sequence !== typesSequence) return
        for (const item of page.items) {
          if (ids.has(item.id!)) throw new Error('重复类型')
          ids.add(item.id!)
          if (item.status === 'PUBLISHED') found.push({ id: item.id!, name: item.name! })
        }
        cursor = page.hasMore ? page.nextCursor! : undefined
        if (cursor && seen.has(cursor)) throw new Error('重复游标')
        if (cursor) seen.add(cursor)
      } while (cursor)
      types.value = found
    } catch (error) {
      if (current(epoch, identity) && sequence === typesSequence)
        typesError.value = message(error, '读取已发布设备类型失败，请重试。')
    } finally {
      if (current(epoch, identity) && sequence === typesSequence) typesLoading.value = false
    }
  }
  function changeType() {
    baselineSequence++
    baseline.value = emptyOtaBaselineVersionHistory()
    baselineError.value = ''
    baselineLoading.value = false
    if (selectedType.value) void loadBaseline()
  }
  async function loadBaseline(cursor?: string) {
    if (
      !visible.value ||
      !types.value.some((item) => item.id === selectedType.value) ||
      baselineLoading.value
    )
      return
    const epoch = generation,
      identity = scope.value,
      sequence = ++baselineSequence,
      project = props.projectId,
      type = selectedType.value
    const active = () =>
      current(epoch, identity) && sequence === baselineSequence && type === selectedType.value
    baselineLoading.value = true
    baselineError.value = ''
    if (!cursor) baseline.value = emptyOtaBaselineVersionHistory()
    try {
      const page = publicOtaPage(
        await fetchOtaBaselineVersions(project, type, { cursor, limit: 20 }),
        isOtaBaselineVersion
      )
      if (active()) baseline.value = mergeOtaBaselineVersionPage(baseline.value, page, !!cursor)
    } catch (error) {
      if (active()) baselineError.value = message(error, '读取基线版本历史失败，请重试。')
    } finally {
      if (active()) baselineLoading.value = false
    }
  }
  watch(
    scope,
    () => {
      invalidate()
      if (props.modelValue) emit('update:modelValue', false)
    },
    { flush: 'sync' }
  )
  watch(
    () => props.modelValue,
    (open) => {
      invalidate()
      if (open && permitted.value) {
        void loadDomains()
        void loadTypes()
      } else if (open) emit('update:modelValue', false)
    },
    { immediate: true, flush: 'sync' }
  )
  onBeforeUnmount(invalidate)
</script>
