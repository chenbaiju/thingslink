<template>
  <section class="console-fragment console-editor-section" aria-label="草稿设备数据预览">
    <h3 class="console-heading">草稿数据预览</h3>
    <p class="console-description">文字选择即时预览；设备数据可刷新，不代表已发布内容。</p>
    <DesignerTextPreview
      :schema="schema"
      :page-id="pageId"
      :project-id="projectId"
      :available="available && visible"
    />
    <el-button
      :disabled="!available || !visible || (busy && !latched)"
      :loading="busy && !latched"
      @click="refresh"
      >刷新草稿数据</el-button
    >
    <p data-testid="preview-rest-state" :data-state="status">{{
      status === 'REST_READY'
        ? '草稿数据已读取；可见时定期校准'
        : status === 'RECOVERING'
          ? '正在读取草稿数据'
          : status === 'RETRY_REQUIRED'
            ? '自动校准已停止，请显式刷新'
            : waitLabel
    }}</p>
    <p data-testid="preview-realtime-state" :data-state="realtimeState">{{
      realtimeState === 'SUBSCRIBED'
        ? '实时提示已连接；显示值仍来自权威读取'
        : realtimeState === 'CONNECTING'
          ? '正在确认实时订阅'
          : realtimeState === 'REST_READY'
            ? '实时提示不可用或无当前值依赖，保留定期权威读取'
            : '实时提示未连接'
    }}</p>
    <DesignerDeviceSelector
      v-for="selector in selectors"
      :key="selector.id"
      :project-id="projectId"
      :read-generation="directoryGeneration"
      :model-version-id="selector.modelVersionId"
      :variable-key="selector.variable.key"
      :title="selector.title"
      :placeholder="selector.placeholder"
      :multiple="selector.variable.type === 'DEVICE_MULTI'"
      :max-items="selector.variable.type === 'DEVICE_MULTI' ? selector.variable.maxItems : 1"
      :page-size="selector.pageSize"
      :selected="selectedIds(selector.variable.key)"
      :disabled="!available || !visible || busy"
      @change="changeSelection(selector.variable.key, $event)"
      @denied="readDenied"
    />
    <label v-for="variable in timeVariables" :key="variable.key">
      {{ variable.title }}（预览时间）
      <select
        :aria-label="`预览时间 ${variable.title}`"
        :disabled="!available || !visible"
        :value="timeSelections[variable.key] ?? variable.defaultPreset"
        @change="changeTime(variable.key, ($event.target as HTMLSelectElement).value)"
      >
        <option v-for="preset in variable.allowedPresets" :key="preset" :value="preset">{{
          presetLabels[preset]
        }}</option>
      </select>
    </label>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <p v-if="!busy && !rows.length && !error">尚未读取当前页设备数据。</p>
    <div
      v-for="row in rows.filter((item) => !item.selector)"
      :key="row.componentId"
      :data-preview-component="row.componentId"
      class="preview-row"
    >
      <strong>{{ row.title }}</strong
      >：<span>{{ row.text }}</span>
      <small v-if="row.detail"> {{ row.detail }}</small>
      <div v-if="row.gauge" data-testid="preview-gauge" :data-out-of-range="row.gauge.outOfRange">
        <p
          >量程：{{ row.gauge.minimum }} 至 {{ row.gauge.maximum
          }}<strong v-if="row.gauge.outOfRange"> · 超出量程</strong></p
        >
        <div
          class="gauge-track"
          role="img"
          :aria-label="`仪表值${row.text}，${row.gauge.outOfRange ? '超出量程' : '量程内'}`"
        >
          <div class="gauge-fill" :style="{ width: `${row.gauge.percent}%` }" />
        </div>
      </div>
      <DesignerAlarmSnapshot
        v-if="row.alarm"
        :component-id="row.componentId"
        :alarm="row.alarm"
        :disabled="!available || !visible || busy || paging"
        @page="pageAlarm"
      />
      <DesignerHistorySnapshot v-if="row.history" :history="row.history" />
      <DesignerDeviceValues v-if="row.table" :table="row.table" />
      <template v-if="row.composite">
        <DesignerJsonTree
          v-if="row.composite.mode === 'JSON'"
          :value="row.composite.value"
          :initial-expand-depth="row.composite.initialExpandDepth"
        />
        <DesignerListSnapshot
          v-else
          :value="row.composite.value"
          :row-limit="row.composite.rowLimit"
        />
      </template>
    </div>
  </section>
</template>
<script setup lang="ts">
  import { computed, onBeforeUnmount, provide, ref, shallowRef, watch } from 'vue'
  import DesignerJsonTree from './DesignerJsonTree.vue'
  import DesignerListSnapshot from './DesignerListSnapshot.vue'
  import DesignerDeviceSelector from './DesignerDeviceSelector.vue'
  import DesignerDeviceValues from './DesignerDeviceValues.vue'
  import DesignerHistorySnapshot from './DesignerHistorySnapshot.vue'
  import DesignerAlarmSnapshot from './DesignerAlarmSnapshot.vue'
  import DesignerTextPreview from './DesignerTextPreview.vue'
  import { useUserStore } from '@/store/modules/user'
  import { fetchDesignerAlarms } from '@/api/dashboard-alarms'
  import { fetchDesignerHistory } from '@/api/dashboard-history'
  import type { DashboardSchemaV1 } from '@things-link/client-contracts/dashboard/v1'
  import {
    fetchPreviewSnapshots,
    fetchPreviewCurrent,
    createDesignerReadScope
  } from '@/api/dashboard-binding'
  import {
    loadDesignerDevicePreview,
    refreshDesignerCurrent,
    DesignerCurrentRecoveryRequired,
    type DesignerCurrentPlan,
    type PreviewRow
  } from '@/features/dashboard/device-preview'
  import { createDesignerRealtime, type DesignerRealtimeState } from '@/api/dashboard-realtime'
  import {
    ownDesignerRealtime,
    closeDesignerRealtime,
    nextDesignerDirtyAt,
    markDesignerDirtyStart
  } from '@/features/dashboard/realtime-lifecycle'
  import { designerReadEligibility, subscribeDesignerReadBudget } from '@/api/designer-read-scope'
  import { createPreviewScheduler } from '@/features/dashboard/preview-scheduler'
  import { previewInteractionKey } from '@/features/dashboard/preview-interaction'
  const props = defineProps<{
    schema: DashboardSchemaV1
    pageId: string
    projectId: string
    available: boolean
  }>()
  const timeSelections = shallowRef<Record<string, string>>({})
  const presetLabels = {
    LAST_1_HOUR: '最近1小时',
    LAST_24_HOURS: '最近24小时',
    LAST_7_DAYS: '最近7天'
  }
  const timeVariables = computed(() => {
    const keys = new Set(
      (props.schema.pages.find((page) => page.id === props.pageId)?.components ?? []).flatMap(
        (component) =>
          component.kind === 'LINE_CHART'
            ? component.bindings.series.map((series) => series.value.timeRangeVariableKey)
            : []
      )
    )
    return props.schema.variables
      .filter((variable) => variable.type === 'TIME_RANGE' && keys.has(variable.key))
      .filter((variable) => variable.type === 'TIME_RANGE')
  })
  function changeTime(key: string, preset: string) {
    const variable = timeVariables.value.find((item) => item.key === key)
    if (
      !props.available ||
      !visible.value ||
      !variable ||
      !variable.allowedPresets.includes(preset as typeof variable.defaultPreset)
    )
      return
    timeSelections.value = { ...timeSelections.value, [key]: preset }
    scheduler.dependencyChanged()
  }
  const directoryGeneration = ref(0)
  const selections = shallowRef<Record<string, readonly string[]>>({})
  const selectors = computed(() =>
    (props.schema.pages.find((page) => page.id === props.pageId)?.components ?? []).flatMap(
      (component) => {
        if (component.kind !== 'DEVICE_SELECTOR') return []
        const variable = props.schema.variables.find(
          (item) => item.key === component.bindings.directory.variableKey
        )
        if (!variable || (variable.type !== 'DEVICE_SINGLE' && variable.type !== 'DEVICE_MULTI'))
          return []
        const model = props.schema.models.find((item) => item.key === variable.modelKey)
        return model
          ? [
              {
                id: component.id,
                variable,
                modelVersionId: model.versionId,
                title: component.props.title ?? variable.title,
                pageSize: component.props.pageSize,
                placeholder: component.props.placeholder
              }
            ]
          : []
      }
    )
  )
  function selectedIds(key: string): readonly string[] {
    if (Object.hasOwn(selections.value, key)) return selections.value[key]!
    const variable = props.schema.variables.find((item) => item.key === key)
    return variable?.type === 'DEVICE_SINGLE'
      ? variable.defaultDeviceId
        ? [variable.defaultDeviceId]
        : []
      : variable?.type === 'DEVICE_MULTI'
        ? variable.defaultDeviceIds
        : []
  }
  function changeSelection(key: string, ids: string[]) {
    if (!props.available || !visible.value) return
    const variable = props.schema.variables.find((item) => item.key === key)
    if (
      !variable ||
      (variable.type !== 'DEVICE_SINGLE' && variable.type !== 'DEVICE_MULTI') ||
      ids.length > (variable.type === 'DEVICE_SINGLE' ? 1 : variable.maxItems) ||
      new Set(ids).size !== ids.length
    )
      return
    selections.value = { ...selections.value, [key]: [...ids] }
    scheduler.dependencyChanged()
  }
  // 保持严格解析器数字品牌；深层响应式代理会改变WeakSet身份。
  const rows = shallowRef<PreviewRow[]>([])
  const visible = ref(!document.hidden && navigator.onLine)
  const error = ref('')
  const busy = ref(false)
  const paging = ref(false)
  const ready = ref(false)
  const latched = ref(false)
  const waitingUntil = ref<number | null | undefined>()
  let generation = 0
  let disposed = false
  let activity: 'idle' | 'full' | 'interaction' = 'idle'
  let currentPlan: DesignerCurrentPlan | undefined
  let realtime: ReturnType<typeof createDesignerRealtime> | undefined
  const realtimeState = ref<DesignerRealtimeState>('CLOSED')
  let dirtyPending = false
  let dirtyTimer: ReturnType<typeof setTimeout> | undefined
  let skipRealtimeOnce = false
  let lastFailure: unknown
  function stopRealtime(deadline?: number) {
    currentPlan = undefined
    realtime = undefined
    realtimeState.value = 'CLOSED'
    dirtyPending = false
    if (dirtyTimer !== undefined) clearTimeout(dirtyTimer)
    dirtyTimer = undefined
    // 生命周期不等待UI，但关闭义务跨卸载保留；新完整轮必须await确认。
    void closeDesignerRealtime(deadline).catch((cause: unknown) => {
      if (!disposed && !latched.value) {
        error.value = '实时连接关闭尚未确认，请重试；不会建立替代连接。'
        scheduler.fail(cause)
      }
    })
  }
  const scheduler = createPreviewScheduler({
    eligible: (kind) => designerReadEligibility(kind),
    subscribeBudget: subscribeDesignerReadBudget,
    runFull,
    onInvalidate: () => {
      generation++
      stopRealtime()
      rows.value = []
      ready.value = false
      directoryGeneration.value++
    },
    onState: (state) => {
      lastFailure = state.error
      activity = state.busy
      busy.value = state.busy === 'full' || state.fullPending
      latched.value = state.latched
      waitingUntil.value = state.waitingUntil
      if (state.latched && !error.value)
        error.value = '读取失败，自动校准已停止；请检查后刷新草稿数据。'
      scheduleDirty()
    }
  })
  const status = computed(() =>
    latched.value
      ? 'RETRY_REQUIRED'
      : waitingUntil.value !== undefined
        ? 'WAITING'
        : busy.value
          ? 'RECOVERING'
          : ready.value
            ? 'REST_READY'
            : 'IDLE'
  )
  const waitLabel = computed(() =>
    waitingUntil.value === null
      ? '等待在途读取释放额度'
      : waitingUntil.value === undefined
        ? ''
        : `读取额度预计在 ${new Date(Date.now() + Math.max(0, waitingUntil.value - performance.now())).toLocaleTimeString()} 可用`
  )
  provide(previewInteractionKey, {
    run: (key, work) =>
      scheduler.runInteraction(key, async (signal) => {
        const scope = createDesignerReadScope({ kind: 'INTERACTION' })
        const cancel = () => scope.close()
        signal.addEventListener('abort', cancel, { once: true })
        try {
          if (signal.aborted) throw new Error('读取已取消')
          return await work(scope, signal)
        } finally {
          signal.removeEventListener('abort', cancel)
          scope.close()
        }
      })
  })
  watch(
    () => [props.schema, props.pageId, props.projectId, props.available] as const,
    (next, before) => {
      const dependencyChanged = !before || next.slice(0, 3).some((value, i) => value !== before[i])
      if (dependencyChanged || !props.available) {
        selections.value = {}
        timeSelections.value = {}
      }
      if (dependencyChanged) error.value = ''
      if (!props.available || !visible.value) scheduler.suspend()
      else scheduler.resume()
      if (dependencyChanged) scheduler.dependencyChanged()
    },
    { flush: 'sync', immediate: true }
  )
  // 正常令牌轮换不改变身份代次；旧订阅立即退役，新令牌只做一次REST复核。
  const userStore = useUserStore()
  watch(
    () => userStore.accessToken,
    (token) => {
      if (disposed) return
      if (!token) {
        readDenied(new Error('预览身份已失效'))
        return
      }
      if (latched.value) {
        stopRealtime()
        return
      }
      skipRealtimeOnce = true
      scheduler.dependencyChanged()
    },
    { flush: 'sync' }
  )
  const visibility = () => {
    visible.value = !document.hidden && navigator.onLine
    if (!visible.value || !props.available) scheduler.suspend()
    else scheduler.resume()
  }
  document.addEventListener('visibilitychange', visibility)
  window.addEventListener('offline', visibility)
  window.addEventListener('online', visibility)
  onBeforeUnmount(() => {
    disposed = true
    document.removeEventListener('visibilitychange', visibility)
    window.removeEventListener('offline', visibility)
    window.removeEventListener('online', visibility)
    scheduler.dispose()
  })
  function readDenied(cause: unknown = lastFailure ?? new Error('读取权限已变化')) {
    rows.value = []
    ready.value = false
    directoryGeneration.value++
    error.value = '读取权限已变化，旧目录和预览已清除，请重新确认权限。'
    scheduler.fail(cause)
  }
  function scheduleDirty() {
    if (
      !dirtyPending ||
      dirtyTimer !== undefined ||
      disposed ||
      latched.value ||
      busy.value ||
      activity !== 'idle' ||
      !ready.value ||
      !visible.value ||
      !props.available ||
      !currentPlan ||
      realtimeState.value !== 'SUBSCRIBED'
    )
      return
    const eligibility = designerReadEligibility('INTERACTION', { current: true })
    if (eligibility.nextAvailableAt === null) return
    dirtyTimer = setTimeout(
      () => {
        dirtyTimer = undefined
        void readDirty()
      },
      Math.max(
        0,
        eligibility.nextAvailableAt - performance.now(),
        nextDesignerDirtyAt() - performance.now()
      )
    )
  }
  async function readDirty() {
    if (
      !dirtyPending ||
      disposed ||
      latched.value ||
      busy.value ||
      activity !== 'idle' ||
      !ready.value ||
      !visible.value ||
      !props.available ||
      !currentPlan ||
      !realtime ||
      realtimeState.value !== 'SUBSCRIBED'
    )
      return
    const eligibility = designerReadEligibility('INTERACTION', { current: true })
    const nextAt = nextDesignerDirtyAt()
    if (!eligibility.eligible || performance.now() < nextAt) {
      scheduleDirty()
      return
    }
    const plan = currentPlan,
      connection = realtime,
      epoch = generation,
      projectId = props.projectId
    try {
      await scheduler.runInteraction('dirty-current', async (signal) => {
        const changed = connection.takeDirty()
        dirtyPending = false
        if (!changed.length) return
        markDesignerDirtyStart()
        const scope = createDesignerReadScope({ kind: 'INTERACTION' })
        const cancel = () => scope.close()
        signal.addEventListener('abort', cancel, { once: true })
        const checkCurrent = () => {
          if (
            signal.aborted ||
            epoch !== generation ||
            disposed ||
            !props.available ||
            !visible.value
          )
            throw new Error('旧实时读取已取消')
        }
        try {
          checkCurrent()
          const next = await refreshDesignerCurrent(plan, rows.value, {
            current: (body) => fetchPreviewCurrent(projectId, body, scope),
            checkCurrent
          })
          checkCurrent()
          rows.value = next
        } catch (cause) {
          if (signal.aborted || epoch !== generation) return
          rows.value = []
          ready.value = false
          if (cause instanceof DesignerCurrentRecoveryRequired) {
            // 用真实元信息/权限重新建立计划；本次恢复跳过WS，避免拒绝→重连反馈循环。
            skipRealtimeOnce = true
            scheduler.dependencyChanged()
            return
          }
          if ([401, 403, 404].includes((cause as { status?: number })?.status ?? 0))
            readDenied(cause)
          else error.value = '当前值更新失败，自动读取已停止，请显式刷新。'
          stopRealtime()
          throw cause
        } finally {
          signal.removeEventListener('abort', cancel)
          scope.close()
        }
      })
    } catch {
      // 实际工作异常由唯一调度器锁存；尚未开始的忙拒绝保留dirty，待下一次idle再调度。
    }
    scheduleDirty()
  }
  async function pageAlarm(queryId: string, cursor: string | undefined) {
    if (!props.available || !visible.value || busy.value || paging.value) return
    const target = rows.value.find((row) => row.alarm?.query?.queryId === queryId)
    if (!target?.alarm?.query) return
    const query = target.alarm.query,
      epoch = generation,
      projectId = props.projectId
    paging.value = true
    const replace = (change: (row: PreviewRow) => PreviewRow) => {
      rows.value = rows.value.map((row) =>
        row.alarm?.query?.queryId === queryId ? change(row) : row
      )
    }
    replace((row) => ({
      ...row,
      text: '正在读取告警页',
      alarm: { ...row.alarm!, result: undefined, pageCursor: cursor }
    }))
    try {
      await scheduler.runInteraction(`alarm:${queryId}:${cursor ?? ''}`, async (signal) => {
        const scope = createDesignerReadScope({ kind: 'INTERACTION' })
        const cancel = () => scope.close()
        signal.addEventListener('abort', cancel, { once: true })
        try {
          const result = await fetchDesignerAlarms(
            projectId,
            query,
            target.componentId,
            cursor,
            scope
          )
          if (signal.aborted || epoch !== generation || !props.available || !visible.value) return
          replace((row) => ({
            ...row,
            text:
              result.status === 'READY'
                ? result.items.length
                  ? '当前页告警事实'
                  : '当前筛选没有告警'
                : '告警配置、设备模型或游标已失效',
            alarm: { ...row.alarm!, result: { ...result, componentId: row.componentId } }
          }))
        } finally {
          signal.removeEventListener('abort', cancel)
          scope.close()
        }
      })
    } catch (cause) {
      if (epoch !== generation) return
      if ([401, 403, 404].includes((cause as { status?: number })?.status ?? 0)) readDenied(cause)
      else replace((row) => ({ ...row, text: '告警页读取失败，旧页已清除，请显式重试' }))
    } finally {
      paging.value = false
    }
  }
  function refresh() {
    if (!props.available || !visible.value) return
    error.value = ''
    scheduler.requestFull(true)
  }
  async function runFull(signal: AbortSignal) {
    if (!props.available || !visible.value) throw new Error('草稿预览已不可用')
    const scope = createDesignerReadScope({
      kind: 'FULL',
      onWait: (next) => {
        waitingUntil.value = next
      }
    })
    const cancel = () => scope.close()
    signal.addEventListener('abort', cancel, { once: true })
    const epoch = generation,
      projectId = props.projectId
    const skipRealtime = skipRealtimeOnce
    skipRealtimeOnce = false
    let plan: DesignerCurrentPlan | undefined
    rows.value = []
    ready.value = false
    error.value = ''
    const checkCurrent = () => {
      if (signal.aborted || epoch !== generation || !props.available || !visible.value)
        throw new Error('stale preview')
    }
    try {
      await closeDesignerRealtime(scope.deadline)
      checkCurrent()
      const result = await loadDesignerDevicePreview(
        props.schema,
        props.pageId,
        {
          snapshots: (body) => fetchPreviewSnapshots(projectId, body, scope),
          current: (body) => fetchPreviewCurrent(projectId, body, scope),
          history: (query) => fetchDesignerHistory(projectId, query, scope),
          alarms: (query, componentId, cursor) =>
            fetchDesignerAlarms(projectId, query, componentId, cursor, scope),
          checkCurrent,
          beforeCurrentRead: async (prepared) => {
            checkCurrent()
            plan = prepared
            if (!prepared.devices.length || skipRealtime) {
              realtimeState.value = 'REST_READY'
              return
            }
            const connection = createDesignerRealtime({
              projectId,
              devices: prepared.devices,
              signal,
              deadline: scope.deadline,
              tryReserve: () => scope.tryReserveWebSocket(),
              onDirty: () => {
                if (epoch !== generation || disposed) return
                dirtyPending = true
                scheduleDirty()
              },
              onState: (state) => {
                if (epoch !== generation || disposed) return
                realtimeState.value = state
                if (state !== 'SUBSCRIBED' && state !== 'CONNECTING') {
                  dirtyPending = false
                  if (dirtyTimer !== undefined) clearTimeout(dirtyTimer)
                  dirtyTimer = undefined
                }
              },
              onRevoked: () => {
                if (epoch !== generation || disposed) return
                skipRealtimeOnce = true
                scheduler.dependencyChanged()
              },
              onFailure: (cause) => {
                if (epoch === generation && !disposed) scheduler.fail(cause)
              }
            })
            realtime = connection
            ownDesignerRealtime(connection)
            await connection.start()
            checkCurrent()
          },
          afterCurrentInvalidation: async () => {
            checkCurrent()
            plan = undefined
            stopRealtime(scope.deadline)
            await closeDesignerRealtime(scope.deadline)
            checkCurrent()
            realtimeState.value = 'REST_READY'
          },
          invalidateDirectories: () => {
            directoryGeneration.value++
          }
        },
        selections.value,
        timeSelections.value
      )
      checkCurrent()
      rows.value = result
      currentPlan = plan
      ready.value = true
    } catch (cause) {
      if (!signal.aborted && epoch === generation) {
        if ([401, 403, 404].includes((cause as { status?: number })?.status ?? 0)) readDenied(cause)
        else
          error.value =
            cause instanceof Error && cause.message.includes('预览上限')
              ? '当前页最多20台设备、200个属性或10条历史查询，请减少选择。'
              : '本轮草稿数据读取失败或不符合合同，请检查权限与模型后重试。'
      }
      throw cause
    } finally {
      signal.removeEventListener('abort', cancel)
      scope.close()
    }
  }
</script>

<style scoped>
  .preview-row {
    min-width: 0;
    margin: 12px 0;
    overflow-wrap: anywhere;
  }
  .gauge-track {
    max-width: 360px;
    height: 12px;
    overflow: hidden;
    border: 1px solid currentcolor;
    border-radius: 6px;
  }
  .gauge-fill {
    height: 100%;
    background: var(--el-color-primary);
  }
</style>
