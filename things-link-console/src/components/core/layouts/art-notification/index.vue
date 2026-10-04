<!-- ADR0093：只展示当前Console账号的告警阅读事实，不复用事故ACK。 -->
<template>
  <section
    v-if="value"
    class="art-notification-panel"
    data-testid="alarm-inbox-panel"
    aria-label="告警通知中心"
    @click.stop
    @keydown.esc="$emit('update:value', false)"
  >
    <div class="inbox-heading">
      <h2>告警通知</h2>
      <button type="button" aria-label="关闭告警通知" @click="$emit('update:value', false)"
        >关闭</button
      >
    </div>
    <p class="inbox-description"
      >当前项目 · 最近30天的告警激活通知。已读仅影响自己，不确认或清除告警。</p
    >
    <p v-if="!available" role="status">请登录并选择项目后查看告警通知。</p>
    <template v-else>
      <p v-if="projectStatus === 'LOADING'" role="status">正在核对项目状态，暂不能标记已读。</p>
      <p v-else-if="projectStatus === 'ARCHIVED'" role="status">项目已归档，通知只读。</p>
      <p v-else-if="projectStatus !== 'ACTIVE'" role="status"
        >项目状态暂不可用，暂不能标记已读，请刷新通知。</p
      >
      <div class="inbox-toolbar">
        <span
          >未读：{{
            inbox.unreadCount === null
              ? '未知'
              : inbox.unreadCount >= 100
                ? '99+'
                : inbox.unreadCount
          }}</span
        >
        <button type="button" :disabled="inbox.loading || inbox.marking" @click="$emit('refresh')"
          >刷新通知</button
        >
      </div>
      <div v-if="inbox.countError" role="alert" class="inbox-error">
        <span>{{ inbox.countError }}</span>
        <button type="button" @click="inbox.refreshCount()">重试未读数</button>
      </div>
      <div v-if="inbox.error" role="alert" class="inbox-error">
        <span>{{ inbox.error }}</span>
        <button type="button" :disabled="inbox.loading || inbox.marking" @click="$emit('refresh')"
          >重试通知</button
        >
      </div>
      <p v-if="inbox.loading" role="status">正在加载告警通知…</p>
      <p v-else-if="!inbox.error && !inbox.items.length" role="status">最近30天暂无告警通知。</p>
      <ul v-if="inbox.items.length" class="inbox-items" :aria-busy="inbox.loading">
        <li
          v-for="item in inbox.items"
          :key="item.eventId"
          data-testid="alarm-inbox-item"
          :data-event-id="item.eventId"
        >
          <div class="inbox-item-heading">
            <strong>{{ item.alarmType }}</strong>
            <span>{{ severityLabel(item.severity) }}</span>
          </div>
          <div class="inbox-item-detail">
            <time>{{ formatTime(item.receivedAt) }}</time>
            <span>{{ item.read ? '已读' : '未读' }}</span>
            <button
              v-if="!item.read"
              type="button"
              :disabled="!writable || inbox.loading || inbox.marking"
              @click="markItem(item.eventId)"
              >标记已读</button
            >
          </div>
        </li>
      </ul>
      <div class="inbox-actions">
        <button
          type="button"
          :disabled="!writable || inbox.loading || inbox.marking || !hasUnreadItems"
          @click="inbox.markCurrentPageRead()"
          >标记当前页已读</button
        >
        <span v-if="inbox.marking" role="status">正在保存…</span>
      </div>
      <nav class="inbox-pagination" aria-label="告警通知分页">
        <button
          type="button"
          :disabled="!inbox.canGoBack || inbox.loading || inbox.marking"
          @click="inbox.previousPage()"
          >上一页</button
        >
        <span>第 {{ inbox.pageNumber }} 页</span>
        <button
          type="button"
          :disabled="!inbox.hasMore || inbox.loading || inbox.marking"
          @click="inbox.nextPage()"
          >下一页</button
        >
      </nav>
    </template>
  </section>
</template>

<script setup lang="ts">
  import { computed, type UnwrapRef } from 'vue'
  import type { useAlarmInbox } from '@/composables/useAlarmInbox'
  import { formatTime } from '@/utils/time'

  defineOptions({ name: 'ArtNotification' })
  const props = defineProps<{
    value: boolean
    available: boolean
    writable: boolean
    projectStatus: string
    inbox: UnwrapRef<ReturnType<typeof useAlarmInbox>>
  }>()
  defineEmits<{ 'update:value': [value: boolean]; refresh: [] }>()
  const hasUnreadItems = computed(() => props.inbox.items.some((item) => !item.read))
  const severityLabel = (severity?: string) =>
    ({ CRITICAL: '严重', MAJOR: '重要', MINOR: '次要', WARNING: '警告', INFO: '信息' })[
      severity as 'CRITICAL' | 'MAJOR' | 'MINOR' | 'WARNING' | 'INFO'
    ] ?? '未知等级'
  const markItem = (id?: string) => {
    if (id) void props.inbox.markRead([id])
  }
</script>

<style lang="scss" scoped>
  .art-notification-panel {
    position: absolute;
    top: 60px;
    right: 16px;
    z-index: 2000;
    width: min(440px, calc(100vw - 32px));
    max-height: calc(100vh - 100px);
    padding: 16px;
    overflow-y: auto;
    color: var(--art-text-gray-800);
    background: var(--art-main-bg-color, var(--el-bg-color));
    border: 1px solid var(--art-border-color);
    border-radius: 8px;
    box-shadow: 0 8px 24px rgb(0 0 0 / 15%);

    button {
      padding: 5px 8px;
      font: inherit;
      font-size: 13px;
      color: var(--el-color-primary);
      cursor: pointer;
      background: transparent;
      border: 1px solid var(--art-border-color);
      border-radius: 4px;

      &:disabled {
        color: var(--el-text-color-disabled);
        cursor: not-allowed;
      }
    }
  }

  .inbox-heading,
  .inbox-toolbar,
  .inbox-item-heading,
  .inbox-item-detail,
  .inbox-actions,
  .inbox-pagination {
    display: flex;
    gap: 8px;
    align-items: center;
    justify-content: space-between;
  }

  .inbox-heading h2 {
    margin: 0;
    font-size: 16px;
  }
  .inbox-description {
    margin: 12px 0;
    font-size: 12px;
    line-height: 1.6;
  }
  .inbox-items {
    padding: 0;
    margin: 12px 0;
    list-style: none;
  }
  .inbox-items li {
    padding: 12px 0;
    border-bottom: 1px solid var(--art-border-color);
  }
  .inbox-item-heading strong {
    overflow-wrap: anywhere;
  }
  .inbox-item-detail {
    margin-top: 8px;
    font-size: 12px;
  }
  .inbox-actions {
    justify-content: flex-start;
    margin-top: 12px;
  }
  .inbox-pagination {
    margin-top: 16px;
  }
  .inbox-error {
    margin-top: 8px;
    color: var(--el-color-danger);
  }
</style>
