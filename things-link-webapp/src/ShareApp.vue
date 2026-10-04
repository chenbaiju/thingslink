<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, shallowRef } from 'vue'
import RuntimeDashboard from './runtime/RuntimeDashboard.vue'
import { captureShareEntry, type ShareEntry } from './share/entry'
import type { ShareSession } from './share/session'
import type { SessionSocket } from './auth/session'
import { createRecoveryBudget, type RecoveryRound } from './runtime/recovery-budget'
import { createRealtimeController } from './runtime/realtime-controller'
import { createDirtyScheduler } from './runtime/dirty-scheduler'
import { loadHostCandidate, type HostCandidate } from './runtime/host'
import { loadShareContext, loadSharePublished, type ShareContext, type SharePublishedDashboard } from './runtime/share-published'
import { DeviceDataError, planDevicePage, currentSubscription, loadDevicePage, loadDeviceCurrent, loadDeviceCatalog, loadAlarmPage,
  type DevicePageFacts, type DevicePagePlan, type DeviceCatalogPage, type DeviceSelection } from './runtime/device-data'

const props = defineProps<{ entry: ShareEntry }>()
const state = ref('IDLE')
const notice = ref('')
const budgetWait = ref('')
const busy = ref(false)
const failed = ref(false)
const realtimeState = ref('CLOSED')
const context = shallowRef<ShareContext | null>(null)
const published = shallowRef<SharePublishedDashboard | null>(null)
const plan = shallowRef<DevicePagePlan | null>(null)
const facts = shallowRef<DevicePageFacts | null>(null)
const catalogs = shallowRef<Readonly<Record<string, DeviceCatalogPage>>>({})
const host = shallowRef<HostCandidate | null>(null)
const activePage = ref('')
let session: ShareSession | undefined
let activeRound: RecoveryRound | undefined
let request: AbortController | undefined
let realtime: ReturnType<typeof createRealtimeController> | undefined
let selections: Record<string, DeviceSelection> = {}
let selectionScope = ''
let generation = 0
let pendingFull = false
let calibrationDue = 0
let calibration: ReturnType<typeof setTimeout> | undefined
let expiry: ReturnType<typeof setTimeout> | undefined
let hostLoad: Promise<HostCandidate> | undefined
// 同一导航可能依次派发popstate/hashchange，fragment只允许捕获一次。
let lastRouteHref = window.location.href
const cancelled = new WeakSet<RecoveryRound>()
const available = () => document.visibilityState === 'visible' && navigator.onLine
const budget = createRecoveryBudget({ origin: window.location.origin,
  fetch: (input, init) => window.fetch(input, init), now: () => performance.now(),
  setTimer: (callback, delay) => window.setTimeout(callback, delay), clearTimer: timer => window.clearTimeout(timer as number),
  onFailure: round => { if (activeRound === round) fail() },
  onWait: (round, value) => {
    if (activeRound !== round) return
    budgetWait.value = value === null ? '' : value.nextAvailableAt === null
      ? '正在等待已有请求释放额度；本轮截止不延长。'
      : `预计${new Date(Date.now() + Math.max(0, value.nextAvailableAt - performance.now())).toLocaleTimeString('zh-CN', { hour12: false })}后可再申请额度；本轮截止不延长。`
  },
})
const dirty = createDirtyScheduler({ now: () => performance.now(),
  setTimer: (callback, delay) => window.setTimeout(callback, delay), clearTimer: timer => window.clearTimeout(timer as number),
  readyAt: () => !busy.value && !pendingFull && !failed.value && available() && facts.value
    && realtime?.state() === 'SUBSCRIBED' ? budget.nextCurrentInteractionAt() : null,
  run: async () => {
    const connection = realtime
    const scope = context.value
    const board = published.value
    const original = plan.value
    const previous = facts.value
    const identity = session
    if (!connection || !scope || !board || !original || !previous || !identity) return
    const devices = connection.takeDirty()
    if (!devices.length) return
    const currentGeneration = generation
    await run(async round => {
      try {
        const result = await loadDeviceCurrent({ kind: 'share', context: scope, published: board, plan: original,
          facts: previous, devices, session: identity })
        budget.assertRound(round)
        identity.checkCurrent()
        if (currentGeneration === generation && realtime === connection && context.value === scope
          && published.value === board && plan.value === original) facts.value = result
      } catch (error) {
        if (error instanceof DeviceDataError && error.reason === 'identity') {
          cancel(); clearView(); recover(); budget.assertRound(round); return
        }
        throw error
      }
    }, 'interaction')
  },
})
function transport() {
  const round = activeRound
  return (input: string, init?: RequestInit) => round
    ? budget.fetch(round, input, init) : Promise.reject(new Error('缺少分享恢复轮'))
}
function clearView() {
  generation++
  request?.abort(); request = undefined
  const old = realtime; realtime = undefined; old?.close(); dirty.clear()
  clearTimeout(expiry)
  context.value = null; published.value = null; plan.value = null; facts.value = null; catalogs.value = {}
  realtimeState.value = 'CLOSED'; budgetWait.value = ''
}
function cancel() { if (activeRound) { cancelled.add(activeRound); budget.cancelRound(activeRound) } }
function fail() {
  failed.value = true; pendingFull = false; clearView(); clearTimeout(calibration)
  state.value = 'RETRY_REQUIRED'
  notice.value = '分享访问未完成或已不可用。请重新打开原分享链接，或明确重试；浏览器须允许同源来源信息。'
}
async function run(action: (round: RecoveryRound) => Promise<void>, kind: 'full' | 'interaction' = 'full') {
  if (busy.value || !available() || failed.value || !session) return
  const installedSession = session
  clearTimeout(calibration)
  const round = budget.beginRound(kind); activeRound = round; busy.value = true; state.value = 'RESTORING'
  try {
    await action(round)
    budget.assertRound(round)
    if (!context.value || !published.value || !facts.value) throw new Error('分享恢复不完整')
    state.value = 'READY'; notice.value = ''
    if (kind === 'full') calibrationDue = performance.now() + 60000
  } catch {
    // 路由安装已换代时，旧轮只负责释放调度占用，不得覆盖新入口或其失败状态。
    if (session === installedSession) {
      if (cancelled.has(round) || !available()) { if (!failed.value) state.value = 'PAUSED' }
      else if (!failed.value) fail()
    }
  } finally {
    budget.endRound(round); if (activeRound === round) activeRound = undefined; busy.value = false
    if (pendingFull && !failed.value && available()) queueMicrotask(flush)
    else if (!failed.value && session && available() && published.value) {
      calibration = setTimeout(() => recover(), Math.max(0, calibrationDue - performance.now()))
      dirty.wake()
    }
  }
}
function recover(explicit = false) {
  if (!session || failed.value && !explicit) return
  if (explicit) { failed.value = false; notice.value = '' }
  pendingFull = true
  if (!busy.value && available()) queueMicrotask(flush)
}
function flush() {
  if (!pendingFull || busy.value || failed.value || !available()) return
  pendingFull = false
  void run(loadPage)
}
async function loadPage(round: RecoveryRound) {
  clearView()
  const identity = session!
  const view = generation
  const controller = new AbortController(); request = controller
  const scope = await loadShareContext({ shareId: identity.shareId, session: identity, signal: controller.signal })
  hostLoad ??= loadHostCandidate().catch(error => { hostLoad = undefined; throw error })
  const candidate = await hostLoad
  const board = await loadSharePublished({ context: scope, session: identity, signal: controller.signal,
    resources: candidate.resources, hostVersion: candidate.hostVersion })
  budget.assertRound(round); identity.checkCurrent()
  if (controller.signal.aborted || view !== generation || session !== identity) return
  host.value = candidate
  const key = `${identity.shareId}/${board.dashboardVersionId}`
  if (selectionScope !== key) { selections = {}; activePage.value = ''; selectionScope = key }
  if (!board.schema.pages.some(page => page.id === activePage.value)) activePage.value = board.schema.pages[0]!.id
  const selected = planDevicePage(board.schema, activePage.value, selections, scope.historyAnchorAt)
  context.value = scope; published.value = board; plan.value = selected
  const read = { kind: 'share' as const, context: scope, published: board, session: identity, signal: controller.signal }
  const pages: Record<string, DeviceCatalogPage> = {}
  const reads = new Map<string, DeviceCatalogPage>()
  for (const component of board.schema.pages.find(page => page.id === selected.pageId)!.components) {
    if (component.kind !== 'DEVICE_SELECTOR') continue
    const variableKey = component.bindings.directory.variableKey
    const catalogKey = `${variableKey}/${component.props.pageSize}`
    let result = reads.get(catalogKey)
    if (!result) { result = await loadDeviceCatalog({ ...read, variableKey, limit: component.props.pageSize }); reads.set(catalogKey, result) }
    pages[component.id] = result
  }
  budget.assertRound(round)
  if (controller.signal.aborted || view !== generation) return
  catalogs.value = pages
  const result = await loadDevicePage({ ...read, plan: selected, beforeCurrent: async queries => {
    const devices = currentSubscription(selected)
    const keys = new Set(queries.flatMap(device => device.propertyKeys.map(key => `${device.deviceId}/${key}`)))
    if (!devices.length || devices.some(device => device.propertyKeys.some(key => !keys.has(`${device.deviceId}/${key}`)))) {
      realtimeState.value = 'REST_READY'; return
    }
    const connection = createRealtimeController({ kind: 'share', session: identity,
      subscription: { requestId: crypto.randomUUID(), devices }, deadline: round.deadline, now: () => performance.now(),
      setTimer: (callback, delay) => window.setTimeout(callback, delay), clearTimer: timer => window.clearTimeout(timer as number),
      tryReserve: () => budget.tryReserveWebSocket(round),
      onState: value => { if (realtime === connection) realtimeState.value = value },
      onDirty: () => { if (realtime === connection && view === generation) dirty.mark() },
      onRevoked: () => { if (realtime === connection && view === generation) { cancel(); clearView(); recover() } },
    })
    realtime = connection; realtimeState.value = 'CONNECTING'
    await connection.start(); budget.assertRound(round)
  } })
  budget.assertRound(round); identity.checkCurrent()
  if (controller.signal.aborted || view !== generation || session !== identity) return
  // 服务器锚点到到期时间的间隔从原轮起算，不能把网络等待加回能力有效期。
  const expiryDue = round.deadline - 30000 + Date.parse(scope.expiresAt) - Date.parse(scope.historyAnchorAt)
  const remaining = expiryDue - performance.now()
  if (remaining <= 0) { expire(); return }
  facts.value = result
  expiry = setTimeout(expire, Math.min(2147483647, remaining))
}
function expire() {
  cancel(); fail()
  notice.value = '分享有效期已结束或需要重新确认，请重新打开原分享链接或明确重试。'
}
function selectDevice(key: string, value: DeviceSelection) {
  if (!session || failed.value) return
  selections = { ...selections, [key]: Array.isArray(value) ? [...value] : value }
  cancel(); clearView(); recover(true)
}
function changePage(id: string) {
  if (!published.value?.schema.pages.some(page => page.id === id) || id === activePage.value) return
  activePage.value = id; selections = {}; cancel(); clearView(); recover(true)
}
async function catalogPage(variableKey: string, cursor: string | undefined, componentId: string) {
  const scope = context.value; const board = published.value; const identity = session; const view = generation
  if (!scope || !board || !identity || busy.value) return
  const component = board.schema.pages.find(page => page.id === activePage.value)?.components.find(item => item.id === componentId)
  if (component?.kind !== 'DEVICE_SELECTOR' || component.bindings.directory.variableKey !== variableKey) return
  await run(async round => {
    const result = await loadDeviceCatalog({ kind: 'share', context: scope, published: board, session: identity,
      variableKey, cursor, limit: component.props.pageSize })
    budget.assertRound(round); identity.checkCurrent()
    if (view === generation && context.value === scope && published.value === board) catalogs.value = { ...catalogs.value, [componentId]: result }
  }, 'interaction')
}
async function alarmPage(componentId: string, cursor: string | undefined) {
  const scope = context.value; const board = published.value; const identity = session; const original = plan.value; const view = generation
  if (!scope || !board || !identity || !original || busy.value) return
  await run(async round => {
    const result = await loadAlarmPage({ kind: 'share', context: scope, published: board, session: identity }, componentId, cursor, original)
    budget.assertRound(round); identity.checkCurrent()
    if (view === generation && context.value === scope && published.value === board && facts.value) {
      facts.value = Object.freeze({ ...facts.value, alarms: Object.freeze([...facts.value.alarms.filter(item => item.componentId !== componentId), result]) })
    }
  }, 'interaction')
}
function install(entry: ShareEntry) {
  cancel(); clearView(); session?.dispose(); session = undefined; pendingFull = false
  selections = {}; selectionScope = ''; activePage.value = ''; failed.value = false
  if (entry.status !== 'ready') {
    state.value = entry.status === 'missing' ? 'MISSING_LINK' : 'INVALID_LINK'
    notice.value = entry.status === 'missing' ? '请重新打开包含访问凭据的原分享链接。刷新页面不会恢复分享凭据。' : '分享链接不完整或格式不正确，请使用原分享链接。'
    return
  }
  session = entry.createSession({ origin: window.location.origin, fetch: (input, init) => transport()(input, init), captureFetch: transport,
    createSocket: (url, protocols) => new WebSocket(url, protocols) as unknown as SessionSocket })
  recover()
}
function routeChanged() {
  if (window.location.href === lastRouteHref) return
  if (!window.location.pathname.startsWith('/app/share/')) { cancel(); clearView(); session?.dispose(); window.location.reload(); return }
  const entry = captureShareEntry({ href: window.location.href, replaceState: url => window.history.replaceState(null, '', url) })
  lastRouteHref = window.location.href
  install(entry)
}
function visibilityChanged() {
  cancel(); clearView(); clearTimeout(calibration)
  if (!available()) { if (!failed.value) state.value = 'PAUSED'; return }
  recover()
}
const resources = computed(() => (host.value?.resources ?? []).map(item => ({ resourceId: item.resourceId, digest: item.digest, src: `/app/${item.assetPath}` })))
onMounted(() => {
  window.addEventListener('popstate', routeChanged); window.addEventListener('hashchange', routeChanged)
  window.addEventListener('online', visibilityChanged); window.addEventListener('offline', visibilityChanged)
  document.addEventListener('visibilitychange', visibilityChanged)
  install(props.entry)
})
onBeforeUnmount(() => {
  cancel(); clearView(); clearTimeout(calibration); session?.dispose(); pendingFull = false
  window.removeEventListener('popstate', routeChanged); window.removeEventListener('hashchange', routeChanged)
  window.removeEventListener('online', visibilityChanged); window.removeEventListener('offline', visibilityChanged)
  document.removeEventListener('visibilitychange', visibilityChanged)
})
</script>

<template>
  <div class="app-shell">
    <header class="site-header"><a class="brand" href="/app/">ThingsLink WebApp</a><span>只读分享</span></header>
    <main class="runtime-surface" aria-label="分享看板">
      <h1>分享看板</h1>
      <p data-testid="share-status" :data-status="state" role="status">{{ notice || (state === 'RESTORING' ? '正在确认分享权限并读取数据…' : state === 'PAUSED' ? '分享已暂停，联网并返回页面后重新确认。' : '分享访问已确认。') }}</p>
      <p v-if="budgetWait" data-testid="budget-wait" role="status">{{ budgetWait }}</p>
      <button v-if="failed" data-testid="share-retry" type="button" class="secondary-button" :disabled="busy" @click="recover(true)">重试</button>
      <p v-if="published" data-testid="realtime-status" :data-status="realtimeState" role="status">{{ realtimeState === 'SUBSCRIBED' ? '实时提示已连接，数据以权威读取结果为准。' : realtimeState === 'REST_READY' ? '实时暂不可用，当前数据已读取；保持页面可见时定期校准。' : realtimeState === 'CONNECTING' ? '正在连接实时提示…' : '' }}</p>
      <RuntimeDashboard v-if="published" :schema="published.schema" :resources="resources" :plan="plan" :facts="facts"
        :catalogs="catalogs" :active-page-id="activePage" :loading="busy" :selection-disabled="failed"
        @select-device="selectDevice" @page-change="changePage" @catalog-page="catalogPage" @alarm-page="alarmPage" />
    </main>
  </div>
</template>
