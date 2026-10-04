<!--
  OTA审计时间线页。

  审计事实由服务端在每个业务事务内写入`sys_audit_log`，本页只读：固定按`ota.`前缀收窄，
  因此看不到其他模块的事实。动作编码是稳定英文常量，中文只在展示层翻译，未知编码原样透传——
  把新动作显示成某个既有含义会让排障看错事实。

  只读页面不提供任何"修改/删除审计"的入口：审计修正应写补偿动作，历史不可覆盖。
-->
<template>
  <div class="console-page ota-audits console-page--single-panel">
    <ElCard class="console-list-filter" shadow="never">
      <ConsoleFilterBar
        :items="[{ key: 'field0', label: '审计动作' }]"
        :show-expand="false"
        :show-reset="false"
        :show-search="false"
      >
        <template #field0
          ><ElSelect
            v-model="actionFilter"
            clearable
            placeholder="全部动作"
            class="ota-audits__filter"
            @change="load()"
          >
            <ElOption
              v-for="option in actionOptions"
              :key="option.value"
              :label="option.label"
              :value="option.value"
            /> </ElSelect
        ></template>
      </ConsoleFilterBar>
    </ElCard>

    <ElAlert class="ota-audits__notice" type="info" :closable="false" show-icon>
      <template #title>审计只读</template>
      审计记录在业务事务内写入且不可修改或删除；修正历史应追加补偿动作。本页不提供任何写入口。
    </ElAlert>

    <ElCard class="console-page__main-panel" shadow="never">
      <ElTable v-loading="loading" :data="records" row-key="id">
        <ElTableColumn label="时间" width="180">
          <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
        </ElTableColumn>
        <ElTableColumn label="动作" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">{{ otaActionLabel(row.action) }}</template>
        </ElTableColumn>
        <ElTableColumn label="目标" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">{{ otaTargetLabel(row.targetType) }}</template>
        </ElTableColumn>
        <ElTableColumn label="目标ID" min-width="290" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="ota-audits__mono">{{ row.targetId || '—' }}</span>
          </template>
        </ElTableColumn>
        <ElTableColumn label="操作者" min-width="290" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="ota-audits__mono">{{ row.actorAccountId || '系统' }}</span>
          </template>
        </ElTableColumn>
        <ElTableColumn label="明细" min-width="240" show-overflow-tooltip>
          <template #default="{ row }">{{ detailsSummary(row.details) }}</template>
        </ElTableColumn>
        <ElTableColumn class-name="console-table-actions-cell" label="链路" width="64">
          <template #default="{ row }">
            <ConsoleTableAction
              v-if="row.traceId"
              type="primary"
              @click="openDetail(row)"
              label="查看"
              icon="ri:eye-line"
            />
            <span v-else>—</span>
          </template>
        </ElTableColumn>
        <template #empty>
          <ElEmpty description="还没有OTA审计记录" />
        </template>
      </ElTable>
      <div v-if="cursor" class="ota-audits__more">
        <ElButton :loading="loading" @click="load(cursor)">加载更多</ElButton>
      </div>
    </ElCard>

    <ElDrawer v-model="detailVisible" title="审计明细" size="720px" destroy-on-close>
      <ElDescriptions :column="1" border>
        <ElDescriptionsItem label="动作">{{ otaActionLabel(detail?.action) }}</ElDescriptionsItem>
        <ElDescriptionsItem label="动作编码">
          <span class="ota-audits__mono">{{ detail?.action || '—' }}</span>
        </ElDescriptionsItem>
        <ElDescriptionsItem label="目标类型">{{
          otaTargetLabel(detail?.targetType)
        }}</ElDescriptionsItem>
        <ElDescriptionsItem label="目标ID">
          <span class="ota-audits__mono">{{ detail?.targetId || '—' }}</span>
        </ElDescriptionsItem>
        <ElDescriptionsItem label="操作者">
          <span class="ota-audits__mono">{{ detail?.actorAccountId || '系统' }}</span>
        </ElDescriptionsItem>
        <ElDescriptionsItem label="traceId">
          <span class="ota-audits__mono">{{ detail?.traceId || '—' }}</span>
        </ElDescriptionsItem>
        <ElDescriptionsItem label="时间">{{ formatTime(detail?.createdAt) }}</ElDescriptionsItem>
      </ElDescriptions>
      <ElDivider content-position="left">明细（原样投影）</ElDivider>
      <pre class="ota-audits__details">{{ detailJson }}</pre>
    </ElDrawer>
  </div>
</template>

<script setup lang="ts">
  import ConsoleFilterBar from '@/components/ConsoleFilterBar.vue'
  import ConsoleTableAction from '@/components/ConsoleTableAction.vue'

  import { formatTime } from '@/utils/time'

  import { fetchOtaAudits, type OtaAuditResponse } from '@/api/ota'
  import {
    auditActionOptions,
    detailsSummary,
    otaActionLabel,
    otaTargetLabel
  } from '@/features/ota/audit-model'
  import { useUserStore } from '@/store/modules/user'
  import { HttpError } from '@/utils/http/error'

  defineOptions({ name: 'OtaAudits' })

  const userStore = useUserStore()
  const projectId = computed(() => userStore.info.currentProjectId ?? '')

  const loading = ref(false)
  const records = ref<OtaAuditResponse[]>([])
  const cursor = ref('')
  const actionFilter = ref('')
  const actionOptions = auditActionOptions()

  const detailVisible = ref(false)
  const detail = ref<OtaAuditResponse>()
  const detailJson = computed(() => JSON.stringify(detail.value?.details ?? {}, null, 2))

  function report(error: unknown, fallback: string): string {
    if (error instanceof HttpError) return error.message
    console.error(error)
    return fallback
  }

  async function load(next?: string) {
    if (projectId.value === '') return
    loading.value = true
    try {
      const page = await fetchOtaAudits(projectId.value, {
        cursor: next,
        action: actionFilter.value
      })
      records.value = next ? [...records.value, ...(page.items ?? [])] : (page.items ?? [])
      cursor.value = page.hasMore ? (page.nextCursor ?? '') : ''
    } catch (error) {
      ElMessage.error(report(error, '读取OTA审计失败。'))
    } finally {
      loading.value = false
    }
  }

  function openDetail(row: OtaAuditResponse) {
    detail.value = row
    detailVisible.value = true
  }

  onMounted(() => void load())
</script>

<style lang="scss" scoped>
  .ota-audits {
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
      width: 200px;
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

    &__details {
      max-height: 420px;
      padding: 12px;
      overflow: auto;
      font-family: var(--art-font-mono, monospace);
      font-size: 12px;
      background: var(--art-main-bg-color);
      border-radius: 6px;
    }
  }
</style>
