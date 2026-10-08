import { expect, test, type Locator, type Page } from '@playwright/test'
import { createHash } from 'node:crypto'
import { execFileSync } from 'node:child_process'
import { readFile, realpath } from 'node:fs/promises'
import { isAbsolute, relative, resolve } from 'node:path'
import type { components } from '../src/types/api/schema'
import { login, enterProject, OWNER_EMAIL, OWNER_PASSWORD } from './helpers'

type Baseline = components['schemas']['OtaTypeBaselineResponse']
type Body = components['schemas']['OtaTypeBaselineBody']
type Version = components['schemas']['OtaTypeBaselineVersionResponse']
interface Fixture {
  projectId: string
  projectName: string
  tenantId: string
  deviceTypeId: string
  typeName: string
  productKey: string
  emptyDeviceTypeId: string
  emptyTypeName: string
  emptyProductKey: string
  alternateProjectId: string
  alternateProjectName: string
  previous: Baseline
  previousHistory: Version[]
  expectedBaseline: Body
  expectedBaselineHash: string
  expectedRevision: string
  emptyExpectedStatus: number
  emptyExpectedCode: number
}
interface Submission {
  path: string
  key: string
  body: string
  bodyHash: string
}

// 受控配置和读取快照都是公开材料；请求键与认证会话仅留在本次进程内存。
test.use({ trace: 'off', video: 'off', screenshot: 'off' })
test.afterEach(async ({ page }) => {
  await page.close()
})
const sha = (value: string | Buffer) => createHash('sha256').update(value).digest('hex')
async function ownedPath(path: string) {
  const root = await realpath(resolve('logs'))
  const target = await realpath(resolve(path))
  const suffix = relative(root, target)
  if (!suffix || isAbsolute(suffix) || suffix === '..' || suffix.startsWith('../'))
    throw new Error('基线登记材料必须位于独占忽略目录')
  return target
}
async function ownedRuntime(baseURL: string, fixturePath: string) {
  const runtime = JSON.parse(
    await readFile(await ownedPath(process.env.E2E_OTA_BASELINE_REGISTRATION_RUNTIME!), 'utf8')
  )
  const origin = new URL(baseURL)
  if (
    !/^tc_console_012a_[A-Za-z0-9_]+$/.test(runtime.database) ||
    runtime.postgresContainer !== 'tc-console-012a-pg' ||
    runtime.postgresPort !== 5547 ||
    runtime.redisDatabase !== 14 ||
    runtime.backendPort !== 8088 ||
    runtime.vitePort !== 3017 ||
    !Number.isSafeInteger(runtime.pid) ||
    runtime.pid <= 1 ||
    !/^[0-9a-f]{64}$/.test(runtime.jarSha256 ?? runtime.jarSha) ||
    origin.protocol !== 'http:' ||
    !['localhost', '127.0.0.1'].includes(origin.hostname) ||
    origin.port !== '3017'
  )
    throw new Error('拒绝非独占受控基线登记运行栈')
  if (
    runtime.fixtureSha256 !== sha(await readFile(fixturePath)) ||
    runtime.configSha256 !==
      sha(await readFile(await ownedPath(runtime.configPath ?? 'logs/016-e-config.json')))
  )
    throw new Error('精确类型配置或公开基线快照摘要不匹配')
  const jar = await ownedPath(runtime.jarPath ?? runtime.frozenJar)
  if (sha(await readFile(jar)) !== (runtime.jarSha256 ?? runtime.jarSha))
    throw new Error('基线登记冻结候选已漂移')
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
    throw new Error('基线登记8088进程或端口所有权不符')
  }
}
function baselinePath(fixture: Fixture, typeId = fixture.deviceTypeId) {
  return `/api/v1/projects/${fixture.projectId}/ota/device-types/${typeId}/baseline`
}
function responseFor(page: Page, path: string, method: 'GET' | 'POST') {
  return page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === path && response.request().method() === method,
    { timeout: 20_000 }
  )
}
async function currentBaseline(page: Page, fixture: Fixture, typeId = fixture.deviceTypeId) {
  return page.evaluate(
    async ({ projectId, typeId }) => {
      const path = '/src/api/ota.ts'
      const api = await import(path)
      try {
        return await api.fetchOtaTypeBaseline(projectId, typeId)
      } catch (error) {
        if ((error as { code?: number }).code === 70031) return null
        throw new Error('基线权威读取失败')
      }
    },
    { projectId: fixture.projectId, typeId }
  ) as Promise<Baseline | null>
}
async function history(page: Page, fixture: Fixture, typeId = fixture.deviceTypeId) {
  return page.evaluate(
    async ({ projectId, typeId }) => {
      const path = '/src/api/ota.ts'
      const api = await import(path)
      try {
        return await api.fetchOtaBaselineVersions(projectId, typeId, { limit: 20 })
      } catch {
        throw new Error('基线历史读取失败')
      }
    },
    { projectId: fixture.projectId, typeId }
  ) as Promise<{ items: Version[]; hasMore: boolean }>
}
async function openDialog(page: Page) {
  await page.getByTestId('ota-baseline-registration-open').click()
  const dialog = page.getByTestId('ota-baseline-registration-dialog')
  await expect(dialog).toBeVisible()
  return dialog
}
async function closeDialog(dialog: Locator) {
  await dialog.getByRole('button', { name: '关闭', exact: true }).click()
  await expect(dialog).not.toBeVisible()
}
async function chooseType(page: Page, dialog: Locator, typeId: string) {
  const select = dialog.getByTestId('ota-baseline-registration-type')
  const input = select.getByRole('combobox')
  await expect(input).toBeEnabled({ timeout: 10_000 })
  if ((await input.getAttribute('aria-expanded')) !== 'true')
    await select.click({ timeout: 10_000 })
  await expect(input).toHaveAttribute('aria-expanded', 'true', { timeout: 10_000 })
  const option = page.getByRole('option').filter({ hasText: typeId })
  await expect(option).toBeVisible({ timeout: 10_000 })
  await option.click({ timeout: 10_000 })
  await expect(dialog.getByTestId('ota-baseline-registration-current')).toHaveCount(0)
  await expect(dialog.getByTestId('ota-baseline-registration-submit')).toBeDisabled()
}
async function readCurrent(
  page: Page,
  dialog: Locator,
  fixture: Fixture,
  typeId: string,
  status: number
) {
  const button = dialog.getByTestId('ota-baseline-registration-check')
  await expect(button).toBeEnabled({ timeout: 10_000 })
  const [read] = await Promise.all([
    responseFor(page, baselinePath(fixture, typeId), 'GET'),
    button.click({ timeout: 10_000 })
  ])
  expect(read.status()).toBe(status)
  await expect(dialog.getByTestId('ota-baseline-registration-revision')).toBeVisible()
}
async function confirmConfig(dialog: Locator) {
  const visible = dialog.getByTestId('ota-baseline-registration-config-confirm')
  await expect(visible).toBeVisible()
  await visible.click()
  await expect(
    dialog.getByRole('checkbox', { name: '已确认服务器已更新本项目与精确类型的受控基线配置' })
  ).toBeChecked()
  await expect(dialog.getByTestId('ota-baseline-registration-submit')).toBeEnabled()
}
function expectCommitted(value: Baseline | null, fixture: Fixture) {
  expect(!!value).toBe(true)
  expect(Object.keys(value!).sort()).toEqual([
    'baseline',
    'baselineHash',
    'registeredAt',
    'revision',
    'updatedAt'
  ])
  expect(value!.revision).toBe(fixture.expectedRevision)
  expect(value!.baselineHash).toBe(fixture.expectedBaselineHash)
  expect(value!.baseline).toEqual(fixture.expectedBaseline)
  expect(value!.registeredAt).toBe(fixture.previous.registeredAt)
  expect(Date.parse(value!.updatedAt!) >= Date.parse(fixture.previous.updatedAt!)).toBe(true)
}
async function expectPublicBody(dialog: Locator, body: Body) {
  const rows = [
    ['合同', body.contractVersion],
    ['租户', body.tenantId],
    ['项目', body.projectId],
    ['设备类型', body.deviceTypeId],
    ['产品标识', body.productKey],
    ['信任域', body.trustDomain],
    ['离线根指纹', body.rootFingerprint],
    ['硬件型号', body.hardware!.model],
    ['板级序号范围', `${body.hardware!.boardRevisionMin} — ${body.hardware!.boardRevisionMax}`],
    ['引导程序版本范围', `${body.bootloader!.minimumVersion} — ${body.bootloader!.maximumVersion}`],
    ['签名规格', body.signatureProfiles!.join('、')],
    ['最大固件字节', String(body.maximumArtifactBytes)],
    ['可用RAM字节', String(body.availableRamBytes)],
    ['可用Flash字节', String(body.availableFlashBytes)],
    ['双槽支持', String(body.supportsAbSlots)],
    ['Range支持', String(body.supportsRangeDownload)],
    ['续传支持', String(body.supportsResumeDownload)],
    ['受保护计数器位数', String(body.protectedSecurityCounterBits)],
    ['压缩算法', body.compressionAlgorithms!.join('、')],
    ['差分方式', body.deltaModes!.join('、')],
    ['属性Profile', body.propertyProfile],
    ['受控来源索引', body.evidenceReference]
  ]
  const current = dialog.getByTestId('ota-baseline-registration-current')
  await expect(current.locator('dl > div')).toHaveCount(rows.length)
  for (const [index, [label, value]] of rows.entries()) {
    const row = current.locator('dl > div').nth(index)
    await expect(row.locator('dt')).toHaveText(label!)
    await expect(row.locator('dd')).toHaveText(value!)
  }
}

test('服务器精确类型基线真实登记、断响应原键恢复、缺配置拒绝和身份清理', async ({
  page
}, testInfo) => {
  test.setTimeout(180_000)
  page.setDefaultTimeout(15_000)
  test.skip(
    !process.env.E2E_OTA_BASELINE_REGISTRATION_RUNTIME ||
      !process.env.E2E_OTA_BASELINE_REGISTRATION_FIXTURE,
    '需要精确递增版本受控配置、公开五字段快照及独占冻结后端'
  )
  const fixturePath = await ownedPath(process.env.E2E_OTA_BASELINE_REGISTRATION_FIXTURE!)
  await ownedRuntime(testInfo.project.use.baseURL ?? '', fixturePath)
  const fixture = JSON.parse(await readFile(fixturePath, 'utf8')) as Fixture
  if (
    fixture.deviceTypeId === fixture.emptyDeviceTypeId ||
    fixture.projectId === fixture.alternateProjectId ||
    !/^[1-9][0-9]{0,18}$/.test(fixture.previous.revision ?? '') ||
    !/^[1-9][0-9]{0,18}$/.test(fixture.expectedRevision) ||
    BigInt(fixture.expectedRevision) !== BigInt(fixture.previous.revision!) + 1n ||
    !Number.isSafeInteger(fixture.previous.baseline?.baselineVersion) ||
    fixture.expectedBaseline.baselineVersion !== fixture.previous.baseline!.baselineVersion! + 1 ||
    !/^[0-9a-f]{64}$/.test(fixture.expectedBaselineHash) ||
    /privateKey|PRIVATE KEY|downloadUrl|X-Amz-|plainSecret/.test(JSON.stringify(fixture))
  )
    throw new Error('受控公开基线材料不匹配冻结合同')
  const marker = (stage: string, count = 0) =>
    console.log(JSON.stringify({ stage, registrationPosts: count }))
  marker('OWNED_FIXTURE_VALIDATED')
  await login(page, OWNER_EMAIL, OWNER_PASSWORD)
  await enterProject(page, fixture.projectName)
  expect(await currentBaseline(page, fixture)).toEqual(fixture.previous)
  expect((await history(page, fixture)).items).toEqual(fixture.previousHistory)
  expect(await currentBaseline(page, fixture, fixture.emptyDeviceTypeId)).toBeNull()
  expect((await history(page, fixture, fixture.emptyDeviceTypeId)).items).toEqual([])
  marker('BEFORE_STATE_CONFIRMED')
  const submissions: Submission[] = []
  page.on('request', (request) => {
    const path = new URL(request.url()).pathname
    if (
      request.method() === 'POST' &&
      /^\/api\/v1\/projects\/[^/]+\/ota\/device-types\/[^/]+\/baseline\/registrations$/.test(path)
    ) {
      const body = request.postData() ?? ''
      submissions.push({
        path,
        key: request.headers()['idempotency-key'] ?? '',
        body,
        bodyHash: sha(body)
      })
    }
  })
  await page.goto('/#/ota/firmwares')
  let dialog = await openDialog(page)
  await chooseType(page, dialog, fixture.deviceTypeId)
  await readCurrent(page, dialog, fixture, fixture.deviceTypeId, 200)
  await expect(dialog.getByTestId('ota-baseline-registration-revision')).toContainText(
    fixture.previous.revision!
  )
  await expect(dialog.getByTestId('ota-baseline-registration-hash')).toContainText(
    fixture.previous.baselineHash!
  )
  await expectPublicBody(dialog, fixture.previous.baseline!)
  await expect(
    dialog.getByText(fixture.expectedBaseline.evidenceReference!, { exact: true })
  ).toHaveCount(0)
  await confirmConfig(dialog)
  marker('PREVIOUS_PUBLIC_BODY_AND_CONFIG_CONFIRMATION_READY', submissions.length)
  // 在真实200响应阶段断网；后端已经提交，浏览器没有获得可冒称成功的正文。
  const cdp = await page.context().newCDPSession(page)
  let resolvePaused!: (status: number) => void
  let rejectPaused!: (error: Error) => void
  const paused = new Promise<number>((resolve, reject) => {
    resolvePaused = resolve
    rejectPaused = reject
  })
  const timer = setTimeout(() => rejectPaused(new Error('成功登记未进入有界网络中断点')), 25_000)
  cdp.on('Fetch.requestPaused', (event: { requestId: string; responseStatusCode?: number }) => {
    void (async () => {
      try {
        if (event.responseStatusCode !== 200) {
          await cdp.send('Fetch.continueRequest', { requestId: event.requestId })
          rejectPaused(new Error('待中断的实际基线登记未成功提交'))
          return
        }
        await cdp.send('Fetch.failRequest', {
          requestId: event.requestId,
          errorReason: 'ConnectionClosed'
        })
        resolvePaused(event.responseStatusCode)
      } catch {
        rejectPaused(new Error('登记响应网络中断未完成'))
      }
    })()
  })
  try {
    await cdp.send('Fetch.enable', {
      patterns: [
        { urlPattern: '*' + baselinePath(fixture) + '/registrations', requestStage: 'Response' }
      ]
    })
    await dialog.getByTestId('ota-baseline-registration-submit').click()
    expect(await paused).toBe(200)
  } finally {
    clearTimeout(timer)
    await cdp.send('Fetch.disable')
    await cdp.detach()
  }
  await expect(dialog.getByRole('button', { name: '使用原键恢复', exact: true })).toBeVisible()
  marker('ACTUAL_SUCCESS_RESPONSE_INTERRUPTED', submissions.length)
  expect(submissions.length).toBe(1)
  const original = submissions[0]!
  expect(!!original.key && original.path === baselinePath(fixture) + '/registrations').toBe(true)
  expect(JSON.parse(original.body)).toEqual({ expectedRevision: fixture.previous.revision })
  const committed = await currentBaseline(page, fixture)
  expectCommitted(committed, fixture)
  const committedHistory = await history(page, fixture)
  expect(committedHistory.hasMore).toBe(false)
  expect(committedHistory.items.length).toBe(fixture.previousHistory.length + 1)
  expect(committedHistory.items.slice(1)).toEqual(fixture.previousHistory)
  expect(committedHistory.items[0]).toEqual({
    baselineVersion: fixture.expectedBaseline.baselineVersion,
    baselineHash: fixture.expectedBaselineHash,
    registeredAt: committed!.updatedAt
  })
  marker('COMMITTED_CURRENT_AND_FULL_HISTORY_CONFIRMED', submissions.length)
  // 同身份关闭仅清公开视图；重开只能恢复原类型、原修订与原键。
  await closeDialog(dialog)
  dialog = await openDialog(page)
  await expect(
    dialog.getByTestId('ota-baseline-registration-type').getByRole('combobox')
  ).toBeDisabled()
  await expect(dialog.getByTestId('ota-baseline-registration-type')).toContainText(
    fixture.deviceTypeId
  )
  await expect(dialog.getByTestId('ota-baseline-registration-current')).toHaveCount(0)
  expect(submissions.length).toBe(1)
  const recoveryButton = dialog.getByRole('button', { name: '使用原键恢复', exact: true })
  await expect(recoveryButton).toBeEnabled({ timeout: 10_000 })
  const [recovered] = await Promise.all([
    responseFor(page, baselinePath(fixture) + '/registrations', 'POST'),
    recoveryButton.click({ timeout: 10_000 })
  ])
  expect(recovered.status()).toBe(409)
  expect((await recovered.json()).code).toBe(10014)
  await expect(dialog.getByTestId('ota-baseline-registration-notice')).toContainText(
    '原意图永久结束'
  )
  await expect(dialog.getByTestId('ota-baseline-registration-submit')).toBeDisabled()
  expect(submissions.length).toBe(2)
  expect(
    submissions[1]!.key === original.key && submissions[1]!.bodyHash === original.bodyHash
  ).toBe(true)
  marker('ORIGINAL_KEY_COMPLETED_TOMBSTONE_CONFIRMED', submissions.length)
  await readCurrent(page, dialog, fixture, fixture.deviceTypeId, 200)
  await expect(dialog.getByTestId('ota-baseline-registration-hash')).toContainText(
    fixture.expectedBaselineHash
  )
  await expect(dialog.getByTestId('ota-baseline-registration-version')).toContainText(
    String(fixture.expectedBaseline.baselineVersion)
  )
  await expectPublicBody(dialog, fixture.expectedBaseline)
  await expect(dialog.getByTestId('ota-baseline-registration-notice')).toContainText(
    '仅是当前权威登记快照'
  )
  expect(await currentBaseline(page, fixture)).toEqual(committed)
  expect(await history(page, fixture)).toEqual(committedHistory)
  expect(submissions.length).toBe(2)
  await dialog.getByTestId('ota-baseline-registration-new-intent').click()
  await expect(dialog.getByTestId('ota-baseline-registration-current')).toHaveCount(0)
  await expect(dialog.getByTestId('ota-baseline-registration-submit')).toBeDisabled()
  marker('NEW_INTENT_CLEARED_SNAPSHOT', submissions.length)
  // 配置缺失是实际503；失败不产生完成墓碑，不能伪造10014来解除未知意图。
  await chooseType(page, dialog, fixture.emptyDeviceTypeId)
  marker('UNCONFIGURED_TYPE_SELECTED', submissions.length)
  await readCurrent(page, dialog, fixture, fixture.emptyDeviceTypeId, 404)
  await expect(dialog.getByTestId('ota-baseline-registration-revision')).toContainText('0')
  await confirmConfig(dialog)
  const [missingResponse] = await Promise.all([
    responseFor(page, baselinePath(fixture, fixture.emptyDeviceTypeId) + '/registrations', 'POST'),
    dialog.getByTestId('ota-baseline-registration-submit').click({ timeout: 10_000 })
  ])
  expect(missingResponse.status()).toBe(fixture.emptyExpectedStatus)
  expect((await missingResponse.json()).code).toBe(fixture.emptyExpectedCode)
  await expect(dialog.getByTestId('ota-baseline-registration-notice')).toContainText(
    '精确类型受控基线尚未配置或不可用'
  )
  await expect(dialog.getByRole('button', { name: '使用原键恢复', exact: true })).toBeVisible()
  expect(submissions.length).toBe(3)
  expect(JSON.parse(submissions[2]!.body)).toEqual({ expectedRevision: '0' })
  expect(submissions[2]!.key !== original.key).toBe(true)
  expect(await currentBaseline(page, fixture, fixture.emptyDeviceTypeId)).toBeNull()
  expect((await history(page, fixture, fixture.emptyDeviceTypeId)).items).toEqual([])
  expect(await currentBaseline(page, fixture)).toEqual(committed)
  marker('UNCONFIGURED_TYPE_REJECTED_WITH_NO_REGISTRATION', submissions.length)
  await closeDialog(dialog)
  dialog = await openDialog(page)
  await expect(
    dialog.getByTestId('ota-baseline-registration-type').getByRole('combobox')
  ).toBeDisabled()
  await expect(dialog.getByTestId('ota-baseline-registration-type')).toContainText(
    fixture.emptyDeviceTypeId
  )
  await expect(dialog.getByTestId('ota-baseline-registration-current')).toHaveCount(0)
  await expect(dialog.getByRole('button', { name: '使用原键恢复', exact: true })).toBeEnabled()
  expect(submissions.length).toBe(3)
  marker('UNCONFIGURED_PENDING_REOPENED_IN_SAME_IDENTITY', submissions.length)
  await enterProject(page, fixture.alternateProjectName)
  await page.goto('/#/ota/firmwares')
  dialog = await openDialog(page)
  await expect(dialog.getByTestId('ota-baseline-registration-current')).toHaveCount(0)
  await expect(dialog.getByTestId('ota-baseline-registration-revision')).toHaveCount(0)
  await expect(dialog.getByRole('button', { name: '使用原键恢复', exact: true })).toHaveCount(0)
  await expect(dialog.getByTestId('ota-baseline-registration-submit')).toBeDisabled()
  const keysPersisted = await page.evaluate(
    (keys) =>
      [localStorage, sessionStorage].some((storage) =>
        Array.from({ length: storage.length }, (_, i) => storage.getItem(storage.key(i)!)).some(
          (value) => keys.some((key) => value?.includes(key))
        )
      ),
    submissions.map((entry) => entry.key)
  )
  expect(keysPersisted).toBe(false)
  expect(submissions.length).toBe(3)
  marker('PROJECT_IDENTITY_CLEARED_PENDING_AND_STORAGE_SAFE', submissions.length)
  await closeDialog(dialog)
})
