<template>
  <section>
    <div class="console-toolbar">
      <div class="console-actions">
        <ElButton
          v-if="canManage"
          :type="mode === 'definitions' ? 'primary' : 'default'"
          @click="mode = 'definitions'"
          >项目消息规则候选</ElButton
        >
        <ElButton :type="mode === 'history' ? 'primary' : 'default'" @click="mode = 'history'"
          >执行尝试</ElButton
        >
        <ElButton :type="mode === 'actions' ? 'primary' : 'default'" @click="mode = 'actions'"
          >设备动作</ElButton
        >
      </div>
      <ElButton :loading="loading" @click="refresh">刷新消息规则</ElButton>
    </div>
    <p class="console-description">{{
      mode === 'definitions'
        ? '项目规则候选未绑定此设备；任意脚本的过滤结果只能运行时判定，暂停规则不处理新消息。'
        : mode === 'history'
          ? '仅包含写入设备身份后的执行尝试；旧日志设备未知，不推测回填。SUCCESS不表示物理动作成功。'
          : '按动作原设备查询仍保留的记录，含旧动作；投递终态不代替物理设备验收。'
    }}</p>
    <ElAlert v-if="failed" type="error" title="消息规则读取失败，请刷新重试" :closable="false" />
    <ElTable v-loading="loading" :data="rows" row-key="id">
      <ElTableColumn
        prop="id"
        :label="
          mode === 'definitions'
            ? '消息规则 ID'
            : mode === 'history'
              ? '尝试记录 ID'
              : '动作记录 ID'
        "
        min-width="230"
        show-overflow-tooltip
      />
      <template v-if="mode === 'definitions'">
        <ElTableColumn prop="name" label="名称" min-width="130" />
        <ElTableColumn label="状态" width="90"
          ><template #default="{ row }">{{
            definitionStatuses[row.status]
          }}</template></ElTableColumn
        >
        <ElTableColumn prop="versionNumber" label="发布版本号" width="110" />
        <ElTableColumn label="设备动作" width="100"
          ><template #default="{ row }">{{
            row.hasDeviceAction ? '有' : '无'
          }}</template></ElTableColumn
        >
      </template>
      <template v-else>
        <ElTableColumn prop="ruleId" label="原消息规则 ID" min-width="230" show-overflow-tooltip />
        <ElTableColumn
          prop="ruleVersionId"
          label="原版本 ID"
          min-width="230"
          show-overflow-tooltip
        />
        <ElTableColumn label="执行状态" width="160"
          ><template #default="{ row }">{{
            (mode === 'history' ? executionStatuses : actionStatuses)[row.status]
          }}</template></ElTableColumn
        >
        <ElTableColumn prop="messageId" label="原消息 ID" min-width="230" show-overflow-tooltip />
        <template v-if="mode === 'history'">
          <ElTableColumn prop="attempt" label="尝试序号" width="95" />
          <ElTableColumn prop="resultCode" label="结果码" min-width="145" />
          <ElTableColumn prop="durationMillis" label="耗时毫秒" width="105" />
          <ElTableColumn prop="inputBytes" label="输入字节" width="95" />
          <ElTableColumn prop="outputBytes" label="输出字节" width="95" />
        </template>
        <template v-else>
          <ElTableColumn label="操作类型" width="110"
            ><template #default="{ row }">{{
              row.operationType === 'COMMAND' ? '命令' : '属性设置'
            }}</template></ElTableColumn
          >
          <ElTableColumn
            prop="commandId"
            label="命令 ID（可空）"
            min-width="230"
            show-overflow-tooltip
          />
          <ElTableColumn prop="failureCode" label="失败码" min-width="140" />
        </template>
      </template>
      <ElTableColumn
        :label="mode === 'definitions' ? '定义建立时间' : '执行登记时间'"
        min-width="180"
        ><template #default="{ row }">{{ formatTime(row.createdAt) }}</template></ElTableColumn
      >
      <template #empty
        ><ElEmpty
          :description="
            failed
              ? '消息规则关系不可用'
              : loading
                ? '正在读取消息规则'
                : mode === 'definitions'
                  ? '暂无当前发布的项目消息规则候选'
                  : mode === 'history'
                    ? '暂无已知设备身份的执行尝试'
                    : '暂无该设备消息规则动作记录'
          "
      /></template>
    </ElTable>
    <div class="console-page-actions">
      <ElButton :disabled="loading || pageIndex === 0" @click="previous">上一页</ElButton>
      <span>第 {{ pageIndex + 1 }} 页</span>
      <ElButton :disabled="loading || failed || !nextCursor" @click="next">下一页</ElButton>
    </div>
  </section>
</template>
<script setup lang="ts">
  import { useUserStore } from '@/store/modules/user'
  import { formatTime } from '@/utils/time'
  import {
    fetchDeviceMessageRules,
    type DeviceMessageRule,
    type DeviceMessageRuleExecution,
    type DeviceMessageRuleAction,
    type DeviceMessageRuleMode
  } from '@/api/device-message-rules'

  const props = defineProps<{ projectId: string; deviceId: string; canManage: boolean }>()
  const user = useUserStore()
  const rows = ref<(DeviceMessageRule | DeviceMessageRuleExecution | DeviceMessageRuleAction)[]>([])
  const loading = ref(false)
  const failed = ref(false)
  const pageIndex = ref(0)
  const nextCursor = ref<string>()
  let cursors: (string | undefined)[] = [undefined]
  let epoch = 0
  let controller: AbortController | undefined
  const mode = ref<DeviceMessageRuleMode>(props.canManage ? 'definitions' : 'history')
  const definitionStatuses: Record<string, string> = { ACTIVE: '启用', PAUSED: '暂停' }
  const executionStatuses: Record<string, string> = {
    SUCCESS: '处理成功',
    RETRY_SCHEDULED: '已安排重试',
    DEAD_LETTER: '已进入死信'
  }
  const actionStatuses: Record<string, string> = {
    ACCEPTED: '已受理',
    REJECTED: '已拒绝',
    SUCCEEDED: '设备报告成功',
    FAILED: '失败',
    TIMED_OUT: '超时'
  }
  function validRow(
    row: DeviceMessageRule | DeviceMessageRuleExecution | DeviceMessageRuleAction,
    kind: DeviceMessageRuleMode
  ) {
    if (!row.id || !Number.isFinite(Date.parse(row.createdAt))) return false
    if (kind === 'definitions') {
      const d = row as DeviceMessageRule
      return (
        d.relationScope === 'PROJECT_CANDIDATE' &&
        typeof d.name === 'string' &&
        Object.hasOwn(definitionStatuses, d.status) &&
        !!d.activeVersionId &&
        Number.isInteger(d.versionNumber) &&
        d.versionNumber > 0 &&
        typeof d.hasDeviceAction === 'boolean'
      )
    }
    const fact = row as DeviceMessageRuleExecution | DeviceMessageRuleAction
    if (!fact.ruleId || !fact.ruleVersionId || !fact.messageId) return false
    if (kind === 'history') {
      const e = row as DeviceMessageRuleExecution
      return (
        Object.hasOwn(executionStatuses, e.status) &&
        Number.isInteger(e.attempt) &&
        e.attempt >= 1 &&
        e.attempt <= 3 &&
        typeof e.resultCode === 'string' &&
        /^[A-Z][A-Z0-9_]{0,63}$/.test(e.resultCode) &&
        [e.durationMillis, e.inputBytes, e.outputBytes].every(
          (v) => Number.isSafeInteger(v) && v >= 0
        )
      )
    }
    const a = row as DeviceMessageRuleAction
    return (
      Object.hasOwn(actionStatuses, a.status) &&
      ['COMMAND', 'PROPERTY_SET'].includes(a.operationType) &&
      (a.status === 'REJECTED' ? !a.commandId : !!a.commandId)
    )
  }
  async function load(index: number) {
    const run = ++epoch
    controller?.abort()
    controller = new AbortController()
    rows.value = []
    failed.value = false
    loading.value = true
    nextCursor.value = undefined
    pageIndex.value = index
    const device = props.deviceId
    const kind = mode.value
    try {
      const page = await fetchDeviceMessageRules(
        props.projectId,
        device,
        kind,
        cursors[index],
        controller.signal
      )
      if (run !== epoch) return
      if (!Array.isArray(page.items) || page.items.some((row) => !validRow(row, kind)))
        throw new Error('设备消息规则响应不完整')
      rows.value = page.items
      nextCursor.value = page.nextCursor ?? undefined
    } catch {
      if (run === epoch) failed.value = true
    } finally {
      if (run === epoch) loading.value = false
    }
  }
  function refresh() {
    cursors = [undefined]
    void load(0)
  }
  function next() {
    if (loading.value || failed.value || !nextCursor.value) return
    cursors[pageIndex.value + 1] = nextCursor.value
    void load(pageIndex.value + 1)
  }
  function previous() {
    if (!loading.value && pageIndex.value > 0) void load(pageIndex.value - 1)
  }
  watch(
    () => props.canManage,
    (allowed) => {
      if (!allowed) mode.value = 'history'
    }
  )
  watch(
    () => [
      props.canManage,
      mode.value,
      props.projectId,
      props.deviceId,
      user.info.userId,
      user.info.tenantId
    ],
    refresh,
    {
      immediate: true
    }
  )
  onBeforeUnmount(() => {
    ++epoch
    controller?.abort()
  })
</script>
