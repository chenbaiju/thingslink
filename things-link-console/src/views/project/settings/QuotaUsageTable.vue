<!--
  配额表复用于“当前项目贡献”和“租户共享池”。

  两者共用同一列定义，避免复制页面后出现不同的单位、空值或超额状态展示口径。
-->
<template>
  <ElTable :data="rows" size="default">
    <ElTableColumn label="指标" min-width="150">
      <template #default="{ row }">{{ metricLabels[row.metric] }}</template>
    </ElTableColumn>
    <ElTableColumn label="已用" min-width="110" align="right">
      <template #default="{ row }">{{ formatNumber(row.used) }}</template>
    </ElTableColumn>
    <ElTableColumn label="上限" min-width="110" align="right">
      <template #default="{ row }">{{ formatLimit(row.limit) }}</template>
    </ElTableColumn>
    <ElTableColumn label="剩余" min-width="110" align="right">
      <template #default="{ row }">{{ formatLimit(row.remaining) }}</template>
    </ElTableColumn>
    <ElTableColumn label="状态" min-width="100" align="center">
      <template #default="{ row }">
        <ElTag :type="statusType(row.status)" disable-transitions>{{
          statusText(row.status, row.metric, row.limit)
        }}</ElTag>
      </template>
    </ElTableColumn>
    <template #empty><ElEmpty description="暂无配额指标" :image-size="72" /></template>
  </ElTable>
</template>

<script setup lang="ts">
  import type { ProjectQuotaScopeResponse } from '@/api/quota'

  const props = defineProps<{
    scope?: ProjectQuotaScopeResponse
  }>()

  const metricLabels: Record<string, string> = {
    DEVICE_COUNT: '设备数',
    UPLINK_MESSAGE: '上行消息量',
    DOWNLINK_MESSAGE: '下行消息量',
    UPLINK_BYTES: '上行流量',
    TIME_SERIES_POINT: '时序数据点',
    REST_API_CALL: 'REST API 调用',
    WEBSOCKET_CONNECTION: 'WebSocket 连接',
    NOTIFICATION_DELIVERY: '通知投递',
    SCRIPT_EXECUTION: '脚本执行次数',
    AUTOMATION_EXECUTION: '自动化执行次数',
    SCRIPT_CPU_MILLIS: '脚本 CPU 耗时',
    STORAGE_BYTES: '对象存储占用'
  }

  /** 设备存量不属于日计数表，但它和 UTC 日指标共享同一套餐上限与状态展示。 */
  const rows = computed(() => {
    const dailyMetrics = props.scope?.dailyMetrics ?? []
    return props.scope?.deviceCount ? [props.scope.deviceCount, ...dailyMetrics] : dailyMetrics
  })

  /** 缺失不是零；用破折号保留“不确定”的语义。 */
  const formatNumber = (value?: number | null) =>
    typeof value === 'number' ? new Intl.NumberFormat('zh-CN').format(value) : '—'

  /** null 或缺失上限代表套餐没有为该指标设置硬上限。 */
  const formatLimit = (value?: number | null) =>
    typeof value === 'number' ? formatNumber(value) : '不限'

  const statusText = (status?: string, metric?: string, limit?: number | null) =>
    metric === 'AUTOMATION_EXECUTION' && limit === 0
      ? '未开通'
      : ({ NORMAL: '正常', SOFT_LIMIT: '软限', HARD_LIMIT: '硬限', DEGRADED: '降级' }[
          status ?? ''
        ] ?? '—')

  const statusType = (status?: string) =>
    ({ NORMAL: 'success', SOFT_LIMIT: 'warning', HARD_LIMIT: 'danger', DEGRADED: 'info' })[
      status ?? ''
    ] as 'success' | 'warning' | 'danger' | 'info' | undefined
</script>
