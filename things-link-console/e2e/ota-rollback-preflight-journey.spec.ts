import { expect, test, type Locator, type Page, type Response } from '@playwright/test'
import { createHash } from 'node:crypto'
import { execFileSync } from 'node:child_process'
import { readFile, realpath } from 'node:fs/promises'
import { isAbsolute, relative, resolve } from 'node:path'
import type { components } from '../src/types/api/schema'
import { login, enterProject, OWNER_EMAIL, OWNER_PASSWORD } from './helpers'

type Preflight = components['schemas']['OtaRollbackPreflightResponse']
type JobDetail = components['schemas']['OtaDeviceJobDetailResponse']
type Disposition = Preflight['observedDisposition']
interface JobFixture {
  deviceId: string
  deviceKey: string
  campaignId: string
  jobId: string
  attemptNo: number
  recoveryRevision: string
  expectedDisposition: Disposition
  expectedReason: string
  preflight: Preflight
}
interface Fixture {
  projectId: string
  tenantId: string
  projectName: string
  alternateProjectId: string
  alternateProjectName: string
  deviceTypeId: string
  firmwareId: string
  preparedAt: string
  jobs: JobFixture[]
}

// 设备密钥与下载响应由准备进程持有，浏览器只读公开DTO。
test.use({ trace: 'off', video: 'off', screenshot: 'off' })
test.afterEach(async ({ page }) => {
  await page.close()
})
async function ownedPath(path: string) {
  const root = await realpath(resolve('logs'))
  const target = await realpath(resolve(path))
  const suffix = relative(root, target)
  if (!suffix || isAbsolute(suffix) || suffix === '..' || suffix.startsWith('../'))
    throw new Error('回退准备材料必须位于独占忽略目录')
  return target
}
async function checkRuntime(baseURL: string) {
  const runtime = JSON.parse(
    await readFile(await ownedPath(process.env.E2E_OTA_ROLLBACK_PREFLIGHT_RUNTIME!), 'utf8')
  )
  const origin = new URL(baseURL)
  if (
    !/^tc_console_012a_[A-Za-z0-9_]+$/.test(runtime.database) ||
    runtime.postgresContainer !== 'tc-console-012a-pg' ||
    runtime.postgresPort !== 5547 ||
    runtime.redisDatabase !== 14 ||
    runtime.backendPort !== 8088 ||
    runtime.vitePort !== 3017 ||
    runtime.mqttPort !== 51883 ||
    runtime.mqttTlsPort !== 58883 ||
    runtime.dashboardPort !== 58083 ||
    runtime.kafkaPort !== 59092 ||
    runtime.apiBase !== 'http://127.0.0.1:8088' ||
    !Number.isSafeInteger(runtime.pid) ||
    runtime.pid <= 1 ||
    !/^[0-9a-f]{64}$/.test(runtime.jarSha256) ||
    origin.protocol !== 'http:' ||
    !['localhost', '127.0.0.1'].includes(origin.hostname) ||
    origin.port !== '3017'
  )
    throw new Error('拒绝非独占MQTT回退准备运行栈')
  const jar = await ownedPath(runtime.frozenJar)
  if (
    createHash('sha256')
      .update(await readFile(jar))
      .digest('hex') !== runtime.jarSha256
  )
    throw new Error('回退准备冻结候选摘要已漂移')
  try {
    const command = execFileSync('ps', ['-p', String(runtime.pid), '-o', 'command='], {
      encoding: 'utf8'
    })
    const listener = execFileSync(
      'lsof',
      ['-nP', '-a', '-p', String(runtime.pid), '-iTCP:8088', '-sTCP:LISTEN', '-t'],
      { encoding: 'utf8' }
    )
    if (!command.includes(jar) || listener.trim() !== String(runtime.pid))
      throw new Error('owner mismatch')
  } catch {
    throw new Error('回退准备8088进程所有权不符')
  }
}
function validateFixture(value: Fixture) {
  const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
  const fields = [
    'queryId',
    'reportId',
    'observedDisposition',
    'observedReason',
    'currentDisposition',
    'currentReason',
    'observedAt',
    'queryExpiresAt',
    'checkedAt',
    'operationRevision',
    'sourceSlot',
    'targetSlot',
    'executionAuthorized',
    'requiresAtomicCommitFence'
  ]
    .sort()
    .join(',')
  if (
    ![
      value.projectId,
      value.tenantId,
      value.deviceTypeId,
      value.firmwareId,
      value.alternateProjectId
    ].every((id) => typeof id === 'string' && uuid.test(id)) ||
    value.projectId === value.alternateProjectId ||
    !value.projectName ||
    !value.alternateProjectName ||
    !Array.isArray(value.jobs) ||
    value.jobs.length !== 3 ||
    [...value.jobs.map((job) => job.expectedDisposition)].sort().join(',') !==
      'INELIGIBLE,PREPARABLE,UNKNOWN'
  )
    throw new Error('缺少真实三分类及独立项目公开夹具')
  for (const job of value.jobs) {
    if (
      ![
        job.deviceId,
        job.campaignId,
        job.jobId,
        job.preflight?.queryId,
        job.preflight?.reportId
      ].every((id) => uuid.test(id)) ||
      job.attemptNo !== 1 ||
      !/^[1-9][0-9]*$/.test(job.recoveryRevision) ||
      Object.keys(job.preflight).sort().join(',') !== fields ||
      job.preflight.observedDisposition !== job.expectedDisposition ||
      job.preflight.currentDisposition !== job.expectedDisposition ||
      job.preflight.observedReason !== job.expectedReason ||
      job.preflight.currentReason !== job.expectedReason ||
      job.preflight.executionAuthorized !== false ||
      job.preflight.requiresAtomicCommitFence !== true ||
      !Number.isFinite(Date.parse(job.preflight.queryExpiresAt))
    )
      throw new Error('公开回退准备夹具不符合真实DTO')
  }
}
function endpoint(fixture: Fixture, job: JobFixture) {
  return `/api/v1/projects/${fixture.projectId}/ota/campaigns/${job.campaignId}/jobs/${job.jobId}`
}
function readResponse(page: Page, path: string) {
  return page.waitForResponse(
    (response) => new URL(response.url()).pathname === path && response.request().method() === 'GET'
  )
}
async function readProjection(response: Response) {
  expect(response.status()).toBe(200)
  expect(response.headers()['cache-control']).toContain('no-store')
  return (await response.json()) as Preflight
}
async function fetchJob(page: Page, path: string) {
  return page.evaluate(async (url) => {
    const modulePath = '/src/utils/http/index.ts'
    const http = (await import(modulePath)).default
    return http.get({ url, showErrorMessage: false })
  }, path) as Promise<JobDetail>
}
function unchangedObservation(actual: Preflight, original: Preflight) {
  for (const field of [
    'queryId',
    'reportId',
    'observedDisposition',
    'observedReason',
    'observedAt',
    'queryExpiresAt',
    'operationRevision',
    'sourceSlot',
    'targetSlot'
  ] as const)
    expect(actual[field]).toBe(original[field])
  expect(actual.executionAuthorized).toBe(false)
  expect(actual.requiresAtomicCommitFence).toBe(true)
  expect(Date.parse(actual.checkedAt)).toBeGreaterThanOrEqual(Date.parse(original.checkedAt))
}
async function expectPanel(panel: Locator, value: Preflight) {
  const labels = { PREPARABLE: '可准备', INELIGIBLE: '不可准备', UNKNOWN: '未知' }
  await expect(panel.getByTestId('ota-rollback-preflight-result')).toBeVisible()
  await expect(panel.getByTestId('ota-rollback-observed-disposition')).toHaveText(
    labels[value.observedDisposition]
  )
  await expect(panel.getByTestId('ota-rollback-current-disposition')).toHaveText(
    labels[value.currentDisposition]
  )
  await expect(panel.getByTestId('ota-rollback-observed-reason')).toContainText(
    value.observedReason
  )
  await expect(panel.getByTestId('ota-rollback-current-reason')).toContainText(value.currentReason)
  await expect(panel.getByTestId('ota-rollback-observed-at')).toHaveText(value.observedAt)
  await expect(panel.getByTestId('ota-rollback-checked-at')).toHaveText(value.checkedAt)
  await expect(panel.getByTestId('ota-rollback-query-expiry')).toHaveText(value.queryExpiresAt)
  await expect(panel.getByTestId('ota-rollback-execution-authorized')).toHaveText(
    'false（无执行权）'
  )
  await expect(panel.getByTestId('ota-rollback-atomic-fence')).toHaveText('true')
  await expect(panel).toContainText(value.queryId)
  await expect(panel).toContainText(value.reportId)
  await expect(panel.getByRole('button')).toHaveCount(1)
  await expect(panel.getByRole('button', { name: '刷新回退准备观察', exact: true })).toBeVisible()
}

test('OTA真实MQTT回退准备三分类、只读刷新及原查询自然陈旧', async ({ page }, testInfo) => {
  test.setTimeout(180_000)
  test.skip(
    !process.env.E2E_OTA_ROLLBACK_PREFLIGHT_RUNTIME ||
      !process.env.E2E_OTA_ROLLBACK_PREFLIGHT_FIXTURE,
    '需显式独占Broker/Kafka及真实认证设备生成的公开回退准备材料'
  )
  await checkRuntime(testInfo.project.use.baseURL ?? '')
  const fixture = JSON.parse(
    await readFile(await ownedPath(process.env.E2E_OTA_ROLLBACK_PREFLIGHT_FIXTURE!), 'utf8')
  ) as Fixture
  validateFixture(fixture)
  const preparable = fixture.jobs.find((job) => job.expectedDisposition === 'PREPARABLE')!
  if (Date.parse(preparable.preflight.queryExpiresAt) - Date.now() < 20_000)
    throw new Error('真实PREPARABLE查询窗口不足；需新受控设备准备，不能改钟或伪造观察')
  await login(page, OWNER_EMAIL, OWNER_PASSWORD)
  await enterProject(page, fixture.projectName)
  const identity = await page.evaluate(async () => {
    const modulePath = '/src/store/modules/user.ts'
    const user = (await import(modulePath)).useUserStore()
    return { projectId: user.info.currentProjectId, tenantId: user.info.tenantId }
  })
  expect(identity).toEqual({ projectId: fixture.projectId, tenantId: fixture.tenantId })
  let otaMutations = 0,
    preflightReads = 0
  page.on('request', (request) => {
    const path = new URL(request.url()).pathname
    if (path.includes('/ota/') && !['GET', 'HEAD', 'OPTIONS'].includes(request.method()))
      otaMutations++
    if (path.endsWith('/rollback-preflight') && request.method() === 'GET') preflightReads++
  })
  const campaignsResponse = readResponse(
    page,
    `/api/v1/projects/${fixture.projectId}/ota/campaigns`
  )
  await page.goto('/#/ota/jobs')
  const campaignPage = (await (await campaignsResponse).json()) as { items: { id: string }[] }
  // UUIDv7活动可能共享前8位标签。按真实GET顺序选对应ElOption，再验证实际GET范围和设备行。
  async function openJob(job: JobFixture) {
    const index = campaignPage.items.findIndex((item) => item.id === job.campaignId)
    expect(index >= 0).toBe(true)
    await page.getByRole('combobox', { name: '所属活动' }).click()
    const options = page.getByRole('option')
    await expect(options).toHaveCount(campaignPage.items.length)
    const jobsRead = readResponse(
      page,
      `/api/v1/projects/${fixture.projectId}/ota/campaigns/${job.campaignId}/jobs`
    )
    await options.nth(index).click()
    expect((await jobsRead).status()).toBe(200)
    const row = page.locator('.ota-jobs tbody tr').filter({ hasText: job.deviceId })
    await expect(row).toHaveCount(1)
    const projectionRead = readResponse(page, endpoint(fixture, job) + '/rollback-preflight')
    await row.getByTestId('ota-job-detail-open').click()
    const drawer = page.getByRole('dialog', { name: '作业详情' })
    await expect(drawer).toBeVisible()
    return {
      drawer,
      panel: drawer.getByTestId('ota-rollback-preflight'),
      projection: await readProjection(await projectionRead)
    }
  }
  const observedOrder = [...fixture.jobs].sort(
    (a, b) =>
      Number(a.expectedDisposition === 'PREPARABLE') -
      Number(b.expectedDisposition === 'PREPARABLE')
  )
  for (const job of observedOrder) {
    const before = await fetchJob(page, endpoint(fixture, job))
    expect(before.status).toBe('RECOVERY_REQUIRED')
    expect(before.stateVersion).toBe(job.recoveryRevision)
    const opened = await openJob(job)
    unchangedObservation(opened.projection, job.preflight)
    expect(opened.projection.currentDisposition).toBe(job.expectedDisposition)
    expect(opened.projection.currentReason).toBe(job.expectedReason)
    await expectPanel(opened.panel, opened.projection)
    const refreshRead = readResponse(page, endpoint(fixture, job) + '/rollback-preflight')
    await opened.panel.getByTestId('ota-rollback-preflight-refresh').click()
    const refreshed = await readProjection(await refreshRead)
    unchangedObservation(refreshed, opened.projection)
    expect(refreshed.currentDisposition).toBe(job.expectedDisposition)
    await expectPanel(opened.panel, refreshed)
    expect(await fetchJob(page, endpoint(fixture, job))).toEqual(before)
    if (job.expectedDisposition !== 'PREPARABLE') {
      await opened.drawer.locator('.el-drawer__close-btn').click()
      await expect(page.getByTestId('ota-rollback-preflight-result')).toHaveCount(0)
      continue
    }
    await opened.drawer.locator('.el-drawer__close-btn').click()
    await expect(page.getByTestId('ota-rollback-preflight-result')).toHaveCount(0)
    const reopenedRead = readResponse(page, endpoint(fixture, job) + '/rollback-preflight')
    await page
      .locator('.ota-jobs tbody tr')
      .filter({ hasText: job.deviceId })
      .getByTestId('ota-job-detail-open')
      .click()
    const reopened = await readProjection(await reopenedRead)
    unchangedObservation(reopened, refreshed)
    const panel = page.getByTestId('ota-rollback-preflight')
    await expectPanel(panel, reopened)
    // 每段等待最多10秒；不修改时钟、查询期限或准备状态。
    const expiry = Date.parse(reopened.queryExpiresAt)
    if (expiry - Date.now() > 65_000) throw new Error('准备窗口并非生产60秒期限')
    while (Date.now() <= expiry + 100)
      await page.waitForTimeout(Math.min(10_000, expiry + 101 - Date.now()))
    await expect(panel.getByTestId('ota-rollback-preflight-stale')).toBeVisible()
    // 本地计时只提示陈旧；当前分类只能由显式真实GET重新评估。
    await expect(panel.getByTestId('ota-rollback-current-disposition')).toHaveText('可准备')
    const staleRead = readResponse(page, endpoint(fixture, job) + '/rollback-preflight')
    await panel.getByTestId('ota-rollback-preflight-refresh').click()
    const stale = await readProjection(await staleRead)
    unchangedObservation(stale, reopened)
    expect(stale.currentDisposition).toBe('INELIGIBLE')
    expect(stale.currentReason).toBe('QUERY_NOT_CURRENT_OR_FRESH')
    expect(Date.parse(stale.checkedAt)).toBeGreaterThanOrEqual(expiry)
    await expectPanel(panel, stale)
    await expect(panel.getByTestId('ota-rollback-preflight-stale')).toBeVisible()
    expect(await fetchJob(page, endpoint(fixture, job))).toEqual(before)
  }
  expect(preflightReads).toBeGreaterThanOrEqual(8)
  expect(otaMutations).toBe(0)
  await enterProject(page, fixture.alternateProjectName)
  await page.goto('/#/ota/jobs')
  await expect(page.getByTestId('ota-rollback-preflight-result')).toHaveCount(0)
  await expect(page.getByText('请先选择活动', { exact: true })).toBeVisible()
  const scopeCleared = await page.evaluate(
    (ids) => !ids.some((id) => document.body.innerText.includes(id)),
    fixture.jobs.flatMap((job) => [job.jobId, job.preflight.queryId, job.preflight.reportId])
  )
  expect(scopeCleared).toBe(true)
  expect(otaMutations).toBe(0)
})
