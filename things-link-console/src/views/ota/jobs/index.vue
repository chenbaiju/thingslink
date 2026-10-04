<!--
  OTA设备作业页。

  作业是活动冻结目标的执行事实：本页按活动筛选、按作业ID倒序分页读取，并在详情抽屉里显示
  代次、失败归因与**真实转移时间线**。时间线来自`ota_job_transition`，控制台不解释终态、
  也不推测缺失的转移。

  本页只读：作业的重试、取消与安全停止由平台按冻结策略和设备事实驱动，控制台不提供"手工重试"，
  以免把状态机外的人工操作伪装成受控流程。
-->
<template>
  <div class="console-page ota-jobs console-page--single-panel">
    <ElCard class="console-list-filter" shadow="never">
      <ConsoleFilterBar
        :items="[{ key: 'field0', label: '所属活动' }]"
        :show-expand="false"
        :show-reset="false"
        :show-search="false"
      >
        <template #field0
          ><ElSelect
            v-model="campaignId"
            filterable
            clearable
            placeholder="选择活动"
            class="ota-jobs__filter"
            @change="load()"
          >
            <ElOption
              v-for="item in campaigns"
              :key="item.id"
              :label="`${item.id?.slice(0, 8)}… · ${campaignStatusLabel(item.status)}`"
              :value="item.id ?? ''"
            /> </ElSelect
        ></template>
      </ConsoleFilterBar>
    </ElCard>

    <ElAlert class="ota-jobs__notice" type="info" :closable="false" show-icon>
      <template #title>作业状态以服务端为准</template>
      重试等待、对账恢复与超时都由冻结策略和设备事实上报驱动；控制台只呈现事实，不代设备推进状态。
    </ElAlert>

    <ElCard class="console-page__main-panel" shadow="never">
      <ElTable v-loading="loading" :data="jobs" row-key="id">
        <ElTableColumn show-overflow-tooltip prop="batchNumber" label="批次" width="70" />
        <ElTableColumn label="设备" min-width="280" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="ota-jobs__mono">{{ row.deviceId }}</span>
          </template>
        </ElTableColumn>
        <ElTableColumn label="状态" width="120">
          <template #default="{ row }">
            <ElTag :type="jobStatusTag(row.status)">{{ jobStatusLabel(row.status) }}</ElTag>
          </template>
        </ElTableColumn>
        <ElTableColumn label="代次" width="110">
          <template #default="{ row }">{{ attemptLabel(row.attemptNo) }}</template>
        </ElTableColumn>
        <ElTableColumn label="失败归因" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">{{ jobFailureHint(row.failureCode) }}</template>
        </ElTableColumn>
        <ElTableColumn label="当前期限" width="180">
          <template #default="{ row }">{{ formatTime(row.deadlineAt) }}</template>
        </ElTableColumn>
        <ElTableColumn label="下次重试" width="180">
          <template #default="{ row }">{{ formatTime(row.nextAttemptAt) }}</template>
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
          <ElEmpty :description="campaignId === '' ? '请先选择活动' : '该活动还没有设备作业'" />
        </template>
      </ElTable>
      <div v-if="cursor" class="ota-jobs__more">
        <ElButton :loading="loading" @click="load(cursor)">加载更多</ElButton>
      </div>
    </ElCard>

    <ElDrawer v-model="detailVisible" title="作业详情" size="760px" destroy-on-close>
      <ElDescriptions :column="2" border>
        <ElDescriptionsItem label="状态">
          <ElTag :type="jobStatusTag(detail?.status)">{{ jobStatusLabel(detail?.status) }}</ElTag>
        </ElDescriptionsItem>
        <ElDescriptionsItem label="代次">{{ attemptLabel(detail?.attemptNo) }}</ElDescriptionsItem>
        <ElDescriptionsItem label="批次">{{ detail?.batchNumber ?? '—' }}</ElDescriptionsItem>
        <ElDescriptionsItem label="修订">{{ detail?.stateVersion || '—' }}</ElDescriptionsItem>
        <ElDescriptionsItem label="设备">
          <span class="ota-jobs__mono">{{ detail?.deviceId || '—' }}</span>
        </ElDescriptionsItem>
        <ElDescriptionsItem label="凭据代际">{{
          detail?.credentialVersion || '—'
        }}</ElDescriptionsItem>
        <ElDescriptionsItem label="失败归因">{{
          jobFailureHint(detail?.failureCode)
        }}</ElDescriptionsItem>
        <ElDescriptionsItem label="首次派发">{{
          formatTime(detail?.firstDispatchedAt)
        }}</ElDescriptionsItem>
        <ElDescriptionsItem label="本次派发">{{
          formatTime(detail?.dispatchedAt)
        }}</ElDescriptionsItem>
        <ElDescriptionsItem label="当前期限">{{
          formatTime(detail?.deadlineAt)
        }}</ElDescriptionsItem>
        <ElDescriptionsItem label="下次重试">{{
          formatTime(detail?.nextAttemptAt)
        }}</ElDescriptionsItem>
        <ElDescriptionsItem label="物模型版本">
          <span class="ota-jobs__mono">{{ detail?.thingModelVersionId || '—' }}</span>
        </ElDescriptionsItem>
      </ElDescriptions>

      <ElDivider content-position="left">状态转移（真实记录）</ElDivider>
      <ElTable :data="detail?.transitions ?? []" size="small" row-key="toRevision">
        <ElTableColumn label="转移" min-width="240">
          <template #default="{ row }">{{ transitionSummary(row) }}</template>
        </ElTableColumn>
        <ElTableColumn label="修订" width="130">
          <template #default="{ row }">{{ row.fromRevision }} → {{ row.toRevision }}</template>
        </ElTableColumn>
        <ElTableColumn show-overflow-tooltip prop="actorKind" label="发起方" width="110" />
        <ElTableColumn label="时间" width="180">
          <template #default="{ row }">{{ formatTime(row.occurredAt) }}</template>
        </ElTableColumn>
        <template #empty>
          <ElEmpty description="没有转移记录" />
        </template>
      </ElTable>
    </ElDrawer>
  </div>
</template>

<script setup lang="ts">
  import ConsoleFilterBar from '@/components/ConsoleFilterBar.vue'
  import ConsoleTableAction from '@/components/ConsoleTableAction.vue'

  import { formatTime } from '@/utils/time'

  import {
    fetchOtaCampaignJob,
    fetchOtaCampaignJobs,
    fetchOtaCampaigns,
    type OtaCampaignSummaryResponse,
    type OtaDeviceJobDetailResponse,
    type OtaDeviceJobSummaryResponse
  } from '@/api/ota'
  import { campaignStatusLabel } from '@/features/ota/campaign-model'
  import {
    attemptLabel,
    jobFailureHint,
    jobStatusLabel,
    jobStatusTag,
    transitionSummary
  } from '@/features/ota/job-model'
  import { useUserStore } from '@/store/modules/user'
  import { HttpError } from '@/utils/http/error'

  defineOptions({ name: 'OtaJobs' })

  const userStore = useUserStore()
  const projectId = computed(() => userStore.info.currentProjectId ?? '')

  const loading = ref(false)
  const campaigns = ref<OtaCampaignSummaryResponse[]>([])
  const campaignId = ref('')
  const jobs = ref<OtaDeviceJobSummaryResponse[]>([])
  const cursor = ref('')

  const detailVisible = ref(false)
  const detail = ref<OtaDeviceJobDetailResponse>()

  function report(error: unknown, fallback: string): string {
    if (error instanceof HttpError) return error.message
    console.error(error)
    return fallback
  }

  async function loadCampaigns() {
    if (projectId.value === '') return
    try {
      const page = await fetchOtaCampaigns(projectId.value, undefined, 100)
      campaigns.value = page.items ?? []
    } catch (error) {
      ElMessage.error(report(error, '读取活动列表失败。'))
    }
  }

  async function load(next?: string) {
    if (projectId.value === '' || campaignId.value === '') return
    loading.value = true
    try {
      const page = await fetchOtaCampaignJobs(projectId.value, campaignId.value, { cursor: next })
      jobs.value = next ? [...jobs.value, ...(page.items ?? [])] : (page.items ?? [])
      cursor.value = page.hasMore ? (page.nextCursor ?? '') : ''
    } catch (error) {
      ElMessage.error(report(error, '读取设备作业失败。'))
    } finally {
      loading.value = false
    }
  }

  async function openDetail(row: OtaDeviceJobSummaryResponse) {
    if (!row.id || campaignId.value === '') return
    detail.value = undefined
    detailVisible.value = true
    try {
      detail.value = await fetchOtaCampaignJob(projectId.value, campaignId.value, row.id)
    } catch (error) {
      ElMessage.error(report(error, '读取作业详情失败。'))
    }
  }

  onMounted(async () => {
    await loadCampaigns()
  })
</script>

<style lang="scss" scoped>
  .ota-jobs {
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

    &__filter {
      width: 240px;
    }

    &__notice {
      margin-bottom: 10px;
    }

    &__more {
      display: flex;
      justify-content: center;
      margin-top: 10px;
    }

    &__mono {
      font-family: var(--art-font-mono, monospace);
      font-size: 12px;
      word-break: break-all;
    }
  }
</style>
