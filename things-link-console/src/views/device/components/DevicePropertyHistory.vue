<template>
  <section
    class="device-detail-list"
    aria-label="原始属性历史"
    data-testid="device-property-history"
  >
    <ElAlert type="info" :closable="false" show-icon
      >按上报时的类型、物模型版本和采集时刻展示原始属性值，数值曲线仍可在设备属性页查看。</ElAlert
    >
    <ElForm inline class="device-history-filter" @submit.prevent="model.refresh()">
      <ElFormItem label="属性键"
        ><ElInput v-model="model.filters.propertyKey" aria-label="历史属性键"
      /></ElFormItem>
      <ElFormItem label="从（包含）"
        ><ElInput
          v-model="model.filters.from"
          aria-label="属性时间从"
          placeholder="RFC3339，含时区"
      /></ElFormItem>
      <ElFormItem label="至（不包含）"
        ><ElInput v-model="model.filters.to" aria-label="属性时间至" placeholder="RFC3339，含时区"
      /></ElFormItem>
      <ElButton
        :loading="model.loading"
        data-testid="device-property-history-refresh"
        @click="model.refresh()"
        >筛选 / 刷新原始历史</ElButton
      >
      <ElButton :disabled="model.loading" @click="reset">清除筛选</ElButton>
    </ElForm>
    <ElAlert
      v-if="model.error"
      data-testid="device-property-history-error"
      :title="model.error"
      type="error"
      :closable="false"
      show-icon
    />
    <ElTable
      v-loading="model.loading"
      :data="model.items"
      data-testid="device-property-history-table"
    >
      <ElTableColumn prop="propertyKey" label="属性键" min-width="140" />
      <ElTableColumn prop="dataType" label="原始类型" width="110" />
      <ElTableColumn label="原始值" min-width="260"
        ><template #default="{ row }"><pre v-text="row.valueText" /></template
      ></ElTableColumn>
      <ElTableColumn prop="ts" label="采集时刻 UTC" min-width="240" />
      <ElTableColumn prop="modelVersion" label="原模型版本" min-width="160" />
      <ElTableColumn label="原模型版本 ID" min-width="250"
        ><template #default="{ row }">{{
          row.thingModelVersionId ?? '未绑定（历史记录）'
        }}</template></ElTableColumn
      >
      <ElTableColumn prop="quality" label="质量标记" width="100" />
      <template #empty
        ><ElEmpty
          :description="
            model.error
              ? '原始属性历史不可用'
              : model.loading
                ? '正在读取原始属性历史'
                : '当前筛选与套餐可读窗口内暂无数据'
          "
      /></template>
    </ElTable>
    <DeviceDetailPagination
      v-if="model.items.length > 0"
      :page-index="model.pageIndex"
      :loading="model.loading"
      :failed="!!model.error"
      :has-next="!!model.nextCursor"
      aria-label="原始属性历史分页"
      next-test-id="device-property-history-next"
      @previous="model.previous()"
      @next="model.next()"
    />
  </section>
</template>
<script setup lang="ts">
  import DeviceDetailPagination from './DeviceDetailPagination.vue'
  import { onBeforeUnmount, reactive, watch } from 'vue'
  import { fetchDevicePropertyHistory } from '@/api/device-property-history'
  import {
    PropertyHistoryModel,
    emptyPropertyFilters
  } from '@/features/device/property-history-model'
  import { currentIdentityEpoch } from '@/utils/http/identity-scope'
  const props = defineProps<{ projectId: string; deviceId: string; active?: boolean }>()
  const model = reactive(new PropertyHistoryModel({ list: fetchDevicePropertyHistory }))
  function reset() {
    model.filters = emptyPropertyFilters()
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
    margin: 0;
    overflow-wrap: anywhere;
    white-space: pre-wrap;
  }
</style>
