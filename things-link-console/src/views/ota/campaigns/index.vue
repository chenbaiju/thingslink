<!--
  OTA灰度活动与批次页。

  本页呈现活动的冻结事实（目标、批次、执行快照）并提供管理动作。动作资格只是"界面不呈现
  注定失败的操作"：每个动作仍由服务端按CAS、角色与安全条件裁决。

  **本机没有正式signer**，固件无法到达已就绪发布态，因此创建活动在本机必然被服务端拒绝
  （活动只能引用已签名发布的产物）。本页把该拒绝如实呈现为环境边界，绝不本地伪造成功，
  也不把"本地校验通过"当作可以创建成功。
-->
<template>
  <div class="console-page ota-campaigns console-page--single-panel">
    <ConsoleWorkspaceHeader
      project-style
      title="升级活动"
      description="从已发布固件组织灰度升级，按冻结目标查看执行。"
    >
      <template #actions>
        <ElButton v-if="hasAuth('ota:deploy')" type="primary" :icon="Plus" @click="openCreate">
          创建活动
        </ElButton>
      </template>
    </ConsoleWorkspaceHeader>

    <ElAlert
      class="ota-campaigns__notice"
      title="创建或启动活动前，请确认固件已完成签名发布。"
      type="info"
      :closable="false"
      show-icon
    />

    <ElCard class="console-page__main-panel" shadow="never">
      <ElTable v-loading="loading" :data="campaigns" row-key="id">
        <ElTableColumn label="状态" width="110">
          <template #default="{ row }">
            <ElTag :type="campaignStatusTag(row.status)">{{
              campaignStatusLabel(row.status)
            }}</ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="固件" min-width="290" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="ota-campaigns__mono">{{ row.firmwareId }}</span>
          </template>
        </ElTableColumn>
        <ElTableColumn
          show-overflow-tooltip
          prop="targetCount"
          label="目标"
          width="80"
          align="right"
        />
        <ElTableColumn
          show-overflow-tooltip
          prop="batchCount"
          label="批次"
          width="80"
          align="right"
        />
        <ElTableColumn
          show-overflow-tooltip
          prop="stateVersion"
          label="修订"
          width="80"
          align="right"
        />
        <ElTableColumn label="创建时间" width="180">
          <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
        </ElTableColumn>
        <ElTableColumn label="排程时间" width="180">
          <template #default="{ row }">{{ formatTime(row.scheduledAt) }}</template>
        </ElTableColumn>
        <ElTableColumn
          class-name="console-table-actions-cell"
          label="操作"
          width="64"
          fixed="right"
        >
          <template #default="{ row }">
            <ConsoleTableAction
              type="primary"
              @click="openDetail(row)"
              label="详情"
              icon="ri:eye-line"
            />
          </template>
        </ElTableColumn>
        <template #empty>
          <ElEmpty description="还没有灰度活动">
            <ElButton v-if="hasAuth('ota:deploy')" type="primary" @click="openCreate">
              创建第一个活动
            </ElButton>
          </ElEmpty>
        </template>
      </ElTable>
      <div v-if="cursor" class="ota-campaigns__more">
        <ElButton :loading="loading" @click="load(cursor)">加载更多</ElButton>
      </div>
    </ElCard>

    <ElDialog
      class="console-dialog"
      v-model="createVisible"
      title="创建灰度活动"
      width="720px"
      destroy-on-close
    >
      <ElForm label-width="130px">
        <ElFormItem label="固件" required>
          <ElSelect
            v-model="draft.firmwareId"
            data-testid="ota-campaign-firmware"
            filterable
            placeholder="选择要发布的固件（需已签名发布）"
            class="ota-campaigns__field"
          >
            <ElOption
              v-for="item in firmwares"
              :key="item.id"
              :label="`${item.firmwareVersion ?? ''}（${firmwareStatusLabel(item.status)}）`"
              :value="item.id ?? ''"
            />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="目标设备" required>
          <ElSelect
            v-model="draft.deviceIds"
            data-testid="ota-campaign-devices"
            multiple
            filterable
            placeholder="选择同一设备类型下的目标设备"
            class="ota-campaigns__field"
          >
            <ElOption
              v-for="device in devices"
              :key="device.id"
              :label="device.name ?? device.id ?? ''"
              :value="device.id ?? ''"
            />
          </ElSelect>
        </ElFormItem>
        <ElFormItem label="批次大小" required>
          <ElInputNumber v-model="draft.batchSize" :min="1" :max="1000" />
        </ElFormItem>
        <ElFormItem label="最早启动时间" required>
          <ElDatePicker
            v-model="notBeforeLocal"
            type="datetime"
            placeholder="到达该时间后才能启动第一批"
          />
        </ElFormItem>
        <ElFormItem class="ota-campaigns__policy-row" label="执行策略">
          <ElCollapse class="ota-campaigns__policy">
            <ElCollapseItem title="高级策略（保守默认值，可修改）" name="policy">
              <ElFormItem label="并发下载" label-width="150px">
                <ElInputNumber v-model="policy.maxConcurrentDownloads" :min="1" :max="1000" />
              </ElFormItem>
              <ElFormItem label="单设备带宽上限" label-width="150px">
                <ElInputNumber
                  v-model="policy.maxDownloadBytesPerSecond"
                  :min="1"
                  :max="1073741824"
                />
              </ElFormItem>
              <ElFormItem label="重试上限" label-width="150px">
                <ElInputNumber v-model="policy.downloadRetryLimit" :min="0" :max="10" />
              </ElFormItem>
              <ElFormItem label="退避秒数" label-width="150px">
                <ElInputNumber v-model="policy.retryBackoffSeconds" :min="1" :max="3600" />
              </ElFormItem>
              <ElFormItem label="健康窗口秒数" label-width="150px">
                <ElInputNumber v-model="policy.healthWindowSeconds" :min="1" :max="86400" />
              </ElFormItem>
              <ElFormItem label="失败阈值（样本/失败数）" label-width="150px">
                <ElInputNumber v-model="policy.pauseMinEvaluated" :min="1" :max="1000" />
                <ElInputNumber v-model="policy.pauseFailureCount" :min="1" :max="1000" />
              </ElFormItem>
              <ElFormItem label="失败率/成功率(bps)" label-width="150px">
                <ElInputNumber v-model="policy.pauseFailureRateBps" :min="1" :max="10000" />
                <ElInputNumber v-model="policy.batchMinSuccessRateBps" :min="1" :max="10000" />
              </ElFormItem>
              <ElFormItem label="每批人工放行" label-width="150px">
                <ElSwitch v-model="policy.requireManualBatchApproval" />
              </ElFormItem>
            </ElCollapseItem>
          </ElCollapse>
        </ElFormItem>
      </ElForm>

      <ElAlert
        v-if="createError"
        type="error"
        :closable="false"
        show-icon
        data-testid="ota-campaign-create-error"
      >
        {{ createError }}
      </ElAlert>
      <template #footer>
        <ElButton @click="createVisible = false">取消</ElButton>
        <ElButton type="primary" :loading="submitting" @click="submitCreate">创建草稿</ElButton>
      </template>
    </ElDialog>

    <ElDrawer
      v-model="detailVisible"
      :title="`活动 ${detail?.id ?? ''}`"
      size="960px"
      destroy-on-close
    >
      <ElDescriptions :column="2" border>
        <ElDescriptionsItem label="状态">
          <ElTag data-testid="ota-campaign-status" :type="campaignStatusTag(detail?.status)">{{
            campaignStatusLabel(detail?.status)
          }}</ElTag>
          <span class="ota-campaigns__hint">{{ actionsOf(detail).reason }}</span>
        </ElDescriptionsItem>
        <ElDescriptionsItem label="修订">{{ detail?.stateVersion || '—' }}</ElDescriptionsItem>
        <ElDescriptionsItem label="固件">
          <span class="ota-campaigns__mono">{{ detail?.firmwareId || '—' }}</span>
        </ElDescriptionsItem>
        <ElDescriptionsItem label="目标/批次">
          {{ detail?.targetCount ?? 0 }} / {{ detail?.batchCount ?? 0 }}
        </ElDescriptionsItem>
        <ElDescriptionsItem label="计划摘要">
          <span class="ota-campaigns__mono">{{ detail?.planSha256 || '—' }}</span>
        </ElDescriptionsItem>
        <ElDescriptionsItem label="排程时间">{{
          formatTime(detail?.scheduledAt)
        }}</ElDescriptionsItem>
      </ElDescriptions>

      <div class="ota-campaigns__actions console-actions">
        <ElButton
          v-if="hasAuth('ota:deploy') && actionsOf(detail).canSchedule"
          type="primary"
          :loading="submitting"
          @click="runAction('schedule')"
        >
          冻结目标并排程
        </ElButton>
        <ElButton
          v-if="hasAuth('ota:deploy') && actionsOf(detail).canStart"
          type="primary"
          :loading="submitting"
          @click="runAction('start')"
        >
          启动第一批
        </ElButton>
        <ElButton
          v-if="hasAuth('ota:deploy') && actionsOf(detail).canPause"
          :loading="submitting"
          @click="runAction('pause')"
        >
          暂停
        </ElButton>
        <ElButton
          v-if="hasAuth('ota:deploy') && actionsOf(detail).canResume"
          type="primary"
          :loading="submitting"
          @click="runAction('resume')"
        >
          恢复
        </ElButton>
        <ElButton
          v-if="hasAuth('ota:deploy') && actionsOf(detail).canAdvance"
          type="primary"
          :loading="submitting"
          @click="runAction('advance')"
        >
          放行下一批
        </ElButton>
        <ElButton
          v-if="hasAuth('ota:deploy') && actionsOf(detail).canCancel"
          type="danger"
          :loading="submitting"
          @click="runAction('cancel')"
        >
          取消活动
        </ElButton>
      </div>

      <ElAlert
        v-if="actionError"
        type="error"
        :closable="false"
        show-icon
        data-testid="ota-campaign-action-error"
        class="ota-campaigns__notice"
      >
        {{ actionError }}
      </ElAlert>

      <template v-if="execution">
        <ElDivider content-position="left">执行事实</ElDivider>
        <ElDescriptions :column="3" border>
          <ElDescriptionsItem label="等待准入">{{ execution.pendingCount }}</ElDescriptionsItem>
          <ElDescriptionsItem label="已派发">{{ execution.dispatchedCount }}</ElDescriptionsItem>
          <ElDescriptionsItem label="已耗尽">{{
            execution.batchProgress?.timedOutCount ?? '—'
          }}</ElDescriptionsItem>
          <ElDescriptionsItem label="已跳过">{{ execution.skippedCount }}</ElDescriptionsItem>
          <ElDescriptionsItem label="当前批次">
            {{ execution.batchProgress?.currentBatchStatus ?? '—' }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="等待人工放行">
            {{ execution.batchProgress?.awaitingManualApproval ? '是' : '否' }}
          </ElDescriptionsItem>
          <ElDescriptionsItem label="暂停类型">{{ execution.pauseKind || '—' }}</ElDescriptionsItem>
        </ElDescriptions>
        <ElAlert v-if="execution.pauseReason" type="warning" :closable="false" show-icon>
          {{ execution.pauseKind || 'PAUSED' }}：{{ execution.pauseReason }}
        </ElAlert>
      </template>

      <ElDivider content-position="left">批次</ElDivider>
      <ElTable v-loading="batchesLoading" :data="batches" size="small" row-key="batchNumber">
        <ElTableColumn show-overflow-tooltip prop="batchNumber" label="批次" width="70" />
        <ElTableColumn label="状态" width="100">
          <template #default="{ row }">
            <ElTag :type="batchStatusTag(row.status)">{{ batchStatusLabel(row.status) }}</ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn
          show-overflow-tooltip
          prop="targetCount"
          label="目标"
          width="70"
          align="right"
        />
        <ElTableColumn
          show-overflow-tooltip
          prop="succeededCount"
          label="成功"
          width="70"
          align="right"
        />
        <ElTableColumn
          show-overflow-tooltip
          prop="rolledBackCount"
          label="回退"
          width="70"
          align="right"
        />
        <ElTableColumn
          show-overflow-tooltip
          prop="skippedCount"
          label="跳过"
          width="70"
          align="right"
        />
        <ElTableColumn
          show-overflow-tooltip
          prop="timedOutCount"
          label="耗尽"
          width="70"
          align="right"
        />
        <ElTableColumn
          show-overflow-tooltip
          prop="cancelledCount"
          label="取消"
          width="70"
          align="right"
        />
        <ElTableColumn label="完成时间" width="180">
          <template #default="{ row }">{{ formatTime(row.completedAt) }}</template>
        </ElTableColumn>
        <ElTableColumn label="结果" width="110">
          <template #default="{ row }">{{ row.outcome || '—' }}</template>
        </ElTableColumn>
        <template #empty>
          <ElEmpty description="还没有冻结批次（草稿活动在排程时生成）" />
        </template>
      </ElTable>

      <ElDivider content-position="left">目标作业与当前资格</ElDivider>
      <ElTable :data="detail?.jobs ?? []" size="small" row-key="id" max-height="320">
        <ElTableColumn show-overflow-tooltip prop="batchNumber" label="批次" width="70" />
        <ElTableColumn label="设备" min-width="280">
          <template #default="{ row }">
            <span class="ota-campaigns__mono">{{ row.deviceId }}</span>
          </template>
        </ElTableColumn>
        <ElTableColumn show-overflow-tooltip prop="status" label="作业状态" width="150" />
        <ElTableColumn label="资格" min-width="180">
          <template #default="{ row }">
            <span v-if="eligibility[row.deviceId]">
              {{ eligibility[row.deviceId]?.eligible ? '合格' : '不合格' }} ·
              {{ eligibility[row.deviceId]?.reason }}
            </span>
            <span v-else class="ota-campaigns__hint">未查询</span>
          </template>
        </ElTableColumn>
        <ElTableColumn class-name="console-table-actions-cell" label="操作" width="64">
          <template #default="{ row }">
            <ConsoleTableAction
              type="primary"
              @click="checkEligibility(row.deviceId)"
              label="查资格"
              icon="ri:shield-check-line"
            />
          </template>
        </ElTableColumn>
      </ElTable>
    </ElDrawer>
  </div>
</template>

<script setup lang="ts">
  import ConsoleWorkspaceHeader from '@/components/business/ConsoleWorkspaceHeader.vue'
  import ConsoleTableAction from '@/components/ConsoleTableAction.vue'

  import { formatTime } from '@/utils/time'
  import { Plus } from '@element-plus/icons-vue'
  import { fetchSearchDevices, type DeviceResponse } from '@/api/device'
  import {
    advanceOtaCampaignBatch,
    cancelOtaCampaign,
    createOtaCampaign,
    fetchOtaCampaign,
    fetchOtaCampaignBatches,
    fetchOtaCampaignExecution,
    fetchOtaCampaigns,
    fetchOtaDeviceEligibility,
    fetchOtaFirmwares,
    pauseOtaCampaign,
    resumeOtaCampaign,
    scheduleOtaCampaign,
    startOtaCampaign,
    type OtaCampaignBatchResponse,
    type OtaCampaignExecutionResponse,
    type OtaCampaignResponse,
    type OtaCampaignSummaryResponse,
    type OtaDeviceEligibilityResponse,
    type OtaFirmwareResponse
  } from '@/api/ota'
  import {
    batchStatusLabel,
    batchStatusTag,
    campaignActions,
    campaignErrorHint,
    campaignStatusLabel,
    campaignStatusTag,
    checkCampaignDraft,
    defaultCampaignPolicy,
    toNotBeforeUtc
  } from '@/features/ota/campaign-model'
  import { firmwareStatusLabel } from '@/features/ota/firmware-model'
  import { useAuth } from '@/hooks/core/useAuth'
  import { useUserStore } from '@/store/modules/user'
  import { HttpError } from '@/utils/http/error'
  import { IdempotentSubmission } from '@/utils/idempotent-submission'

  defineOptions({ name: 'OtaCampaigns' })

  const userStore = useUserStore()
  const { hasAuth } = useAuth()
  const projectId = computed(() => userStore.info.currentProjectId ?? '')

  const loading = ref(false)
  const submitting = ref(false)
  const campaigns = ref<OtaCampaignSummaryResponse[]>([])
  const cursor = ref('')

  const createVisible = ref(false)
  const createError = ref('')
  const firmwares = ref<OtaFirmwareResponse[]>([])
  const devices = ref<DeviceResponse[]>([])
  const notBeforeLocal = ref<Date>()
  const policy = reactive(defaultCampaignPolicy())
  const draft = reactive({ firmwareId: '', deviceIds: [] as string[], batchSize: 10 })

  const detailVisible = ref(false)
  const detail = ref<OtaCampaignResponse>()
  const execution = ref<OtaCampaignExecutionResponse>()
  const batches = ref<OtaCampaignBatchResponse[]>([])
  const batchesLoading = ref(false)
  const actionError = ref('')
  const eligibility = reactive<Record<string, OtaDeviceEligibilityResponse>>({})

  const createKeys = new IdempotentSubmission()
  const actionKeys = new IdempotentSubmission()

  /** 活动动作资格；详情抽屉与提示共用同一判定。 */
  const actionsOf = (row?: Partial<OtaCampaignSummaryResponse>) =>
    campaignActions(row?.status, execution.value?.batchProgress?.awaitingManualApproval === true)

  function report(error: unknown, fallback: string): string {
    if (error instanceof HttpError) {
      const hint = campaignErrorHint(error.code)
      return hint === '' ? error.message : `${error.message}（${error.code}）${hint}`
    }
    console.error(error)
    return fallback
  }

  async function load(next?: string) {
    if (projectId.value === '') return
    loading.value = true
    try {
      const page = await fetchOtaCampaigns(projectId.value, next)
      campaigns.value = next ? [...campaigns.value, ...(page.items ?? [])] : (page.items ?? [])
      cursor.value = page.hasMore ? (page.nextCursor ?? '') : ''
    } catch (error) {
      ElMessage.error(report(error, '读取活动列表失败。'))
    } finally {
      loading.value = false
    }
  }

  async function openCreate() {
    createError.value = ''
    // 预填"5分钟后"作为保守默认：活动只能在notBefore之后启动，空白只会让操作者猜格式。
    if (!notBeforeLocal.value) notBeforeLocal.value = new Date(Date.now() + 5 * 60 * 1000)
    createVisible.value = true
    if (firmwares.value.length === 0) {
      try {
        const page = await fetchOtaFirmwares(projectId.value, undefined, 100)
        firmwares.value = page.items ?? []
      } catch (error) {
        createError.value = report(error, '读取固件列表失败。')
      }
    }
    if (devices.value.length === 0) {
      try {
        const page = await fetchSearchDevices(projectId.value, { limit: 200 })
        devices.value = page.items ?? []
      } catch (error) {
        createError.value = report(error, '读取设备列表失败。')
      }
    }
  }

  async function submitCreate() {
    if (!notBeforeLocal.value) {
      createError.value = '请选择最早启动时间。'
      return
    }
    const body = {
      contractVersion: 'tc-ota-campaign-plan/v1' as const,
      firmwareId: draft.firmwareId,
      deviceIds: draft.deviceIds,
      batchSize: draft.batchSize,
      notBefore: toNotBeforeUtc(notBeforeLocal.value),
      executionPolicy: { ...policy, stageTimeoutSeconds: { ...policy.stageTimeoutSeconds } }
    }
    const check = checkCampaignDraft({
      firmwareId: body.firmwareId,
      deviceIds: body.deviceIds,
      batchSize: body.batchSize,
      notBefore: body.notBefore
    })
    if (!check.ok) {
      createError.value = check.reason ?? '活动计划不符合要求。'
      return
    }
    const fingerprint = JSON.stringify(body)
    const key = createKeys.keyFor(fingerprint)
    submitting.value = true
    createError.value = ''
    try {
      const created = await createOtaCampaign(projectId.value, body, key)
      createKeys.succeeded(key)
      ElMessage.success('活动草稿已创建。')
      createVisible.value = false
      await load()
      if (created.id) await openDetail({ id: created.id })
    } catch (error) {
      createKeys.failed(key, !(error instanceof HttpError))
      createError.value = report(error, '创建活动结果未知，请刷新列表核对。')
    } finally {
      submitting.value = false
    }
  }

  async function openDetail(row: Partial<OtaCampaignSummaryResponse>) {
    if (!row.id) return
    detail.value = undefined
    execution.value = undefined
    batches.value = []
    actionError.value = ''
    detailVisible.value = true
    batchesLoading.value = true
    try {
      detail.value = await fetchOtaCampaign(projectId.value, row.id)
      execution.value = await fetchOtaCampaignExecution(projectId.value, row.id)
      batches.value = await fetchOtaCampaignBatches(projectId.value, row.id)
    } catch (error) {
      actionError.value = report(error, '读取活动事实失败。')
    } finally {
      batchesLoading.value = false
    }
  }

  async function refreshDetail() {
    if (!detail.value?.id) return
    detail.value = await fetchOtaCampaign(projectId.value, detail.value.id)
    execution.value = await fetchOtaCampaignExecution(projectId.value, detail.value.id)
    batches.value = await fetchOtaCampaignBatches(projectId.value, detail.value.id)
  }

  async function runAction(kind: 'schedule' | 'start' | 'pause' | 'resume' | 'cancel' | 'advance') {
    const current = detail.value
    if (!current?.id || !current.stateVersion) return
    const reason = kind === 'schedule' || kind === 'start' ? '' : await promptReason(kind)
    if (reason === undefined) return
    const key = actionKeys.keyFor(JSON.stringify([kind, current.id, current.stateVersion, reason]))
    submitting.value = true
    actionError.value = ''
    try {
      if (kind === 'schedule') {
        await scheduleOtaCampaign(projectId.value, current.id, current.stateVersion, key)
      } else if (kind === 'start') {
        await startOtaCampaign(projectId.value, current.id, current.stateVersion, key)
      } else if (kind === 'pause') {
        await pauseOtaCampaign(projectId.value, current.id, current.stateVersion, reason, key)
      } else if (kind === 'resume') {
        await resumeOtaCampaign(projectId.value, current.id, current.stateVersion, reason, key)
      } else if (kind === 'cancel') {
        await cancelOtaCampaign(projectId.value, current.id, current.stateVersion, reason, key)
      } else {
        const batch = String(execution.value?.currentBatch ?? 1)
        await advanceOtaCampaignBatch(
          projectId.value,
          current.id,
          current.stateVersion,
          batch,
          reason,
          key
        )
      }
      actionKeys.succeeded(key)
      ElMessage.success('活动命令已受理。')
      await refreshDetail()
      await load()
    } catch (error) {
      actionKeys.failed(key, !(error instanceof HttpError))
      actionError.value = report(error, '活动命令结果未知，请刷新执行事实核对。')
    } finally {
      submitting.value = false
    }
  }

  async function promptReason(kind: string): Promise<string | undefined> {
    const labels: Record<string, string> = {
      pause: '暂停',
      resume: '恢复',
      cancel: '取消',
      advance: '放行下一批'
    }
    try {
      const result = await ElMessageBox.prompt(
        `请输入${labels[kind] ?? kind}活动的原因（会写入运行事实与审计）。`,
        `${labels[kind] ?? kind}活动`,
        {
          inputPattern: /\S+/,
          inputErrorMessage: '原因不能为空',
          confirmButtonText: labels[kind] ?? '确定'
        }
      )
      return result.value
    } catch {
      return undefined
    }
  }

  /** 资格快照只按当前固件读取一次，不缓存为可复用授权。 */
  async function checkEligibility(deviceId: string) {
    const firmwareId = detail.value?.firmwareId
    if (!firmwareId) return
    try {
      eligibility[deviceId] = await fetchOtaDeviceEligibility(projectId.value, deviceId, firmwareId)
    } catch (error) {
      actionError.value = report(error, '读取设备资格失败。')
    }
  }

  onMounted(() => void load())
</script>

<style lang="scss" scoped>
  .ota-campaigns {
    :deep(.workspace-header > .console-actions) {
      margin-bottom: 0;
    }

    &__notice {
      margin-bottom: 10px;

      :deep(.el-alert__title) {
        font-size: 12px;
        font-weight: normal;
        line-height: 20px;
      }

      :deep(.el-alert__icon) {
        width: 14px;
        height: 14px;
        font-size: 14px;
      }
    }

    &__more {
      display: flex;
      justify-content: center;
      margin-top: 10px;
    }

    &__field {
      width: 100%;
    }

    &__policy {
      width: 100%;

      :deep(.el-form-item__content) {
        gap: 8px;
        align-items: center;
      }
    }

    &__policy-row > :deep(.el-form-item__label) {
      height: 50px !important;
      line-height: 50px !important;
    }

    &__actions {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
      margin: 10px 0;
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
  }
</style>
