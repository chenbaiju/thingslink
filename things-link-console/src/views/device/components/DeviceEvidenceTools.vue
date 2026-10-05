<template>
  <section data-testid="device-evidence-tools">
    <h4>历史与告警事实</h4>
    <p class="console-description"
      >手动读取，不调用模型。空结果不代表正常；单位及行业阈值未提供。</p
    >
    <p v-if="!available">先加载当前设备的属性目录。</p>
    <template v-else>
      <h5>单属性历史</h5>
      <div class="console-toolbar">
        <label
          >数值属性
          <select v-model="property" :disabled="busy" aria-label="历史数值属性">
            <option value="">请选择</option>
            <option v-for="key in propertyKeys" :key="key" :value="key">{{ key }}</option>
          </select>
        </label>
        <label
          >起点（包含）<input
            v-model="from"
            type="datetime-local"
            :disabled="busy"
            aria-label="历史起点"
        /></label>
        <label
          >终点（不包含）<input
            v-model="to"
            type="datetime-local"
            :disabled="busy"
            aria-label="历史终点"
        /></label>
        <ElButton :disabled="busy || parentBusy || !property" @click="readHistory"
          >读取历史</ElButton
        >
      </div>
      <p>使用浏览器本地时区，提交时转换为 UTC；最多过去 24 小时。时间和属性变化会清除旧结果。</p>
      <p v-if="!propertyKeys.length">当前目录没有 NUMBER 数值属性。</p>
      <ElAlert v-if="historyError" type="error" :closable="false" :title="historyError" />
      <div v-if="history" data-testid="history-evidence-result">
        <p>查询区间：{{ history.requestedFrom }} 至 {{ history.requestedTo }}</p>
        <p
          >实际区间：{{ history.effectiveFrom }} 至 {{ history.effectiveTo }}；保留窗口{{
            history.retentionClipped ? '已裁剪' : '未裁剪'
          }}</p
        >
        <p
          >请求粒度 {{ history.requestedGranularity }}；实际粒度
          {{ history.actualGranularity }}；聚合 {{ history.aggregation }}；读取时间
          {{ history.readAt }}</p
        >
        <p v-if="history.state !== 'HAS_POINTS'"
          >{{
            history.state === 'OUTSIDE_RETENTION'
              ? '查询位于保留窗口外，未取得点。'
              : '区间内未取得点。'
          }}
          不代表正常。</p
        >
        <div v-if="historyOverview.length" data-testid="history-evidence-overview">
          <h5>返回证据概览</h5>
          <p>按来源模型分别统计，单位未提供；不计算完整率、异常或趋势。</p>
          <p v-if="history.actualGranularity !== 'RAW'">
            以下范围只包含已返回桶均值，不代表原始采样的最小值或最大值。
          </p>
          <ul>
            <li
              v-for="group in historyOverview"
              :key="group.key"
              data-testid="history-source-group"
            >
              {{ sourceLabels[group.source] }}；模型 {{ group.model ?? '未提供' }}；返回
              {{ group.points }} 个点；样本计数合计 {{ group.samples }}；
              <template v-if="group.minimum !== null && group.maximum !== null">
                {{ history.actualGranularity === 'RAW' ? '原始点值范围' : '桶均值范围' }}：
                {{ group.minimum }} 至 {{ group.maximum }}
              </template>
              <template v-else>数值未提供，不计算范围</template>
            </li>
          </ul>
        </div>
        <ElTable :data="history.points">
          <ElTableColumn prop="at" label="时间（UTC）" />
          <ElTableColumn label="值（单位未提供）"
            ><template #default="{ row }">{{
              row.value ?? '来源未知，未提供数值'
            }}</template></ElTableColumn
          >
          <ElTableColumn prop="sampleCount" label="样本数" />
          <ElTableColumn label="来源"
            ><template #default="{ row }">{{ sourceLabels[row.source] }}</template></ElTableColumn
          >
          <ElTableColumn label="来源模型"
            ><template #default="{ row }">{{
              row.sourceModelVersionId ?? '未提供'
            }}</template></ElTableColumn
          >
        </ElTable>
      </div>
      <h5>告警事故页</h5>
      <div class="console-toolbar">
        <ElButton :disabled="busy || parentBusy" @click="readAlarms(false)">读取告警首页</ElButton>
        <ElButton :disabled="busy || parentBusy || !alarms?.hasMore" @click="readAlarms(true)"
          >读取下一页</ElButton
        >
      </div>
      <p>每页 20 条，下一页替换本页；无时间窗筛选，不承诺分页期间全量稳定快照。</p>
      <ElAlert v-if="alarmError" type="error" :closable="false" :title="alarmError" />
      <div v-if="alarms?.items" data-testid="alarm-evidence-result">
        <p>取证时间 {{ alarms.collectedAt }}；事故来源模型未提供，当前模型仅用于访问复核。</p>
        <p v-if="!alarms.items.length">本页为空，不代表设备正常。</p>
        <ElTable :data="alarms.items" row-key="id">
          <ElTableColumn prop="id" label="事故编号" />
          <ElTableColumn label="严重度"
            ><template #default="{ row }">{{
              severityLabels[row.severity]
            }}</template></ElTableColumn
          >
          <ElTableColumn label="条件状态"
            ><template #default="{ row }">{{
              conditionLabels[row.conditionState]
            }}</template></ElTableColumn
          >
          <ElTableColumn label="确认状态"
            ><template #default="{ row }">{{
              row.ackState === 'ACKNOWLEDGED' ? '已确认' : '未确认'
            }}</template></ElTableColumn
          >
          <ElTableColumn prop="firstConditionAt" label="首次条件成立" />
          <ElTableColumn label="激活时间"
            ><template #default="{ row }">{{
              row.activatedAt ?? '未提供'
            }}</template></ElTableColumn
          >
          <ElTableColumn label="清除时间"
            ><template #default="{ row }">{{ row.clearedAt ?? '未提供' }}</template></ElTableColumn
          >
          <ElTableColumn label="确认时间"
            ><template #default="{ row }">{{
              row.acknowledgedAt ?? '未提供'
            }}</template></ElTableColumn
          >
          <ElTableColumn prop="lastReceivedAt" label="最近接收时间" />
          <ElTableColumn prop="version" label="事故版本" />
        </ElTable>
        <p>{{ alarms.hasMore ? '查询时有后续页，可手动继续。' : '查询时未返回后续游标。' }}</p>
      </div>
    </template>
  </section>
</template>

<script setup lang="ts">
  import { computed, onBeforeUnmount, ref, watch } from 'vue'
  import { useUserStore } from '@/store/modules/user'
  import { currentIdentityEpoch } from '@/utils/http/identity-scope'
  import {
    readHistoryEvidence,
    readAlarmEvidence,
    type HistoryEvidence,
    type AlarmEvidence
  } from '@/api/assistant-tools'

  const props = defineProps<{
    projectId: string
    deviceId: string
    modelVersionId: string
    propertyKeys: string[]
    parentBusy?: boolean
  }>()
  const user = useUserStore()
  const property = ref(''),
    from = ref(''),
    to = ref('')
  const history = ref<HistoryEvidence>(),
    alarms = ref<AlarmEvidence>()
  // 只汇总已经过身份和响应校验的本次事实，历史模型分别保留，不推断完整率或行业异常。
  const historyOverview = computed(() => {
    const groups = new Map<
      string,
      {
        key: string
        source: string
        model: string | null
        points: number
        samples: string
        minimum: number | null
        maximum: number | null
      }
    >()
    for (const point of history.value?.points ?? []) {
      const source = point.source!,
        model = point.sourceModelVersionId ?? null
      const key = `${source}:${model ?? ''}`
      const group = groups.get(key) ?? {
        key,
        source,
        model,
        points: 0,
        samples: '0',
        minimum: null,
        maximum: null
      }
      group.points++
      group.samples = (BigInt(group.samples) + BigInt(point.sampleCount!)).toString()
      if (typeof point.value === 'number') {
        group.minimum = group.minimum === null ? point.value : Math.min(group.minimum, point.value)
        group.maximum = group.maximum === null ? point.value : Math.max(group.maximum, point.value)
      }
      groups.set(key, group)
    }
    return [...groups.values()]
  })
  const historyError = ref(''),
    alarmError = ref(''),
    busy = ref(false)
  const uuid = (v: unknown): v is string =>
    typeof v === 'string' && /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(v)
  const time = (v: unknown): v is string => typeof v === 'string' && Number.isFinite(Date.parse(v))
  const nullableTime = (v: unknown) => v === null || time(v)
  const sourceLabels: Record<string, string> = {
    CURRENT_MODEL: '当前模型来源',
    HISTORICAL_MODEL: '历史模型来源，不重新解释',
    SOURCE_UNKNOWN: '来源未知'
  }
  const severityLabels: Record<string, string> = {
    CRITICAL: '紧急',
    MAJOR: '重要',
    MINOR: '次要',
    WARNING: '警告',
    INFO: '信息'
  }
  const conditionLabels: Record<string, string> = {
    PENDING: '待激活',
    ACTIVE: '活动',
    CLEARED: '已清除'
  }
  const available = computed(() =>
    Boolean(
      user.isLogin &&
        user.info.userId &&
        user.info.tenantId &&
        props.projectId === user.info.currentProjectId &&
        uuid(props.modelVersionId) &&
        props.deviceId &&
        user.info.roles?.some((r) => ['OWNER', 'ADMIN', 'OPERATOR', 'VIEWER'].includes(r))
    )
  )
  let generation = 0,
    controller: AbortController | undefined
  function cancel() {
    ++generation
    controller?.abort()
    controller = undefined
    busy.value = false
  }
  function clear() {
    cancel()
    history.value = alarms.value = undefined
    historyError.value = alarmError.value = ''
    property.value = ''
    const local = (date: Date) =>
      new Date(date.getTime() - date.getTimezoneOffset() * 60000).toISOString().slice(0, 16)
    const now = new Date()
    from.value = local(new Date(now.getTime() - 3600000))
    to.value = local(now)
  }
  function begin() {
    cancel()
    const run = generation,
      epoch = currentIdentityEpoch()
    controller = new AbortController()
    busy.value = true
    return {
      signal: controller.signal,
      current: () => run === generation && epoch === currentIdentityEpoch() && available.value
    }
  }
  function matchesHistory(value: HistoryEvidence, start: string, end: string) {
    if (
      !value ||
      value.projectId !== props.projectId ||
      value.deviceId !== props.deviceId ||
      value.modelVersionId !== props.modelVersionId ||
      value.propertyKey !== property.value ||
      !time(value.requestedFrom) ||
      !time(value.requestedTo) ||
      Date.parse(value.requestedFrom) !== Date.parse(start) ||
      Date.parse(value.requestedTo) !== Date.parse(end) ||
      !time(value.effectiveFrom) ||
      !time(value.effectiveTo) ||
      !time(value.readAt) ||
      typeof value.retentionClipped !== 'boolean' ||
      value.requestedGranularity !== 'RAW' ||
      value.aggregation !== 'AVG' ||
      !['RAW', 'ONE_MINUTE', 'ONE_HOUR', 'ONE_DAY'].includes(value.actualGranularity) ||
      !['HAS_POINTS', 'NO_POINTS', 'OUTSIDE_RETENTION'].includes(value.state) ||
      !Array.isArray(value.points) ||
      value.points.length > 2000
    )
      return false
    const a = Date.parse(value.effectiveFrom),
      b = Date.parse(value.effectiveTo)
    if (value.state === 'OUTSIDE_RETENTION')
      return a === b && value.retentionClipped && value.points.length === 0
    if (
      a < Date.parse(start) ||
      b > Date.parse(end) ||
      a >= b ||
      value.retentionClipped !== (a !== Date.parse(start) || b !== Date.parse(end)) ||
      (value.state === 'HAS_POINTS') !== value.points.length > 0
    )
      return false
    return value.points.every(
      (p) =>
        p &&
        time(p.at) &&
        Date.parse(p.at) >= a &&
        Date.parse(p.at) < b &&
        typeof p.sampleCount === 'string' &&
        /^[1-9][0-9]{0,18}$/.test(p.sampleCount) &&
        BigInt(p.sampleCount) <= 9223372036854775807n &&
        (p.source === 'SOURCE_UNKNOWN'
          ? p.value === null && p.sourceModelVersionId === null
          : typeof p.value === 'number' &&
            Number.isFinite(p.value) &&
            uuid(p.sourceModelVersionId) &&
            (p.source === 'CURRENT_MODEL'
              ? p.sourceModelVersionId === props.modelVersionId
              : p.source === 'HISTORICAL_MODEL' && p.sourceModelVersionId !== props.modelVersionId))
    )
  }
  async function readHistory() {
    if (!available.value || busy.value || props.parentBusy) return
    history.value = undefined
    historyError.value = ''
    const start = new Date(from.value),
      end = new Date(to.value)
    if (
      !props.propertyKeys.includes(property.value) ||
      !/^[A-Za-z0-9_-]{1,64}$/.test(property.value) ||
      !Number.isFinite(start.getTime()) ||
      !Number.isFinite(end.getTime()) ||
      start.getTime() < 0 ||
      start >= end ||
      end.getTime() > Date.now() ||
      end.getTime() - start.getTime() > 86400000
    ) {
      historyError.value = '请选择数值属性和合法过去区间，最多 24 小时。'
      return
    }
    const run = begin()
    try {
      const result = await readHistoryEvidence(
        props.projectId,
        props.deviceId,
        props.modelVersionId,
        property.value,
        start.toISOString(),
        end.toISOString(),
        run.signal
      )
      if (!run.current()) return
      if (!matchesHistory(result, start.toISOString(), end.toISOString()))
        throw new Error('INVALID_HISTORY')
      history.value = result
    } catch {
      if (run.current()) historyError.value = '历史读取未取得有效回执，请核对权限和时间后手动重试。'
    } finally {
      if (run.current()) busy.value = false
    }
  }
  function matchesAlarms(value: AlarmEvidence) {
    if (
      !value ||
      value.projectId !== props.projectId ||
      value.deviceId !== props.deviceId ||
      value.currentModelVersionId !== props.modelVersionId ||
      !time(value.collectedAt) ||
      value.sourceModelState !== 'NOT_PROVIDED' ||
      value.limit !== 20 ||
      typeof value.hasMore !== 'boolean' ||
      !Array.isArray(value.items) ||
      value.items.length > 20 ||
      new Set(value.items.map((p) => p?.id)).size !== value.items.length ||
      (value.hasMore
        ? value.items.length !== 20 ||
          typeof value.nextCursor !== 'string' ||
          !/^[\x20-\x7e]{1,2048}$/.test(value.nextCursor)
        : value.nextCursor !== null)
    )
      return false
    return value.items.every(
      (p) =>
        p &&
        uuid(p.id) &&
        typeof p.severity === 'string' &&
        Object.hasOwn(severityLabels, p.severity) &&
        typeof p.conditionState === 'string' &&
        Object.hasOwn(conditionLabels, p.conditionState) &&
        typeof p.ackState === 'string' &&
        ['UNACKNOWLEDGED', 'ACKNOWLEDGED'].includes(p.ackState) &&
        time(p.firstConditionAt) &&
        time(p.lastReceivedAt) &&
        nullableTime(p.activatedAt) &&
        nullableTime(p.clearedAt) &&
        nullableTime(p.acknowledgedAt) &&
        typeof p.version === 'number' &&
        Number.isInteger(p.version) &&
        p.version >= 0
    )
  }
  async function readAlarms(next: boolean) {
    if (!available.value || busy.value || props.parentBusy) return
    const cursor = next ? alarms.value?.nextCursor : undefined
    if (next && (!alarms.value?.hasMore || !cursor)) return
    alarms.value = undefined
    alarmError.value = ''
    const run = begin()
    try {
      const result = await readAlarmEvidence(
        props.projectId,
        props.deviceId,
        props.modelVersionId,
        run.signal,
        cursor ?? undefined
      )
      if (!run.current()) return
      if (!matchesAlarms(result)) throw new Error('INVALID_ALARMS')
      alarms.value = result
    } catch {
      if (run.current())
        alarmError.value = '告警页未取得有效回执，请手动重新读取首页；未将失败记作空页。'
    } finally {
      if (run.current()) busy.value = false
    }
  }
  watch(
    () => [
      props.projectId,
      props.deviceId,
      props.modelVersionId,
      props.propertyKeys.join(','),
      user.isLogin,
      user.info.userId,
      user.info.tenantId,
      user.info.currentProjectId,
      user.info.roles?.join(','),
      currentIdentityEpoch()
    ],
    clear,
    { immediate: true }
  )
  watch(
    () => [property.value, from.value, to.value],
    () => {
      cancel()
      history.value = undefined
      historyError.value = ''
    }
  )
  onBeforeUnmount(cancel)
</script>
