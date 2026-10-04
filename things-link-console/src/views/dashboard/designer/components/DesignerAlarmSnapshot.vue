<template>
  <section aria-label="告警事实快照" :data-preview-alarm="componentId">
    <p>条件状态与确认状态独立；这里只展示事故事实，不代表个人已读。</p>
    <div v-if="alarm.result?.status === 'READY'" class="alarm-scroll">
      <table>
        <thead
          ><tr
            ><th>设备</th><th>告警类型</th><th>等级</th><th>条件</th><th>确认</th
            ><th>首次条件时间</th><th>激活时间</th><th v-if="alarm.showClearedAt">恢复时间</th
            ><th>确认时间</th><th>最后接收时间</th></tr
          ></thead
        >
        <tbody
          ><tr v-for="item in alarm.result.items" :key="item.id" :data-alarm-id="item.id"
            ><td>{{ item.deviceId }}</td
            ><td>{{ item.alarmType }}</td
            ><td>{{ item.severity }}</td
            ><td>{{ item.conditionState }}</td
            ><td>{{ item.ackState }}</td
            ><td>{{ item.firstConditionAt }}</td
            ><td>{{ item.activatedAt ?? '未提供' }}</td
            ><td v-if="alarm.showClearedAt">{{ item.clearedAt ?? '未提供' }}</td
            ><td>{{ item.acknowledgedAt ?? '未提供' }}</td
            ><td>{{ item.lastReceivedAt }}</td></tr
          ></tbody
        >
      </table>
    </div>
    <template v-if="alarm.query">
      <div class="console-actions">
        <button :disabled="disabled" @click="emit('page', alarm.query.queryId, undefined)"
          >返回告警首页</button
        >
        <button
          :disabled="disabled || !alarm.result?.hasMore || !alarm.result.nextCursor"
          @click="emit('page', alarm.query.queryId, alarm.result!.nextCursor!)"
          >下一页告警</button
        >
        <button
          v-if="!alarm.result"
          :disabled="disabled"
          @click="emit('page', alarm.query.queryId, alarm.pageCursor)"
          >重试告警当前页</button
        >
      </div>
    </template>
  </section>
</template>
<script setup lang="ts">
  import type { PreviewRow } from '@/features/dashboard/device-preview'
  defineProps<{ componentId: string; alarm: NonNullable<PreviewRow['alarm']>; disabled: boolean }>()
  const emit = defineEmits<{ page: [queryId: string, cursor: string | undefined] }>()
</script>
<style scoped>
  .alarm-scroll {
    max-width: 100%;
    overflow-x: auto;
  }
  table {
    width: 100%;
    border-collapse: collapse;
  }
  th,
  td {
    padding: 4px;
    text-align: left;
    white-space: nowrap;
  }
</style>
