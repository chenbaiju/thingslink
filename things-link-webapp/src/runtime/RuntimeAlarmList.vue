<script setup lang="ts">
import type { AlarmResult } from './observation-data'
import { interactionStateLabel } from './history-presentation'
withDefaults(defineProps<{ componentId: string; state: string; page?: AlarmResult; loading?: boolean; showClearedAt?: boolean }>(), { showClearedAt: true })
const emit = defineEmits<{ page: [cursor: string | undefined] }>()
const conditions: Record<string, string> = { PENDING: '待激活', ACTIVE: '活跃', CLEARED: '已恢复' }
const acknowledgements: Record<string, string> = { UNACKNOWLEDGED: '未确认', ACKNOWLEDGED: '已确认' }
const severities: Record<string, string> = { CRITICAL: '紧急', MAJOR: '严重', MINOR: '次要', WARNING: '警告', INFO: '提示' }
</script>
<template>
  <div :data-testid="`alarm-list-${componentId}`" :data-state="state">
    <p v-if="state !== 'VALUE'" role="status">{{ interactionStateLabel(state) }}</p>
    <template v-else-if="page">
      <p v-if="page.items.length === 0">当前条件下暂无告警</p>
      <article v-for="alarm in page.items" :key="alarm.id" :data-testid="`alarm-row-${alarm.id}`" class="alarm-row">
        <h4>{{ alarm.alarmType }}</h4>
        <p>{{ severities[alarm.severity] }} · {{ conditions[alarm.conditionState] }} · {{ acknowledgements[alarm.ackState] }}（确认状态不代表个人已读）</p>
        <dl>
          <dt>告警编号</dt><dd>{{ alarm.id }}</dd><dt>设备编号</dt><dd>{{ alarm.deviceId }}</dd>
          <dt>首次满足条件</dt><dd>{{ alarm.firstConditionAt }}</dd>
          <dt>激活时间</dt><dd>{{ alarm.activatedAt ?? '未提供' }}</dd>
          <template v-if="showClearedAt"><dt>恢复时间</dt><dd>{{ alarm.clearedAt ?? '未提供' }}</dd></template>
          <dt>确认时间</dt><dd>{{ alarm.acknowledgedAt ?? '未提供' }}</dd>
          <dt>最近接收时间</dt><dd>{{ alarm.lastReceivedAt }}</dd><dt>记录版本</dt><dd>{{ alarm.version }}</dd>
        </dl>
      </article>
      <div class="alarm-pages">
        <button type="button" :data-testid="`alarm-first-${componentId}`" :disabled="loading" @click="emit('page', undefined)">首页</button>
        <button v-if="page.hasMore" type="button" :data-testid="`alarm-next-${componentId}`" :disabled="loading" @click="emit('page', page.nextCursor ?? undefined)">下一页</button>
      </div>
    </template>
  </div>
</template>
<style scoped>
.alarm-row { border-bottom: 1px solid currentColor; padding: 8px 0; }
h4, p { margin: 4px 0; overflow-wrap: anywhere; }
dl { margin: 6px 0; }dt { font-weight: 600; }dd { margin: 0 0 4px; white-space: pre-wrap; overflow-wrap: anywhere; }
.alarm-pages { display: flex; gap: 8px; margin-top: 8px; }
button { font: inherit; color: inherit; background: inherit; border: 1px solid currentColor; border-radius: 4px; padding: 6px 10px; }
</style>
