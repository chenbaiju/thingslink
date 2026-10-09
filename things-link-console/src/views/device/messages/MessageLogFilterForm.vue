<template>
  <ConsoleFilterBar
    class="message-log-filter"
    :items="filterItems"
    :span="8"
    @search="emit('search')"
    @reset="emit('reset')"
  >
    <template #deviceId>
      <ElSelect
        v-model="model.deviceId"
        class="form-control"
        filterable
        remote
        :remote-method="(keyword: string) => emit('device-search', keyword)"
        :loading="devicesLoading"
        placeholder="输入名称或标识搜索设备"
        @popup-scroll="(event: Event) => emit('device-scroll', event)"
      >
        <ElOption
          v-for="device in devices"
          :key="device.id"
          :label="`${device.name}（${device.deviceKey}）`"
          :value="device.id"
        />
      </ElSelect>
    </template>
    <template #direction>
      <ElSelect v-model="model.direction" clearable placeholder="全部方向">
        <ElOption label="上行" value="UP" />
        <ElOption label="下行" value="DOWN" />
      </ElSelect>
    </template>
    <template #timeRange>
      <ElDatePicker
        v-model="model.timeRange"
        type="datetimerange"
        range-separator="至"
        start-placeholder="开始时间"
        end-placeholder="结束时间"
        :clearable="false"
      />
    </template>
    <template #messageType>
      <ElSelect v-model="model.messageType" clearable placeholder="全部类型">
        <ElOption label="属性上报" value="PROPERTY_REPORT" />
        <ElOption label="命令下发" value="COMMAND" />
        <ElOption label="命令回复" value="COMMAND_REPLY" />
      </ElSelect>
    </template>
    <template #traceId>
      <ElInput v-model.trim="model.traceId" maxlength="64" clearable placeholder="精确匹配" />
    </template>
  </ConsoleFilterBar>
</template>

<script setup lang="ts">
  import ConsoleFilterBar from '@/components/ConsoleFilterBar.vue'
  import type { SearchFormItem } from '@/components/core/forms/art-search-bar/index.vue'

  const filterItems: SearchFormItem[] = [
    { key: 'deviceId', label: '设备' },
    { key: 'direction', label: '消息方向' },
    { key: 'messageType', label: '消息类型' },
    { key: 'traceId', label: 'Trace ID' },
    { key: 'timeRange', label: '发生时间', span: 24 }
  ].map((item) => ({ ...item, labelWidth: 90 }))
  /** 已归一化的消息日志筛选状态；请求 DTO 仍由生成的 OpenAPI 类型约束。 */
  export interface MessageLogFilterModel {
    deviceId: string
    direction: '' | 'UP' | 'DOWN'
    messageType: '' | 'PROPERTY_REPORT' | 'COMMAND' | 'COMMAND_REPLY'
    timeRange: [Date, Date]
    traceId: string
  }

  /** 下拉中可展示的最小设备字段，避免筛选组件耦合接口响应的完整模型。 */
  interface DeviceOption {
    id: string
    name: string
    deviceKey: string
  }

  defineProps<{
    devices: DeviceOption[]
    devicesLoading?: boolean
  }>()

  const model = defineModel<MessageLogFilterModel>({ required: true })
  const emit = defineEmits<{
    search: []
    reset: []
    'device-search': [keyword: string]
    'device-scroll': [event: Event]
  }>()
</script>

<style lang="scss">
  .message-log-filter.console-filter-bar .el-form-item {
    .el-form-item__label {
      justify-content: flex-end;
    }

    .el-date-editor.el-range-editor {
      flex-grow: 0;
      width: 400px !important;
      max-width: 100%;
    }
  }
</style>
