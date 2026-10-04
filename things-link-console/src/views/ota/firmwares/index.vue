<!--
  OTA固件管理页。

  本页只呈现平台能诚实判定的事实：固件草稿、上传会话与对象复验、生命周期记录，
  以及发布尝试的服务端结果。**发布清单是由外部发布链冻结并签名的工件**，控制台不代为
  编造：本页原样提交操作者粘贴的 `tc-ota-manifest/v1`，并把无受控 signer 时的
  fail-closed（70016/503）显示成环境边界而不是可重试的业务错误（ADR0119）。

  历史发布尝试与历史上传会话都从服务端只读集合端点按游标分页读取（S13-4e-5），
  不再用本次会话的本地记录冒充权威历史；信任域密钥清单仍缺读取端点，缺端点的范围
  登记在实施债务里。
-->
<template>
  <div class="console-page ota-firmwares console-page--single-panel">
    <div class="ota-firmwares__header console-toolbar console-page-actions">
      <div class="ota-firmwares__header-actions console-actions">
        <ElButton v-if="hasAuth('ota:deploy')" type="primary" :icon="Plus" @click="openCreate">
          创建固件草稿
        </ElButton>
      </div>
    </div>

    <ElAlert class="ota-firmwares__notice" type="info" :closable="false" show-icon>
      <template #title>固件发布前置条件</template>
      固件发布需要签名服务。未配置签名服务时，发布会被拒绝，也不会生成就绪或已发布版本。
    </ElAlert>

    <ElCard class="console-page__main-panel" shadow="never">
      <ElTable v-loading="loading" :data="firmwares" row-key="id">
        <ElTableColumn
          prop="firmwareVersion"
          label="固件版本"
          min-width="140"
          show-overflow-tooltip
        />
        <ElTableColumn label="状态" width="100">
          <template #default="{ row }">
            <ElTag :type="firmwareStatusTag(row.status)">{{
              firmwareStatusLabel(row.status)
            }}</ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="设备类型" min-width="290" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="ota-firmwares__mono">{{ row.deviceTypeId || '—' }}</span>
          </template>
        </ElTableColumn>
        <ElTableColumn label="物模型版本" min-width="290" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="ota-firmwares__mono">{{ row.thingModelVersionId || '—' }}</span>
          </template>
        </ElTableColumn>
        <ElTableColumn
          show-overflow-tooltip
          prop="revision"
          label="修订"
          width="80"
          align="right"
        />
        <ElTableColumn label="创建时间" width="180">
          <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
        </ElTableColumn>
        <ElTableColumn
          class-name="console-table-actions-cell"
          label="操作"
          width="184"
          fixed="right"
        >
          <template #default="{ row }">
            <ConsoleTableAction
              type="primary"
              @click="openDetail(row)"
              label="详情"
              icon="ri:eye-line"
            />
            <ConsoleTableAction
              v-if="hasAuth('ota:deploy') && actionsOf(row).canUpload"
              type="primary"
              @click="openUpload(row)"
              label="上传对象"
              icon="ri:upload-cloud-2-line"
            />
            <ConsoleTableAction
              v-if="hasAuth('ota:deploy') && actionsOf(row).canPublish"
              type="success"
              @click="openPublish(row)"
              label="提交发布"
              icon="ri:send-plane-line"
            />
            <ElDropdown
              v-if="hasAuth('ota:deploy')"
              trigger="click"
              @command="handleAction(row, $event)"
            >
              <ElButton
                link
                type="primary"
                class="console-table-action"
                aria-label="更多操作"
                title="更多操作"
                ><ArtSvgIcon icon="ri:more-2-fill"
              /></ElButton>
              <template #dropdown>
                <ElDropdownMenu>
                  <ElDropdownItem :disabled="!actionsOf(row).canDeprecate" command="deprecate">
                    退役（仅已就绪）
                  </ElDropdownItem>
                  <ElDropdownItem :disabled="!actionsOf(row).canRevoke" command="revoke">
                    撤销（仅已就绪/已退役）
                  </ElDropdownItem>
                  <ElDropdownItem :disabled="!actionsOf(row).canCancel" command="cancel" divided>
                    取消草稿
                  </ElDropdownItem>
                </ElDropdownMenu>
              </template>
            </ElDropdown>
          </template>
        </ElTableColumn>
        <template #empty>
          <ElEmpty description="还没有固件草稿">
            <ElButton v-if="hasAuth('ota:deploy')" type="primary" @click="openCreate">
              创建第一个固件草稿
            </ElButton>
          </ElEmpty>
        </template>
      </ElTable>
      <div v-if="cursor" class="ota-firmwares__more">
        <ElButton :loading="loading" @click="load(cursor)">加载更多</ElButton>
      </div>
    </ElCard>

    <ElDialog
      class="console-dialog"
      v-model="createVisible"
      title="创建固件草稿"
      width="560px"
      destroy-on-close
    >
      <ElForm ref="createFormRef" :model="createForm" label-width="120px">
        <ElFormItem label="设备类型" required>
          <ElSelect
            v-model="createForm.deviceTypeId"
            data-testid="ota-create-device-type"
            filterable
            placeholder="选择本项目已发布的设备类型"
            :loading="deviceTypesLoading"
            class="ota-firmwares__field"
            @change="resolveModelVersion"
          >
            <ElOption
              v-for="type in deviceTypes"
              :key="type.id"
              :label="`${type.name ?? ''}（${type.typeKey ?? ''}）`"
              :value="type.id ?? ''"
            />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="物模型版本">
          <div class="ota-firmwares__resolved">
            <span v-if="modelVersionLoading">读取中…</span>
            <span v-else-if="modelVersion">
              {{ modelVersion.versionNumber }} · {{ modelVersion.id }}
            </span>
            <span v-else-if="modelVersionError" class="ota-firmwares__warn">{{
              modelVersionError
            }}</span>
            <span v-else>选择设备类型后自动读取其最新已发布版本。</span>
          </div>
        </ElFormItem>
        <ElFormItem label="固件版本" required>
          <ElInput v-model="createForm.firmwareVersion" placeholder="例如 1.0.1" maxlength="64" />
        </ElFormItem>
      </ElForm>
      <ElAlert
        v-if="createError"
        type="error"
        :closable="false"
        show-icon
        class="ota-firmwares__form-error"
      >
        {{ createError }}
      </ElAlert>
      <template #footer>
        <ElButton @click="createVisible = false">取消</ElButton>
        <ElButton
          type="primary"
          :loading="submitting"
          :disabled="!canSubmitCreate"
          @click="submitCreate"
        >
          创建草稿
        </ElButton>
      </template>
    </ElDialog>

    <ElDialog
      class="console-dialog"
      v-model="uploadVisible"
      title="上传固件对象"
      width="620px"
      destroy-on-close
    >
      <ElAlert type="info" :closable="false" show-icon class="ota-firmwares__form-error">
        上传只证明字节与承诺一致；签名、硬件资格与安全版本由发布与设备侧证据决定。
      </ElAlert>
      <ElForm label-width="120px">
        <ElFormItem label="固件版本">
          <span>{{ uploading?.firmwareVersion || '—' }}</span>
        </ElFormItem>
        <ElFormItem label="固件文件" required>
          <input
            class="ota-firmwares__file"
            type="file"
            :disabled="uploading !== undefined"
            @change="onFileSelected"
          />
        </ElFormItem>
        <ElFormItem v-if="selectedFile" label="长度与摘要">
          <div class="ota-firmwares__resolved">
            <div>
              {{ formatBytes(selectedFile.size) }}
              <span v-if="selectedFile.size > OTA_MAX_ARTIFACT_BYTES" class="ota-firmwares__warn">
                （超过单次上界 {{ formatBytes(OTA_MAX_ARTIFACT_BYTES) }}）
              </span>
            </div>
            <div class="ota-firmwares__mono">{{ artifactSha256 || '计算中…' }}</div>
          </div>
        </ElFormItem>
        <ElFormItem v-if="uploadSession" label="会话状态" data-testid="ota-upload-session">
          <div class="ota-firmwares__resolved">
            <ElTag :type="uploadSession.status === 'VERIFIED' ? 'success' : 'info'">
              {{ uploadStatusLabel(uploadSession.status) }}
            </ElTag>
            <span class="ota-firmwares__mono">{{ uploadSession.id }}</span>
            <span v-if="uploadSession.failureCode" class="ota-firmwares__warn">
              {{ uploadSession.failureCode }}
            </span>
          </div>
        </ElFormItem>
      </ElForm>
      <ElAlert
        v-if="uploadError"
        type="error"
        :closable="false"
        show-icon
        class="ota-firmwares__form-error"
      >
        {{ uploadError }}
      </ElAlert>
      <template #footer>
        <ElButton v-if="uploadSession && uploadSession.status !== 'VERIFIED'" @click="cancelUpload">
          登记取消上传
        </ElButton>
        <ElButton v-if="uploadSession" @click="refreshUploadSession">刷新会话状态</ElButton>
        <ElButton @click="uploadVisible = false">关闭</ElButton>
        <ElButton
          type="primary"
          :loading="submitting"
          :disabled="!selectedFile || !artifactSha256 || !uploadSizeOk"
          @click="submitUpload"
        >
          创建会话并上传
        </ElButton>
      </template>
    </ElDialog>

    <ElDialog
      class="console-dialog"
      v-model="publishVisible"
      title="提交发布尝试"
      width="720px"
      destroy-on-close
    >
      <ElForm label-width="130px">
        <ElFormItem label="固件版本">
          <span>{{ publishing?.firmwareVersion || '—' }}</span>
        </ElFormItem>
        <ElFormItem label="上传会话" required>
          <ElInput v-model="publishForm.uploadSessionId" placeholder="已完成对象复验的上传会话ID" />
        </ElFormItem>
        <ElFormItem label="固件修订" required>
          <ElInput v-model="publishForm.expectedRevision" placeholder="固件当前修订" />
        </ElFormItem>
        <ElFormItem label="manifest" required>
          <ElInput
            v-model="publishForm.manifestText"
            type="textarea"
            :rows="12"
            placeholder="粘贴发布流程冻结的 tc-ota-manifest/v1 JSON"
          />
        </ElFormItem>
      </ElForm>
      <ElAlert
        v-if="publishError"
        type="error"
        :closable="false"
        show-icon
        class="ota-firmwares__form-error"
      >
        {{ publishError }}
      </ElAlert>
      <ElAlert
        v-if="publishOutcome"
        data-testid="ota-publish-outcome"
        :type="publishOutcome.kind === 'REJECTED_BY_ENVIRONMENT' ? 'warning' : 'error'"
        :closable="false"
        show-icon
        class="ota-firmwares__form-error"
      >
        {{ publishOutcome.message }}
      </ElAlert>
      <template #footer>
        <ElButton @click="publishVisible = false">关闭</ElButton>
        <ElButton type="primary" :loading="submitting" @click="submitPublish">提交发布</ElButton>
      </template>
    </ElDialog>

    <ElDrawer
      v-model="detailVisible"
      :title="`固件 ${detail?.firmwareVersion ?? ''}`"
      size="680px"
      destroy-on-close
    >
      <ElDescriptions :column="1" border>
        <ElDescriptionsItem label="固件 ID">
          <span class="ota-firmwares__mono">{{ detail?.id || '—' }}</span>
        </ElDescriptionsItem>
        <ElDescriptionsItem label="状态">
          <ElTag :type="firmwareStatusTag(detail?.status)">{{
            firmwareStatusLabel(detail?.status)
          }}</ElTag>
          <span class="ota-firmwares__hint">{{ actionsOf(detail ?? {}).reason }}</span>
        </ElDescriptionsItem>
        <ElDescriptionsItem label="产品标识">{{ detail?.productKey || '—' }}</ElDescriptionsItem>
        <ElDescriptionsItem label="设备类型">
          <span class="ota-firmwares__mono">{{ detail?.deviceTypeId || '—' }}</span>
        </ElDescriptionsItem>
        <ElDescriptionsItem label="物模型版本">
          <span class="ota-firmwares__mono">{{ detail?.thingModelVersionId || '—' }}</span>
        </ElDescriptionsItem>
        <ElDescriptionsItem label="模型摘要算法">{{
          detail?.schemaDigestAlgorithm || '—'
        }}</ElDescriptionsItem>
        <ElDescriptionsItem label="模型摘要">
          <span class="ota-firmwares__mono">{{ detail?.schemaDigest || '—' }}</span>
        </ElDescriptionsItem>
        <ElDescriptionsItem label="修订">{{ detail?.revision || '—' }}</ElDescriptionsItem>
        <ElDescriptionsItem label="创建时间">{{
          formatTime(detail?.createdAt)
        }}</ElDescriptionsItem>
        <ElDescriptionsItem label="取消时间">{{
          formatTime(detail?.cancelledAt)
        }}</ElDescriptionsItem>
      </ElDescriptions>

      <template v-if="lifecycle">
        <ElDivider content-position="left">生命周期</ElDivider>
        <ElDescriptions :column="1" border>
          <ElDescriptionsItem label="退役">
            <span v-if="lifecycle.deprecation">
              {{ lifecycle.deprecation.reason }} ·
              {{ formatTime(lifecycle.deprecation.occurredAt) }}
            </span>
            <span v-else>—</span>
          </ElDescriptionsItem>
          <ElDescriptionsItem label="撤销">
            <span v-if="lifecycle.revocation">
              {{ lifecycle.revocation.reason }} · {{ formatTime(lifecycle.revocation.occurredAt) }}
            </span>
            <span v-else>—</span>
          </ElDescriptionsItem>
        </ElDescriptions>
      </template>

      <ElDivider content-position="left">上传会话历史</ElDivider>
      <ElAlert
        v-if="uploadHistoryError"
        class="ota-firmwares__form-error"
        type="error"
        :closable="false"
        show-icon
      >
        {{ uploadHistoryError }}
      </ElAlert>
      <ElTable
        v-loading="uploadHistoryLoading"
        :data="uploadHistory.items"
        size="small"
        row-key="id"
      >
        <ElTableColumn label="会话 ID" min-width="280">
          <template #default="{ row }">
            <span class="ota-firmwares__mono">{{ row.id }}</span>
          </template>
        </ElTableColumn>
        <ElTableColumn label="状态" width="120">
          <template #default="{ row }">{{ uploadStatusLabel(row.status) }}</template>
        </ElTableColumn>
        <ElTableColumn label="大小" width="110">
          <template #default="{ row }">{{ formatBytes(row.expectedLength) }}</template>
        </ElTableColumn>
        <ElTableColumn label="失败归因" min-width="140">
          <template #default="{ row }">{{ row.failureCode || '—' }}</template>
        </ElTableColumn>
        <ElTableColumn label="创建时间" width="170">
          <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
        </ElTableColumn>
        <template #empty>
          <ElEmpty description="该固件还没有上传会话" />
        </template>
      </ElTable>
      <div v-if="uploadHistory.hasMore" class="ota-firmwares__more">
        <ElButton :loading="uploadHistoryLoading" @click="loadMoreUploads">加载更多</ElButton>
      </div>

      <ElDivider content-position="left">发布尝试历史</ElDivider>
      <ElAlert
        v-if="publicationHistoryError"
        class="ota-firmwares__form-error"
        type="error"
        :closable="false"
        show-icon
      >
        {{ publicationHistoryError }}
      </ElAlert>
      <ElTable
        v-loading="publicationHistoryLoading"
        :data="publicationHistory.items"
        size="small"
        row-key="id"
      >
        <ElTableColumn label="尝试 ID" min-width="280">
          <template #default="{ row }">
            <span class="ota-firmwares__mono">{{ row.id }}</span>
          </template>
        </ElTableColumn>
        <ElTableColumn show-overflow-tooltip prop="status" label="状态" width="130" />
        <ElTableColumn label="修订" width="90">
          <template #default="{ row }">{{ row.revision || '—' }}</template>
        </ElTableColumn>
        <ElTableColumn label="失败归因" min-width="140">
          <template #default="{ row }">{{ row.failureCode || '—' }}</template>
        </ElTableColumn>
        <ElTableColumn label="更新时间" width="170">
          <template #default="{ row }">{{ formatTime(row.updatedAt) }}</template>
        </ElTableColumn>
        <template #empty>
          <ElEmpty description="该固件还没有发布尝试" />
        </template>
      </ElTable>
      <div v-if="publicationHistory.hasMore" class="ota-firmwares__more">
        <ElButton :loading="publicationHistoryLoading" @click="loadMorePublications"
          >加载更多</ElButton
        >
      </div>
    </ElDrawer>
  </div>
</template>

<script setup lang="ts">
  import ConsoleTableAction from '@/components/ConsoleTableAction.vue'

  import { formatTime } from '@/utils/time'
  import { Plus } from '@element-plus/icons-vue'
  import { fetchDeviceTypePage, type DeviceTypeResponse } from '@/api/device'
  import {
    cancelOtaFirmware,
    cancelOtaUpload,
    createOtaFirmware,
    createOtaPublication,
    createOtaUpload,
    deprecateOtaFirmware,
    fetchLatestThingModelVersion,
    fetchOtaFirmwareLifecycle,
    fetchOtaFirmwares,
    fetchOtaPublicationHistory,
    fetchOtaUploadHistory,
    fetchOtaUpload,
    revokeOtaFirmware,
    sha256Hex,
    uploadOtaContent,
    OtaUploadError,
    type OtaFirmwareLifecycleResponse,
    type OtaFirmwareResponse,
    type OtaPublicationCreateRequest,
    type OtaPublicationResponse,
    type OtaUploadResponse,
    type ThingModelVersionResponse
  } from '@/api/ota'
  import {
    checkArtifactSize,
    emptyOtaHistory,
    firmwareActions,
    firmwareStatusLabel,
    firmwareStatusTag,
    formatBytes,
    mergeOtaHistoryPage,
    OTA_MAX_ARTIFACT_BYTES,
    parseManifestInput,
    publicationEnvironmentBoundary,
    uploadStatusLabel,
    type OtaHistoryState
  } from '@/features/ota/firmware-model'
  import { useAuth } from '@/hooks/core/useAuth'
  import { useUserStore } from '@/store/modules/user'
  import { HttpError } from '@/utils/http/error'
  import { IdempotentSubmission } from '@/utils/idempotent-submission'

  defineOptions({ name: 'OtaFirmwares' })

  const userStore = useUserStore()
  const { hasAuth } = useAuth()
  const projectId = computed(() => userStore.info.currentProjectId ?? '')

  const loading = ref(false)
  const submitting = ref(false)
  const firmwares = ref<OtaFirmwareResponse[]>([])
  const cursor = ref('')
  /** 本次会话提交过的上传会话，按固件聚合；仅用于动作资格与表单预填，不作为权威历史。 */
  const uploadSessions = reactive<Record<string, OtaUploadResponse>>({})

  const createVisible = ref(false)
  const createError = ref('')
  const deviceTypesLoading = ref(false)
  const deviceTypes = ref<DeviceTypeResponse[]>([])
  const modelVersionLoading = ref(false)
  const modelVersion = ref<ThingModelVersionResponse>()
  const modelVersionError = ref('')
  const createForm = reactive({ deviceTypeId: '', firmwareVersion: '' })

  const uploadVisible = ref(false)
  const uploading = ref<OtaFirmwareResponse>()
  const selectedFile = ref<File>()
  const artifactSha256 = ref('')
  const uploadSizeOk = ref(false)
  const uploadSession = ref<OtaUploadResponse>()
  const uploadError = ref('')

  const publishVisible = ref(false)
  const publishing = ref<OtaFirmwareResponse>()
  const publishForm = reactive({ uploadSessionId: '', expectedRevision: '', manifestText: '' })
  const publishError = ref('')
  const publishOutcome = ref<{ kind: 'REJECTED' | 'REJECTED_BY_ENVIRONMENT'; message: string }>()

  const detailVisible = ref(false)
  const detail = ref<OtaFirmwareResponse>()
  const lifecycle = ref<OtaFirmwareLifecycleResponse>()
  /** 详情抽屉的服务端权威历史上传会话；游标原样透传，不在本地解释。 */
  const uploadHistory = ref<OtaHistoryState<OtaUploadResponse>>(emptyOtaHistory())
  const uploadHistoryLoading = ref(false)
  const uploadHistoryError = ref('')
  /** 详情抽屉的服务端权威历史发布尝试。 */
  const publicationHistory = ref<OtaHistoryState<OtaPublicationResponse>>(emptyOtaHistory())
  const publicationHistoryLoading = ref(false)
  const publicationHistoryError = ref('')

  const createKeys = new IdempotentSubmission()
  const uploadKeys = new IdempotentSubmission()
  const publishKeys = new IdempotentSubmission()
  const actionKeys = new IdempotentSubmission()

  const canSubmitCreate = computed(
    () =>
      createForm.deviceTypeId !== '' &&
      createForm.firmwareVersion.trim() !== '' &&
      modelVersion.value !== undefined
  )

  /** 固件动作资格；详情抽屉与列表共用同一判定。 */
  const actionsOf = (row: Partial<OtaFirmwareResponse>) =>
    firmwareActions(row.status, row.id !== undefined && uploadSessions[row.id] !== undefined)

  function report(error: unknown, fallback: string): string {
    if (error instanceof HttpError) return error.message
    if (error instanceof OtaUploadError) return error.message
    console.error(error)
    return fallback
  }

  async function load(next?: string) {
    if (projectId.value === '') return
    loading.value = true
    try {
      const page = await fetchOtaFirmwares(projectId.value, next)
      firmwares.value = next ? [...firmwares.value, ...(page.items ?? [])] : (page.items ?? [])
      cursor.value = page.hasMore ? (page.nextCursor ?? '') : ''
    } catch (error) {
      ElMessage.error(report(error, '读取固件列表失败。'))
    } finally {
      loading.value = false
    }
  }

  /** 读取固件历史上传会话；续页在已有事实之后追加，失败保留已加载事实。 */
  async function loadUploadHistory(firmwareId: string, next?: string) {
    uploadHistoryLoading.value = true
    uploadHistoryError.value = ''
    try {
      const page = await fetchOtaUploadHistory(projectId.value, firmwareId, {
        cursor: next,
        limit: 20
      })
      uploadHistory.value = mergeOtaHistoryPage(uploadHistory.value, page, next !== undefined)
    } catch (error) {
      uploadHistoryError.value = report(error, '读取上传会话历史失败。')
    } finally {
      uploadHistoryLoading.value = false
    }
  }

  /** 读取固件历史发布尝试；续页在已有事实之后追加，失败保留已加载事实。 */
  async function loadPublicationHistory(firmwareId: string, next?: string) {
    publicationHistoryLoading.value = true
    publicationHistoryError.value = ''
    try {
      const page = await fetchOtaPublicationHistory(projectId.value, firmwareId, {
        cursor: next,
        limit: 20
      })
      publicationHistory.value = mergeOtaHistoryPage(
        publicationHistory.value,
        page,
        next !== undefined
      )
    } catch (error) {
      publicationHistoryError.value = report(error, '读取发布尝试历史失败。')
    } finally {
      publicationHistoryLoading.value = false
    }
  }

  /** 抽屉内继续加载下一页上传会话；游标为空表示已到末页。 */
  function loadMoreUploads() {
    const id = detail.value?.id
    if (id === undefined || uploadHistory.value.cursor === '') return
    void loadUploadHistory(id, uploadHistory.value.cursor)
  }

  /** 抽屉内继续加载下一页发布尝试。 */
  function loadMorePublications() {
    const id = detail.value?.id
    if (id === undefined || publicationHistory.value.cursor === '') return
    void loadPublicationHistory(id, publicationHistory.value.cursor)
  }

  /** 当前抽屉固件的历史事实发生变化后按权威端点重载首页。 */
  function reloadDetailHistory(firmwareId: string) {
    if (detail.value?.id !== firmwareId) return
    void loadUploadHistory(firmwareId)
    void loadPublicationHistory(firmwareId)
  }

  async function openCreate() {
    createError.value = ''
    createForm.deviceTypeId = ''
    createForm.firmwareVersion = ''
    modelVersion.value = undefined
    modelVersionError.value = ''
    createVisible.value = true
    if (deviceTypes.value.length === 0) {
      deviceTypesLoading.value = true
      try {
        const page = await fetchDeviceTypePage(projectId.value, undefined, 100)
        deviceTypes.value = page.items ?? []
      } catch (error) {
        createError.value = report(error, '读取设备类型失败。')
      } finally {
        deviceTypesLoading.value = false
      }
    }
  }

  /** 物模型版本身份不随类型详情下发，创建草稿前必须显式读取，读不到就不允许提交。 */
  async function resolveModelVersion() {
    modelVersion.value = undefined
    modelVersionError.value = ''
    if (createForm.deviceTypeId === '') return
    modelVersionLoading.value = true
    try {
      modelVersion.value = await fetchLatestThingModelVersion(
        projectId.value,
        createForm.deviceTypeId
      )
    } catch (error) {
      if (error instanceof HttpError && error.code === 30052) {
        modelVersionError.value = '该设备类型还没有已发布的物模型版本，请先发布设备类型。'
      } else {
        modelVersionError.value = report(error, '读取物模型版本失败。')
      }
    } finally {
      modelVersionLoading.value = false
    }
  }

  async function submitCreate() {
    if (!canSubmitCreate.value || !modelVersion.value?.id) return
    const fingerprint = JSON.stringify([
      projectId.value,
      createForm.deviceTypeId,
      modelVersion.value.id,
      createForm.firmwareVersion.trim()
    ])
    const key = createKeys.keyFor(fingerprint)
    submitting.value = true
    createError.value = ''
    try {
      const created = await createOtaFirmware(
        projectId.value,
        {
          deviceTypeId: createForm.deviceTypeId,
          thingModelVersionId: modelVersion.value.id,
          firmwareVersion: createForm.firmwareVersion.trim()
        },
        key
      )
      createKeys.succeeded(key)
      ElMessage.success('固件草稿已创建。')
      createVisible.value = false
      await load()
      if (created.id) openUpload(created)
    } catch (error) {
      const uncertain = !(error instanceof HttpError)
      createKeys.failed(key, uncertain)
      createError.value = report(error, '创建固件草稿结果未知，请刷新列表核对。')
    } finally {
      submitting.value = false
    }
  }

  function openUpload(row: OtaFirmwareResponse) {
    uploading.value = row
    selectedFile.value = undefined
    artifactSha256.value = ''
    uploadSizeOk.value = false
    uploadSession.value = row.id ? uploadSessions[row.id] : undefined
    uploadError.value = ''
    uploadVisible.value = true
  }

  async function onFileSelected(event: Event) {
    const input = event.target as HTMLInputElement
    const file = input.files?.[0]
    selectedFile.value = file
    artifactSha256.value = ''
    uploadError.value = ''
    if (!file) {
      uploadSizeOk.value = false
      return
    }
    const check = checkArtifactSize(file.size)
    uploadSizeOk.value = check.ok
    if (!check.ok) {
      uploadError.value = check.reason ?? '固件文件不符合要求。'
      return
    }
    try {
      artifactSha256.value = await sha256Hex(await file.arrayBuffer())
    } catch {
      artifactSha256.value = ''
      uploadError.value = '无法在本地计算摘要，请使用支持 WebCrypto 的浏览器访问。'
    }
  }

  async function submitUpload() {
    const row = uploading.value
    const file = selectedFile.value
    if (!row?.id || !file || artifactSha256.value === '') return
    if (!row.revision) {
      uploadError.value = '固件修订缺失，无法创建上传会话。'
      return
    }
    const fingerprint = JSON.stringify([row.id, file.size, artifactSha256.value])
    const key = uploadKeys.keyFor(fingerprint)
    submitting.value = true
    uploadError.value = ''
    try {
      const session = await createOtaUpload(
        projectId.value,
        row.id,
        { expectedLength: file.size, expectedSha256: artifactSha256.value },
        key
      )
      uploadKeys.succeeded(key)
      if (session.id) {
        uploadSession.value = session
        uploadSessions[row.id] = session
      }
      if (!session.id) {
        uploadError.value = '上传会话缺少标识，请刷新会话状态。'
        return
      }
      const verified = await uploadOtaContent(
        projectId.value,
        row.id,
        session.id,
        await file.arrayBuffer()
      )
      uploadSession.value = verified
      uploadSessions[row.id] = verified
      reloadDetailHistory(row.id)
      ElMessage.success(`上传会话状态：${uploadStatusLabel(verified.status)}`)
      await load()
    } catch (error) {
      uploadKeys.failed(key, !(error instanceof HttpError) && !(error instanceof OtaUploadError))
      uploadError.value = report(error, '上传结果未知，请刷新会话权威状态后再决定是否重传。')
    } finally {
      submitting.value = false
    }
  }

  async function refreshUploadSession() {
    const row = uploading.value
    const session = uploadSession.value
    if (!row?.id || !session?.id) return
    try {
      const current = await fetchOtaUpload(projectId.value, row.id, session.id)
      uploadSession.value = current
      uploadSessions[row.id] = current
    } catch (error) {
      uploadError.value = report(error, '读取上传会话失败。')
    }
  }

  async function cancelUpload() {
    const row = uploading.value
    const session = uploadSession.value
    if (!row?.id || !session?.id || !session.revision) return
    const key = uploadKeys.keyFor(JSON.stringify(['cancel', session.id, session.revision]))
    try {
      const cancelled = await cancelOtaUpload(
        projectId.value,
        row.id,
        session.id,
        { expectedRevision: session.revision },
        key
      )
      uploadKeys.succeeded(key)
      uploadSession.value = cancelled
      uploadSessions[row.id] = cancelled
      ElMessage.success('已登记上传取消；对象回收由后台完成。')
    } catch (error) {
      const uncertain = !(error instanceof HttpError)
      uploadKeys.failed(key, uncertain)
      uploadError.value = report(error, '登记上传取消失败。')
    }
  }

  function openPublish(row: OtaFirmwareResponse) {
    publishing.value = row
    publishForm.uploadSessionId = row.id ? (uploadSessions[row.id]?.id ?? '') : ''
    publishForm.expectedRevision = row.revision ?? ''
    publishForm.manifestText = ''
    publishError.value = ''
    publishOutcome.value = undefined
    publishVisible.value = true
  }

  async function submitPublish() {
    const row = publishing.value
    if (!row?.id) return
    const parsed = parseManifestInput(publishForm.manifestText)
    if (!parsed.ok || !parsed.manifest) {
      publishError.value = parsed.reason ?? 'manifest不符合合同。'
      return
    }
    if (publishForm.uploadSessionId.trim() === '' || publishForm.expectedRevision.trim() === '') {
      publishError.value = '上传会话与固件修订都是必填。'
      return
    }
    const fingerprint = JSON.stringify([
      row.id,
      publishForm.expectedRevision.trim(),
      publishForm.uploadSessionId.trim(),
      publishForm.manifestText.trim()
    ])
    const key = publishKeys.keyFor(fingerprint)
    submitting.value = true
    publishError.value = ''
    publishOutcome.value = undefined
    try {
      await createOtaPublication(
        projectId.value,
        row.id,
        {
          expectedRevision: publishForm.expectedRevision.trim(),
          uploadSessionId: publishForm.uploadSessionId.trim(),
          manifest: parsed.manifest as OtaPublicationCreateRequest['manifest']
        },
        key
      )
      publishKeys.succeeded(key)
      reloadDetailHistory(row.id)
      ElMessage.success('已建立发布尝试，请查询结果。')
      await load()
    } catch (error) {
      const code = error instanceof HttpError ? error.code : undefined
      publishKeys.failed(key, !(error instanceof HttpError))
      const boundary = publicationEnvironmentBoundary(code)
      if (boundary !== null) {
        publishOutcome.value = {
          kind: 'REJECTED_BY_ENVIRONMENT',
          message:
            boundary === 'TRUST_NOT_CONFIGURED'
              ? '发布被环境边界拒绝（70010）：本项目还没有登记受控发布信任（离线根签名 bundle），服务端按 ADR0119 fail-closed，未产生 READY 或已发布事实。'
              : `发布被环境边界拒绝（${code ?? '未知'}）：本环境没有受控 signer 适配器，服务端按 ADR0119 fail-closed，未产生 READY 或已发布事实。`
        }
      } else {
        publishOutcome.value = {
          kind: 'REJECTED',
          message: report(error, '发布尝试被拒绝。')
        }
      }
    } finally {
      submitting.value = false
    }
  }

  async function handleAction(row: OtaFirmwareResponse, command: string) {
    if (!row.id) return
    if (command === 'deprecate' || command === 'revoke') {
      const label = command === 'deprecate' ? '退役' : '撤销'
      const reason = await promptReason(label, row.firmwareVersion ?? '')
      if (reason === undefined) return
      const key = actionKeys.keyFor(JSON.stringify([command, row.id, row.revision, reason]))
      try {
        const body = { expectedRevision: row.revision ?? '', reason }
        if (command === 'deprecate') await deprecateOtaFirmware(projectId.value, row.id, body, key)
        else await revokeOtaFirmware(projectId.value, row.id, body, key)
        actionKeys.succeeded(key)
        ElMessage.success(`固件已${label}。`)
        await load()
      } catch (error) {
        actionKeys.failed(key, !(error instanceof HttpError))
        ElMessage.error(report(error, `${label}失败。`))
      }
      return
    }
    if (command === 'cancel') {
      try {
        await ElMessageBox.confirm(
          `取消草稿 ${row.firmwareVersion ?? ''} 后不能再次上传或发布；已上传对象的回收由后台完成。`,
          '取消固件草稿',
          { confirmButtonText: '取消草稿', cancelButtonText: '返回', type: 'warning' }
        )
      } catch {
        return
      }
      const key = actionKeys.keyFor(JSON.stringify(['cancel', row.id, row.revision]))
      try {
        await cancelOtaFirmware(
          projectId.value,
          row.id,
          { expectedRevision: row.revision ?? '' },
          key
        )
        actionKeys.succeeded(key)
        ElMessage.success('固件草稿已取消。')
        await load()
      } catch (error) {
        actionKeys.failed(key, !(error instanceof HttpError))
        ElMessage.error(report(error, '取消固件草稿失败。'))
      }
    }
  }

  async function promptReason(label: string, version: string): Promise<string | undefined> {
    try {
      const result = await ElMessageBox.prompt(
        `请输入${label}固件 ${version} 的原因（会写入生命周期记录）。`,
        `${label}固件`,
        { inputPattern: /\S+/, inputErrorMessage: '原因不能为空', confirmButtonText: label }
      )
      return result.value
    } catch {
      return undefined
    }
  }

  async function openDetail(row: OtaFirmwareResponse) {
    detail.value = row
    lifecycle.value = undefined
    // 先清空历史，避免上一个固件的权威事实在首屏请求返回前被误读成本固件的历史。
    uploadHistory.value = emptyOtaHistory()
    uploadHistoryError.value = ''
    publicationHistory.value = emptyOtaHistory()
    publicationHistoryError.value = ''
    detailVisible.value = true
    if (!row.id) return
    try {
      lifecycle.value = await fetchOtaFirmwareLifecycle(projectId.value, row.id)
    } catch (error) {
      ElMessage.error(report(error, '读取固件生命周期失败。'))
    }
    await Promise.all([loadUploadHistory(row.id), loadPublicationHistory(row.id)])
  }

  onMounted(() => void load())
</script>

<style lang="scss" scoped>
  .ota-firmwares {
    &__header {
      display: flex;
      gap: 10px;
      align-items: flex-start;
      justify-content: space-between;
      margin-bottom: 10px;

      h3 {
        margin: 0 0 4px;
        font-size: 18px;
      }

      p {
        margin: 0;
        font-size: 13px;
        color: var(--art-text-gray-600);
      }
    }

    &__header-actions {
      display: flex;
      flex-shrink: 0;
      gap: 8px;
    }

    &__notice,
    &__form-error {
      margin-bottom: 10px;
    }

    &__more {
      display: flex;
      justify-content: center;
      margin-top: 10px;
    }

    &__field {
      width: 100%;
    }

    &__resolved {
      display: flex;
      flex-direction: column;
      gap: 4px;
      line-height: 1.5;
    }

    &__mono {
      font-family: var(--art-font-mono, monospace);
      font-size: 12px;
      word-break: break-all;
    }

    &__hint {
      margin-left: 8px;
      font-size: 12px;
      color: var(--art-text-gray-600);
    }

    &__warn {
      color: var(--el-color-warning);
    }

    &__more-icon {
      margin-left: 2px;
    }

    &__file {
      width: 100%;
    }
  }
</style>
