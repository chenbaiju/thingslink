<script setup lang="ts">
import { createRealtimeController } from './runtime/realtime-controller'
import { createDirtyScheduler } from './runtime/dirty-scheduler'
import { createRecoveryBudget, type RecoveryRound } from './runtime/recovery-budget'
import { computed, onBeforeUnmount, onMounted, ref, shallowRef } from 'vue'
import { createBrowserSessionPorts, createSessionCoordinator, SessionError } from './auth/session'
import type { components } from './types/api/schema'
import RuntimeDashboard from './runtime/RuntimeDashboard.vue'
import DeviceControl from './control/DeviceControl.vue'
import { DeviceDataError, planDevicePage, loadDevicePage, loadDeviceCurrent, loadDirtyAlarms, alarmSubscription, currentSubscription, loadDeviceCatalog, loadAlarmPage, type DevicePagePlan, type DevicePageFacts, type DeviceCatalogPage, type DeviceSelection } from './runtime/device-data'
import { loadPublishedDashboard, type PublishedDashboard } from './runtime/published'
import { loadHostCandidate, type HostCandidate } from './runtime/host'

type PublicApplication = components['schemas']['WebAppApplicationResolutionResponse']
type CurrentApplication = components['schemas']['WebAppApplicationCurrentResponse']

// 限额不含身份数据，跨本页视图重开保留，避免反复进入绕过20次/分钟或并发槽。
const controlBudget = { starts: [] as number[], pending: false }
const controlMode = ref(false)
const controlView = ref<{ dispose(): void } | null>(null)
const projectKey = ref('')
const username = ref('')
const password = ref('')
const appKey = ref<string | null>(null)
const publicApplication = ref<PublicApplication | null>(null)
const currentApplication = shallowRef<CurrentApplication | null>(null)
const publishedDashboard = shallowRef<PublishedDashboard | null>(null)
const canvasError = ref('')
const activePageId = ref('')
const devicePlan = shallowRef<DevicePagePlan | null>(null)
const deviceFacts = shallowRef<DevicePageFacts | null>(null)
const deviceCatalogs = shallowRef<Readonly<Record<string, DeviceCatalogPage>>>({})
let selections: Record<string, DeviceSelection> = {}
let selectionScope = ''
let calibrationDue = 0
const canvasLoading = ref(false)
const selectedDashboard = ref<string | null>(null)
const hostCandidate = ref<HostCandidate | null>(null)
let hostLoad: Promise<HostCandidate> | undefined
let canvasRequest: AbortController | undefined
const status = ref('idle')
const sessionUser = ref<string | undefined>()
const sessionProject = ref<string | undefined>()
const busy = ref(false)
const resolving = ref(true)
const invalidRoute = ref(false)
const notice = ref('')
const switchingAccount = ref(false)
let routeGeneration = 0
let viewGeneration = 0
let activeRound: RecoveryRound | undefined
let needsCurrent = false
let pendingRecovery = false
let pendingExplicit = false
let pendingDashboard: string | undefined
const cancelledRounds = new WeakSet<RecoveryRound>()
// 同一轮跨认证等待、current重读和所有历史序列共用UTC锚点；分页沿原计划窗口。
const roundTimes = new WeakMap<RecoveryRound, string>()
let calibration: ReturnType<typeof setTimeout> | undefined
const recoveryStatus = ref('IDLE')
const budgetWait = ref('')
const failureLatch = ref(false)
const realtimeStatus = ref('CLOSED')
const alarmRealtimeNotice = ref('')
let realtimeAlarmQueries = new Map<string, string>()
let realtime: ReturnType<typeof createRealtimeController> | undefined
let coordinator: ReturnType<typeof createSessionCoordinator> | undefined
const available = () => document.visibilityState === 'visible' && navigator.onLine
const budget = createRecoveryBudget({
  origin: window.location.origin,
  fetch: (input, init) => window.fetch(input, init), now: () => performance.now(),
  setTimer: (callback, delay) => window.setTimeout(callback, delay),
  clearTimer: timer => window.clearTimeout(timer as number),
  onFailure: round => { if (activeRound === round) failRecovery() },
  onWait: (round, state) => {
    if (activeRound !== round) return
    budgetWait.value = state === null ? '' : state.nextAvailableAt === null
      ? '等待在途请求释放额度；本轮截止不延长。'
      : `预计${new Date(Date.now() + Math.max(0, state.nextAvailableAt - performance.now())).toLocaleTimeString('zh-CN', { hour12: false })}后可再申请额度；本轮截止不延长。`
  },
})
const dirtyScheduler = createDirtyScheduler({
  now: () => performance.now(),
  setTimer: (callback, delay) => window.setTimeout(callback, delay),
  clearTimer: timer => window.clearTimeout(timer as number),
  readyAt: () => !busy.value && !pendingRecovery && !failureLatch.value && available()
    && realtime?.state() === 'SUBSCRIBED' && deviceFacts.value && !switchingAccount.value
    && coordinator?.snapshot().status === 'authenticated' ? budget.nextCurrentInteractionAt() : null,
  run: refreshDirtyCurrent,
})
function closeRealtime() {
  const previous = realtime
  realtime = undefined
  previous?.close()
  dirtyScheduler.clear()
  realtimeStatus.value = 'CLOSED'
  realtimeAlarmQueries = new Map()
  alarmRealtimeNotice.value = ''
}
async function refreshDirtyCurrent() {
  const controller = realtime
  const current = currentApplication.value
  const published = publishedDashboard.value
  const plan = devicePlan.value
  const facts = deviceFacts.value
  const session = coordinator
  if (!controller || !current || !published || !plan || !facts || !session) return
  const devices = controller.takeDirty()
  const queryIds = controller.takeDirtyAlarms().map(key => realtimeAlarmQueries.get(key)!)
  if (!devices.length && !queryIds.length) return
  const view = viewGeneration
  await runRound(async round => {
    try {
      let result = devices.length ? await loadDeviceCurrent({ current, published, plan, facts, devices, session }) : facts
      budget.assertRound(round)
      session.checkCurrent()
      if (view !== viewGeneration || realtime !== controller || devicePlan.value !== plan) return
      if (queryIds.length) {
        alarmRealtimeNotice.value = '当前看板告警有变化，正在重新读取。'
        result = await loadDirtyAlarms({ current, published, plan, facts: result, queryIds, session })
      }
      budget.assertRound(round)
      session.checkCurrent()
      if (view !== viewGeneration || realtime !== controller || devicePlan.value !== plan
        || currentApplication.value !== current || publishedDashboard.value !== published) return
      deviceFacts.value = result
      if (queryIds.length) alarmRealtimeNotice.value = '当前看板告警列表已更新。'
    } catch (error) {
      if (error instanceof DeviceDataError && error.reason === 'identity') {
        cancelActiveRound()
        clearCurrent()
        requestRecovery()
        budget.assertRound(round)
        return
      }
      throw error
    }
  }, false, 'interaction')
}
function failRecovery() {
  failureLatch.value = true
  pendingRecovery = false
  pendingExplicit = false
  clearCurrent(true)
  recoveryStatus.value = 'RETRY_REQUIRED'
  notice.value = '应用恢复未完成。请稍后点击重试；自动恢复已停止。'
}
function captureFetch() {
  const round = activeRound
  return (input: string, init?: RequestInit) => round
    ? budget.fetch(round, input, init) : Promise.reject(new Error('缺少恢复轮'))
}
// 所有身份操作在进入WebLock前捕获同一轮；等待锁不能借用后续轮的新预算。
async function runRound(action: (round: RecoveryRound) => Promise<void>, explicit = false, kind: 'full' | 'interaction' = 'full') {
  if (busy.value || !available() || (failureLatch.value && !explicit)) return
  clearTimeout(calibration)
  if (explicit) failureLatch.value = false
  const round = budget.beginRound(kind)
  roundTimes.set(round, new Date().toISOString())
  activeRound = round
  busy.value = true
  needsCurrent = false
  recoveryStatus.value = 'RESTORING'
  try {
    await action(round)
    while (needsCurrent && authenticated.value && !switchingAccount.value) {
      needsCurrent = false
      budget.assertRound(round)
      await loadCurrent(round, selectedDashboard.value ?? undefined)
    }
    budget.assertRound(round)
    if (!authenticated.value) recoveryStatus.value = 'AUTH_REQUIRED'
    else if (currentApplication.value && (!selectedDashboard.value || publishedDashboard.value)) {
      recoveryStatus.value = 'READY'
      if (kind === 'full') calibrationDue = performance.now() + 60000
    }
    else throw new Error('恢复结果不完整')
  } catch (error) {
    if (cancelledRounds.has(round) || !available()) {
      if (!failureLatch.value) recoveryStatus.value = 'PAUSED'
    } else if (error instanceof SessionError && ['anonymous', 'rejected', 'unknown', 'unsupported', 'invalidated'].includes(error.reason)) {
      recoveryStatus.value = 'AUTH_REQUIRED'
      if (error.reason === 'unknown') notice.value = '会话结果待确认，请明确重新登录。'
      else if (error.reason === 'rejected') notice.value = '登录未通过，请核对账号后重新登录。'
    } else if (!failureLatch.value) failRecovery()
  } finally {
    budget.endRound(round)
    if (activeRound === round) activeRound = undefined
    busy.value = false
    budgetWait.value = ''
    resolving.value = false
    synchronize()
    if (pendingRecovery && !failureLatch.value && available()) queueMicrotask(flushRecovery)
    else if (!controlMode.value && !failureLatch.value && available() && authenticated.value && !switchingAccount.value) {
      calibration = setTimeout(() => requestRecovery(), Math.max(0, calibrationDue - performance.now()))
      dirtyScheduler.wake()
    }
  }
}
// 主动隐藏/换路由仅取消旧轮，不锁存故障；新意图等旧身份请求实际终态后才启动。
function cancelActiveRound() {
  if (!activeRound) return
  cancelledRounds.add(activeRound)
  budget.cancelRound(activeRound)
}
function requestRecovery(explicit = false, dashboardId?: string) {
  if (controlMode.value || !appKey.value || switchingAccount.value || (failureLatch.value && !explicit)) return
  if (explicit) failureLatch.value = false
  pendingRecovery = true
  pendingExplicit ||= explicit
  pendingDashboard = dashboardId ?? selectedDashboard.value ?? undefined
  if (!busy.value && available()) queueMicrotask(flushRecovery)
}
function flushRecovery() {
  if (!pendingRecovery || busy.value || !available() || failureLatch.value || !appKey.value || switchingAccount.value) return
  const explicit = pendingExplicit
  const dashboardId = pendingDashboard
  pendingRecovery = false
  pendingExplicit = false
  pendingDashboard = undefined
  void runRound(round => recoverApplication(round, dashboardId), explicit)
}

// 代次变化同步清除业务事实，不能等待下一次网络响应才隐藏旧身份的内容。
function clearCurrent(preserveError = false) {
  controlView.value?.dispose()
  controlMode.value = false
  closeRealtime()
  viewGeneration += 1
  currentApplication.value = null
  canvasRequest?.abort()
  canvasRequest = undefined
  publishedDashboard.value = null
  deviceFacts.value = null
  devicePlan.value = null
  deviceCatalogs.value = {}
  canvasLoading.value = false
  if (!preserveError) canvasError.value = ''
}
function synchronize() {
  if (!coordinator) return
  const snapshot = coordinator.snapshot()
  status.value = snapshot.status
  sessionUser.value = snapshot.appUserId
  sessionProject.value = snapshot.projectId
  if (snapshot.status !== 'authenticated') clearCurrent(true)
}
const ports = createBrowserSessionPorts(window, () => {
  clearCurrent(failureLatch.value)
  synchronize()
  scheduleCurrentRecovery()
})
ports.fetch = (input, init) => captureFetch()(input, init)
ports.captureFetch = captureFetch
coordinator = createSessionCoordinator(ports)
synchronize()
const stopSnapshot = coordinator.subscribeSnapshot((snapshot) => {
  const previousStatus = status.value
  if (snapshot.status === 'authenticated' && previousStatus === 'authenticating' && busy.value) needsCurrent = true
  status.value = snapshot.status
  sessionUser.value = snapshot.appUserId
  sessionProject.value = snapshot.projectId
  if (snapshot.status !== 'authenticated') clearCurrent(true)
  if (['invalidated', 'unknown', 'anonymous', 'disposed'].includes(snapshot.status)) resetSelections()
})

const authenticated = computed(() => status.value === 'authenticated')
const unsupported = computed(() => status.value === 'unsupported')
const unresolvedIdentity = computed(() => status.value === 'unknown')
const title = computed(() => publicApplication.value?.displayName || '连接你的设备应用')
const canLogin = computed(() => !!publicApplication.value && !!appKey.value && !busy.value && !resolving.value && !invalidRoute.value && !unsupported.value)
const sessionLabel = computed(() => {
  if (authenticated.value) return '已登录'
  if (status.value === 'authenticating') return '正在确认会话'
  if (status.value === 'invalidated') return '会话已变化'
  if (unresolvedIdentity.value) return '会话结果待确认'
  if (unsupported.value) return '浏览器暂不支持'
  return '未登录'
})

// 按后端公开合同限制解码正文；流超限立即取消，不先无界response.json再检查。
async function boundedJson(response: Response, maximum: number): Promise<unknown> {
  if (!response.body) throw new Error('响应正文缺失')
  const reader = response.body.getReader()
  const chunks: Uint8Array[] = []
  let length = 0
  try {
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      length += value.byteLength
      if (length > maximum) {
        await reader.cancel()
        throw new Error('响应正文超限')
      }
      if (value.byteLength > 0) chunks.push(value)
    }
  } finally { reader.releaseLock() }
  const body = new Uint8Array(length)
  let offset = 0
  for (const chunk of chunks) { body.set(chunk, offset); offset += chunk.byteLength }
  return JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(body)) as unknown
}

// 唯一公开应用入口不接受宽松key、尾缀或另一个origin提供的路由参数。
async function resolveApplication(round: RecoveryRound) {
  const key = appKey.value
  const generation = routeGeneration
  if (!key) return
  const response = await budget.fetch(round, `/api/v1/app/applications/${key}/resolve`, {
    method: 'GET', credentials: 'omit', cache: 'no-store', mode: 'same-origin', redirect: 'error',
    headers: { Accept: 'application/json' },
  })
  if (!response.ok) throw new Error('应用入口不可用')
  const value = await boundedJson(response, 2048) as PublicApplication
  budget.assertRound(round)
  if (generation !== routeGeneration || window.location.pathname !== `/app/${key}`) return
  if (value.appKey !== key || !value.displayName || !value.projectKey) throw new Error('公开应用合同不完整')
  publicApplication.value = value
  projectKey.value = value.projectKey
}
async function recoverApplication(round: RecoveryRound, dashboardId?: string) {
  if (!publicApplication.value) await resolveApplication(round)
  if (coordinator?.snapshot().status === 'idle') await coordinator.restore()
  synchronize()
  if (authenticated.value) {
    needsCurrent = false
    await loadCurrent(round, dashboardId)
  }
}
async function openRoute() {
  routeGeneration += 1
  cancelActiveRound()
  pendingRecovery = false
  pendingExplicit = false
  pendingDashboard = undefined
  clearCurrent()
  publicApplication.value = null
  resetSelections()
  selectedDashboard.value = null
  switchingAccount.value = false
  notice.value = ''
  password.value = ''
  const match = /^\/app\/(app_[0-9a-f]{32})$/.exec(window.location.pathname)
  invalidRoute.value = !match && window.location.pathname !== '/app/'
  appKey.value = match?.[1] ?? null
  projectKey.value = ''
  resolving.value = !!match
  if (match) requestRecovery(true)
}

// refresh只恢复会话；应用权限必须由current再次确认，不把登录成功等同于可渲染。
async function loadCurrent(round: RecoveryRound, dashboardId?: string) {
  const generation = routeGeneration
  clearCurrent()
  const key = appKey.value
  const session = coordinator
  if (!key || !session || !authenticated.value || switchingAccount.value) return
  const view = viewGeneration
  const identity = session.snapshot()
  try {
    const response = await session.fetch(`/api/v1/app/applications/${key}/current`, {
      method: 'GET', headers: { Accept: 'application/json' },
    })
    if (!response.ok) throw new Error('应用授权不可用')
    const value = await boundedJson(response, 64 * 1024) as CurrentApplication
    budget.assertRound(round)
    const latest = session.checkCurrent()
    if (generation !== routeGeneration || view !== viewGeneration || window.location.pathname !== `/app/${key}`) return
    if (latest.status !== 'authenticated' || latest.appUserId !== identity.appUserId || latest.projectId !== identity.projectId
      || value.application?.appKey !== key || value.identity?.kind !== 'APP'
      || value.identity.appUserId !== latest.appUserId || value.identity.projectId !== latest.projectId) {
      throw new Error('应用身份不匹配')
    }
    currentApplication.value = value
    notice.value = ''
    const selected = dashboardId ?? value.entryDashboardId
    if (selected && !value.dashboards.some(board => board.dashboardId === selected)) throw new Error('看板已不可访问')
    selectedDashboard.value = selected
    if (selected) await loadCanvas(value, selected, round)
  } catch {
    if (generation !== routeGeneration || view !== viewGeneration) return
    throw new Error('当前应用恢复失败')
  } finally {
    if (generation === routeGeneration) synchronize()
  }
}

// 只读取current中明确可见的精确版本；切换、失效与正文迟到共用原身份和视图围栏。
async function loadCanvas(current: CurrentApplication, dashboardId: string, round: RecoveryRound) {
  const session = coordinator
  if (!session || !current.dashboards.some(board => board.dashboardId === dashboardId)) return
  canvasRequest?.abort()
  const request = new AbortController()
  canvasRequest = request
  publishedDashboard.value = null
  canvasError.value = ''
  canvasLoading.value = true
  selectedDashboard.value = dashboardId
  const view = viewGeneration
  const route = routeGeneration
  try {
    hostLoad ??= loadHostCandidate().catch(error => {
      hostLoad = undefined
      throw error
    })
    const host = await hostLoad
    if (request.signal.aborted) return
    const published = await loadPublishedDashboard({ current, dashboardId, session,
      resources: host.resources, hostVersion: host.hostVersion, signal: request.signal })
    budget.assertRound(round)
    session.checkCurrent()
    if (request.signal.aborted || view !== viewGeneration || route !== routeGeneration
      || currentApplication.value !== current || window.location.pathname !== `/app/${current.application.appKey}`) return
    hostCandidate.value = host
    publishedDashboard.value = published
    const scope = `${current.identity.appUserId}/${current.identity.projectId}/${published.applicationVersionId}/${published.publicationRevision}/${published.dashboardVersionId}`
    if (selectionScope !== scope) { resetSelections(); selectionScope = scope }
    if (!published.schema.pages.some(page => page.id === activePageId.value)) activePageId.value = published.schema.pages[0]!.id
    const plan = planDevicePage(published.schema, activePageId.value, selections, roundTimes.get(round))
    devicePlan.value = plan
    // 组件只呈现数据；当前可见页的目录、元信息及当前值均共用已捕获的恢复轮。
    const page = published.schema.pages.find(page => page.id === activePageId.value)!
    const catalogs: Record<string, DeviceCatalogPage> = {}
    const catalogReads = new Map<string, DeviceCatalogPage>()
    for (const component of page.components) {
      if (component.kind !== 'DEVICE_SELECTOR') continue
      const variableKey = component.bindings.directory.variableKey
      const variable = published.schema.variables.find(entry => entry.key === variableKey)!
      if (variable.type !== 'DEVICE_SINGLE' && variable.type !== 'DEVICE_MULTI') throw new Error('未支持的设备变量')
      const model = published.schema.models.find(entry => entry.key === variable.modelKey)!
      const catalogKey = `${model.versionId}/${component.props.pageSize}`
      let catalog = catalogReads.get(catalogKey)
      if (!catalog) {
        catalog = await loadDeviceCatalog({ current, published, variableKey,
          limit: component.props.pageSize, session, signal: request.signal })
        catalogReads.set(catalogKey, catalog)
      }
      catalogs[component.id] = catalog
    }
    budget.assertRound(round)
    session.checkCurrent()
    if (request.signal.aborted || view !== viewGeneration || route !== routeGeneration || currentApplication.value !== current) return
    deviceCatalogs.value = catalogs
    const facts = await loadDevicePage({ current, published, plan, session, signal: request.signal,
      beforeCurrent: async (queries, available) => {
        const devices = currentSubscription(plan)
        const alarmGroups = alarmSubscription(plan)
        const availableKeys = new Set(queries.flatMap(device => device.propertyKeys.map(key => `${device.deviceId}/${key}`)))
        // 负设备不能静默缩小订阅后宣称原计划实时完整。
        if ((!devices.length && !alarmGroups.length)
          || devices.some(device => device.propertyKeys.some(key => !availableKeys.has(`${device.deviceId}/${key}`)))
          || alarmGroups.some(group => group.devices.some(device => !available.some(fact => fact.deviceId === device.deviceId && fact.expectedModelVersionId === device.expectedModelVersionId)))) { realtimeStatus.value = 'REST_READY'; return }
        realtimeAlarmQueries = new Map(alarmGroups.map(group => [group.queryKey, group.queryId]))
        const controller = createRealtimeController({
          session, version: alarmGroups.length ? 'v2' : 'v1',
          subscription: { requestId: crypto.randomUUID(), devices,
            ...(alarmGroups.length ? { alarms: alarmGroups.map(({ queryId: _queryId, ...group }) => group) } : {}),
            runtimeContext: { appKey: current.application.appKey,
              applicationVersionId: published.applicationVersionId,
              publicationRevision: published.publicationRevision,
              dashboardVersionId: published.dashboardVersionId } },
          deadline: round.deadline, now: () => performance.now(),
          setTimer: (callback, delay) => window.setTimeout(callback, delay),
          clearTimer: timer => window.clearTimeout(timer as number),
          tryReserve: () => budget.tryReserveWebSocket(round),
          onDirty: () => { if (realtime === controller && view === viewGeneration) dirtyScheduler.mark() },
          onState: state => { if (realtime === controller) realtimeStatus.value = state },
          onRevoked: () => {
            if (realtime !== controller || view !== viewGeneration) return
            cancelActiveRound()
            clearCurrent()
            requestRecovery()
          },
        })
        realtime = controller
        realtimeStatus.value = 'CONNECTING'
        await controller.start()
        budget.assertRound(round)
        if (request.signal.aborted || view !== viewGeneration) throw new Error('实时恢复已替换')
      },
    })
    budget.assertRound(round)
    session.checkCurrent()
    if (request.signal.aborted || view !== viewGeneration || route !== routeGeneration
      || currentApplication.value !== current || publishedDashboard.value !== published) return
    deviceCatalogs.value = catalogs
    deviceFacts.value = facts
  } catch (error) {
    if (request.signal.aborted || view !== viewGeneration || route !== routeGeneration) return
    publishedDashboard.value = null
    failRecovery()
    canvasError.value = error instanceof DeviceDataError && error.reason === 'response'
      ? '设备数据合同不匹配，请明确重试或联系管理员。'
      : '当前看板暂时无法显示，请重新打开应用或联系管理员。'
    throw new Error('看板恢复失败')
  } finally {
    if (canvasRequest === request) canvasLoading.value = false
  }
}
const imageResources = computed(() => (hostCandidate.value?.resources ?? []).map(resource => ({
  resourceId: resource.resourceId, digest: resource.digest, src: `/app/${resource.assetPath}`,
})))

// 同代次通知只合并current重读；在途重读共用原固定截止，不延长恢复轮。
function scheduleCurrentRecovery() {
  if (!available() || failureLatch.value || switchingAccount.value || !appKey.value
    || coordinator?.snapshot().status !== 'authenticated') return
  if (busy.value) { needsCurrent = true; return }
  requestRecovery()
}
function openDeviceControl() {
  if (busy.value || !authenticated.value || !available()) return
  clearTimeout(calibration)
  cancelActiveRound()
  clearCurrent()
  pendingRecovery = false
  needsCurrent = false
  controlMode.value = true
}
function leaveDeviceControl() {
  clearCurrent()
  requestRecovery(true)
}
function switchAccount() {
  if (busy.value) return
  clearTimeout(calibration)
  clearCurrent()
  username.value = ''
  password.value = ''
  notice.value = ''
  switchingAccount.value = true
}
async function login() {
  if (!canLogin.value || !coordinator) return
  resetSelections()
  const submittedPassword = password.value
  password.value = ''
  notice.value = ''
  clearCurrent()
  await runRound(async round => {
    await coordinator!.login({ projectKey: projectKey.value, username: username.value, password: submittedPassword })
    synchronize()
    if (authenticated.value) {
      switchingAccount.value = false
      needsCurrent = false
      await loadCurrent(round)
    }
  }, true)
}
async function logout() {
  if (!coordinator) return
  clearCurrent()
  password.value = ''
  needsCurrent = false
  await runRound(async () => { await coordinator!.logout(); synchronize() }, true)
}
function retryRecovery() { requestRecovery(true, selectedDashboard.value ?? undefined) }
function selectDashboard(current: CurrentApplication, dashboardId: string) {
  if (!current.dashboards.some(board => board.dashboardId === dashboardId)) return
  clearCurrent()
  requestRecovery(false, dashboardId)
}
function resetSelections() {
  selections = {}
  selectionScope = ''
  activePageId.value = ''
}
function selectDevice(variableKey: string, selection: DeviceSelection) {
  const previous = devicePlan.value?.selections[variableKey]
  if (status.value !== 'authenticated' || previous === selection
    || Array.isArray(previous) && Array.isArray(selection) && previous.length === selection.length
      && previous.every((id, index) => id === selection[index])) return
  selections = { ...selections, [variableKey]: Array.isArray(selection) ? [...selection] : selection }
  cancelActiveRound()
  clearCurrent()
  requestRecovery(true)
}
function changePage(pageId: string) {
  if (pageId === activePageId.value || !publishedDashboard.value?.schema.pages.some(page => page.id === pageId)) return
  selections = {}
  activePageId.value = pageId
  cancelActiveRound()
  clearCurrent()
  requestRecovery(true)
}
async function catalogPage(variableKey: string, cursor: string | undefined, componentId: string) {
  const current = currentApplication.value
  const published = publishedDashboard.value
  const session = coordinator
  if (!current || !published || !session || busy.value) return
  const page = published.schema.pages.find(page => page.id === activePageId.value)
  const selector = page?.components.find(component => component.kind === 'DEVICE_SELECTOR' && component.id === componentId && component.bindings.directory.variableKey === variableKey)
  if (selector?.kind !== 'DEVICE_SELECTOR') return
  const view = viewGeneration
  await runRound(async round => {
    const result = await loadDeviceCatalog({ current, published, variableKey, cursor, limit: selector.props.pageSize, session })
    budget.assertRound(round)
    session.checkCurrent()
    if (view !== viewGeneration || currentApplication.value !== current || publishedDashboard.value !== published) return
    deviceCatalogs.value = { ...deviceCatalogs.value, [componentId]: result }
  }, false, 'interaction')
}
// 告警翻页只替换目标组件当前页，仍在原身份/版本/计划围栏内；不重算历史窗口。
async function alarmPage(componentId: string, cursor: string | undefined) {
  const current = currentApplication.value
  const published = publishedDashboard.value
  const plan = devicePlan.value
  const session = coordinator
  if (!current || !published || !plan || !session || busy.value) return
  if (!published.schema.pages.find(page => page.id === activePageId.value)?.components.some(component => component.id === componentId && component.kind === 'ALARM_LIST')) return
  const view = viewGeneration
  await runRound(async round => {
    const result = await loadAlarmPage({ current, published, session }, componentId, cursor, plan)
    budget.assertRound(round)
    session.checkCurrent()
    if (view !== viewGeneration || currentApplication.value !== current || publishedDashboard.value !== published || devicePlan.value !== plan) return
    const facts = deviceFacts.value
    if (!facts) return
    deviceFacts.value = Object.freeze({ ...facts, alarms: Object.freeze([...facts.alarms.filter(entry => entry.componentId !== componentId), result]) })
  }, false, 'interaction')
}
function checkVisible() {
  clearCurrent(failureLatch.value)
  clearTimeout(calibration)
  cancelActiveRound()
  if (!available()) {
    if (!failureLatch.value) recoveryStatus.value = 'PAUSED'
    return
  }
  requestRecovery()
}
onMounted(() => {
  window.addEventListener('popstate', openRoute)
  document.addEventListener('visibilitychange', checkVisible)
  window.addEventListener('online', checkVisible)
  window.addEventListener('offline', checkVisible)
  void openRoute()
})
onBeforeUnmount(() => {
  routeGeneration += 1
  clearTimeout(calibration)
  pendingRecovery = false
  cancelActiveRound()
  window.removeEventListener('popstate', openRoute)
  document.removeEventListener('visibilitychange', checkVisible)
  window.removeEventListener('online', checkVisible)
  window.removeEventListener('offline', checkVisible)
  stopSnapshot()
  coordinator?.dispose()
  clearCurrent()
  password.value = ''
})
</script>

<template>
  <div class="app-shell">
    <header class="site-header">
      <a class="brand" href="/app/" aria-label="ThingsLink WebApp 应用入口">
        <span class="brand-mark" aria-hidden="true">T</span>
        <span>ThingsLink <span class="brand-product">WebApp</span></span>
      </a>
      <span class="header-note">设备应用 · 独立账号登录</span>
    </header>

    <main class="main-layout">
      <section class="intro" aria-labelledby="application-title">
        <p class="eyebrow">THINGS LINK / WEB APP</p>
        <h1 id="application-title" data-testid="application-title">{{ title }}</h1>
        <p class="intro-text">{{ appKey ? '使用此项目的账号登录，确认你的应用访问权限。' : '通过管理员提供的应用链接进入 WebApp，确认应用后再登录项目账号。' }}</p>
        <div class="intro-divider" aria-hidden="true"></div>
        <p class="intro-note">同一浏览器中的多个标签共享当前会话。切换账号或项目后，其他标签需要重新确认身份。</p>
      </section>

      <section class="surface" aria-label="登录与会话状态" :aria-busy="busy || resolving">
        <span class="session-status" data-testid="session-status" :data-status="status" role="status">
          <span class="status-dot" aria-hidden="true"></span>{{ sessionLabel }}
        </span>
        <p data-testid="recovery-status" :data-status="recoveryStatus" role="status">{{ recoveryStatus === 'RESTORING' ? '正在恢复应用…' : recoveryStatus === 'PAUSED' ? '应用已暂停，返回前台并联网后恢复。' : '' }}</p>
        <p v-if="budgetWait" data-testid="recovery-budget-wait" role="status">{{ budgetWait }}</p>
        <button v-if="failureLatch" data-testid="recovery-retry" class="secondary-button" :disabled="busy" @click="retryRecovery">重试</button>
        <p v-if="canvasError" data-testid="static-dashboard-error" class="notice notice-error" role="alert">{{ canvasError }}</p>
        <div v-if="invalidRoute" class="notice notice-warning" data-testid="application-state" role="alert">
          此应用链接格式不正确，请使用完整的应用链接进入。
        </div>
        <div v-else-if="resolving" class="notice" data-testid="application-state" role="status">正在确认应用与会话…</div>
        <div v-if="unsupported" class="notice notice-warning" role="alert">
          当前浏览器无法安全协调多标签会话。请使用较新版本的浏览器并允许站点本地存储；此入口已停止自动恢复。
        </div>
        <p v-if="notice" class="notice notice-error" role="alert" data-testid="error-message">{{ notice }}</p>

        <div v-if="!appKey && !invalidRoute && !resolving" class="notice" data-testid="application-state">
          请打开管理员提供的完整应用链接。此入口不提供独立的项目账号登录。
        </div>
        <template v-if="appKey && authenticated && !resolving && !invalidRoute && !switchingAccount">
          <h2>{{ currentApplication ? '应用访问已确认' : '登录会话已确认' }}</h2>
          <p class="surface-description">{{ currentApplication ? '你已通过当前应用的身份与访问检查。' : appKey ? '登录会话有效，尚未确认此应用的访问权限。' : '当前账号已登录。请使用完整应用链接继续。' }}</p>
          <span data-testid="session-user" :data-user-id="sessionUser" hidden></span>
          <span data-testid="session-project" :data-project-id="sessionProject" hidden></span>
          <p v-if="currentApplication" class="application-facts">
            当前应用：<strong>{{ currentApplication.application.displayName }}</strong><br>
            发布版本：{{ currentApplication.applicationVersionNumber }}
          </p>
          <div v-if="!currentApplication" class="notice notice-warning" data-testid="application-state">
            此应用的访问权限尚未确认。
          </div>
          <div class="action-row">
            <button v-if="!controlMode" class="secondary-button" type="button" data-testid="device-control-open" :disabled="busy" @click="openDeviceControl">设备控制</button>
            <button class="secondary-button" type="button" data-testid="switch-account-button" :disabled="busy" @click="switchAccount">切换账号</button>
            <button class="secondary-button" type="button" data-testid="logout-button" :disabled="busy" @click="logout">退出登录</button>
          </div>
        </template>

        <form v-else-if="!invalidRoute && appKey && publicApplication && !resolving" data-testid="login-form" @submit.prevent="login">
          <h2>{{ switchingAccount ? '切换项目账号' : '登录项目账号' }}</h2>
          <p class="surface-description">{{ switchingAccount ? '项目已由应用链接确定。输入新账号登录后，将替换当前浏览器会话。' : '项目已由应用链接确定。' }}</p>
          <div class="form-field">
            <label for="project-key">项目标识</label>
            <input id="project-key" v-model="projectKey" data-testid="project-key" name="projectKey" autocomplete="off" maxlength="64" required :readonly="!!publicApplication" :disabled="busy || unsupported" />
          </div>
          <div class="form-field">
            <label for="username">用户名</label>
            <input id="username" v-model="username" data-testid="username" name="username" autocomplete="username" maxlength="64" required :disabled="busy || unsupported" />
          </div>
          <div class="form-field">
            <label for="password">密码</label>
            <input id="password" v-model="password" data-testid="password" name="password" type="password" autocomplete="current-password" maxlength="128" required :disabled="busy || unsupported" />
          </div>
          <button class="primary-button" type="submit" data-testid="login-submit" :disabled="!canLogin">{{ busy ? '正在登录…' : '登录并继续' }}</button>
          <p class="form-footer">请使用项目终端用户账号。控制台账号不能用于此入口。</p>
        </form>
      </section>
    </main>

    <DeviceControl v-if="controlMode && authenticated && coordinator && !switchingAccount" ref="controlView" :session="coordinator" :budget="controlBudget" @back="leaveDeviceControl" />
    <section v-if="!controlMode && currentApplication && authenticated && !switchingAccount" class="runtime-surface" aria-label="已发布看板">
      <nav v-if="currentApplication.dashboards.length > 1 || !currentApplication.entryDashboardId" class="dashboard-navigation" aria-label="看板导航">
        <button v-for="board in currentApplication.dashboards" :key="board.dashboardId" type="button"
          data-testid="dashboard-navigation" :aria-current="selectedDashboard === board.dashboardId ? 'page' : undefined"
          :disabled="busy" @click="selectDashboard(currentApplication, board.dashboardId)">{{ board.title }}</button>
      </nav>
      <p v-if="devicePlan?.alarmQueries.length" data-testid="alarm-realtime-status" :data-status="realtimeStatus" role="status">当前看板范围的告警同步。{{ alarmRealtimeNotice || (realtimeStatus === 'SUBSCRIBED' ? '已连接，告警变化后自动更新列表。' : '实时连接暂不可用，将定期刷新列表。') }}</p>
      <p data-testid="realtime-status" :data-status="realtimeStatus" role="status">{{ realtimeStatus === 'SUBSCRIBED' ? '实时提示已连接，数据以权威读取结果为准。' : realtimeStatus === 'REST_READY' ? '实时暂不可用，当前数据已读取；保持页面可见时定期校准。' : realtimeStatus === 'CONNECTING' ? '正在连接实时提示…' : '' }}</p>
      <p v-if="canvasLoading" role="status">正在读取看板…</p>
      <RuntimeDashboard v-if="publishedDashboard" :schema="publishedDashboard.schema" :resources="imageResources"
        :active-page-id="activePageId" :plan="devicePlan" :facts="deviceFacts" :catalogs="deviceCatalogs" :loading="canvasLoading || busy" :selection-disabled="!authenticated"
        @select-device="selectDevice" @catalog-page="catalogPage" @alarm-page="alarmPage" @page-change="changePage" />
      <p v-else-if="!canvasLoading && !canvasError && !currentApplication.entryDashboardId" class="notice">暂无可访问的入口看板。</p>
    </section>
    <footer class="site-footer">
      <span>ThingsLink WebApp</span>
      <span>安全访问你的设备应用</span>
    </footer>
  </div>
</template>
