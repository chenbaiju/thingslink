<template>
  <section aria-label="事件历史" data-testid="device-event-history">
    <p>设备发生记录，只读展示原模型与发生时刻。</p>
    <ElForm inline @submit.prevent="model.refresh()">
      <ElFormItem label="事件键"
        ><ElInput v-model="model.filters.eventKey" aria-label="事件键" maxlength="64"
      /></ElFormItem>
      <ElFormItem label="级别"
        ><ElSelect v-model="model.filters.level" aria-label="事件级别" clearable
          ><ElOption
            v-for="level in ['INFO', 'WARNING', 'ERROR']"
            :key="level"
            :value="level"
            :label="level" /></ElSelect
      ></ElFormItem>
      <ElFormItem label="原模型版本 ID"
        ><ElInput v-model="model.filters.thingModelVersionId" aria-label="原模型版本 ID"
      /></ElFormItem>
      <ElFormItem label="从（包含）"
        ><ElInput
          v-model="model.filters.from"
          aria-label="事件时间从"
          placeholder="RFC3339，例如 2026-10-06T00:00:00Z"
      /></ElFormItem>
      <ElFormItem label="至（排他）"
        ><ElInput v-model="model.filters.to" aria-label="事件时间至" placeholder="RFC3339，含时区"
      /></ElFormItem>
      <ElButton
        data-testid="device-event-history-refresh"
        :loading="model.loading"
        @click="model.refresh()"
        >筛选 / 刷新事件历史</ElButton
      >
      <ElButton :disabled="model.loading" @click="reset">清除筛选</ElButton>
    </ElForm>
    <p v-if="model.windowFrom" data-testid="device-event-history-window"
      >当前有效窗口 {{ model.windowFrom }} 至 {{ model.windowTo }}（不包含）；存储保留 90
      天，当前套餐可读窗口可能更短。</p
    >
    <p>套餐决定当前可读时间范围。</p>
    <ElAlert
      v-if="model.error"
      data-testid="device-event-history-error"
      :title="model.error"
      type="error"
      :closable="false"
    />
    <ElTable
      v-loading="model.loading"
      :data="model.items"
      row-key="messageId"
      data-testid="device-event-history-table"
    >
      <ElTableColumn prop="eventKey" label="原事件键" min-width="140" />
      <ElTableColumn prop="level" label="原级别" width="110" />
      <ElTableColumn prop="modelVersion" label="原模型版本" min-width="130" />
      <ElTableColumn prop="thingModelVersionId" label="原模型版本 ID" min-width="250" />
      <ElTableColumn prop="occurredAt" label="发生时刻 UTC" min-width="240" />
      <ElTableColumn prop="receivedAt" label="接收时刻 UTC" min-width="240" />
      <ElTableColumn prop="eligibility" label="首次受理资格" min-width="160" />
      <ElTableColumn label="详情" width="110"
        ><template #default="{ row }"
          ><ElButton data-testid="device-event-detail-open" @click="model.open(row.messageId)"
            >查看详情</ElButton
          ></template
        ></ElTableColumn
      >
      <template #empty
        ><ElEmpty
          :description="
            model.error
              ? '事件历史不可用'
              : model.loading
                ? '正在读取事件历史'
                : filtered
                  ? '筛选范围内没有可读事件'
                  : '当前可读窗口内暂无事件'
          "
      /></template>
    </ElTable>
    <ElButton
      data-testid="device-event-history-next"
      :disabled="model.loading || !!model.error || !model.nextCursor"
      @click="model.next()"
      >下一页</ElButton
    >
    <section
      v-if="model.detailLoading || model.detail || model.detailError"
      aria-label="事件详情"
      data-testid="device-event-detail"
    >
      <ElButton @click="model.clearDetail()">关闭事件详情</ElButton>
      <p v-if="model.detailLoading">正在读取事件详情</p>
      <ElAlert v-if="model.detailError" :title="model.detailError" type="error" :closable="false" />
      <template v-if="model.detail">
        <dl
          ><dt>消息 ID</dt><dd>{{ model.detail.messageId }}</dd
          ><dt>原事件键 / 原级别</dt><dd>{{ model.detail.eventKey }} / {{ model.detail.level }}</dd
          ><dt>原模型版本</dt
          ><dd>{{ model.detail.modelVersion }}（{{ model.detail.thingModelVersionId }}）</dd
          ><dt>发生 / 接收 / 受理时刻 UTC</dt
          ><dd
            >{{ model.detail.occurredAt }} / {{ model.detail.receivedAt }} /
            {{ model.detail.acceptedAt }}</dd
          ><dt>首次受理资格</dt><dd>{{ model.detail.eligibility }}</dd></dl
        >
        <p data-testid="device-event-detail-redaction">{{
          model.detail.paramsRedacted
            ? '参数已按存储规则脱敏；此处只展示只读脱敏投影。'
            : '此记录未发生参数脱敏；此处只展示只读参数投影。'
        }}</p>
        <pre data-testid="device-event-detail-params" v-text="model.detail.paramsText" />
      </template>
    </section>
  </section>
</template>
<script setup lang="ts">
  import { computed, onBeforeUnmount, reactive, watch } from 'vue'
  import { fetchDeviceEvent, fetchDeviceEvents } from '@/api/device-event-history'
  import { EventHistoryModel, emptyEventFilters } from '@/features/device/event-history-model'
  import { currentIdentityEpoch } from '@/utils/http/identity-scope'
  const props = defineProps<{ projectId: string; deviceId: string; active?: boolean }>()
  const model = reactive(
    new EventHistoryModel({ list: fetchDeviceEvents, detail: fetchDeviceEvent })
  )
  const filtered = computed(() => Object.values(model.filters).some(Boolean))
  function reset() {
    model.filters = emptyEventFilters()
    void model.refresh()
  }
  watch(
    () =>
      [props.projectId, props.deviceId, props.active !== false, currentIdentityEpoch()] as const,
    ([project, device, active]) => model.scope(project, device, active),
    { immediate: true, flush: 'sync' }
  )
  onBeforeUnmount(() => model.close())
</script>
<style scoped>
  pre {
    overflow-wrap: anywhere;
    white-space: pre-wrap;
  }
  dd {
    margin-bottom: 0.5rem;
    overflow-wrap: anywhere;
  }
</style>
