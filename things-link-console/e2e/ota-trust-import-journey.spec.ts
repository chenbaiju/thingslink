import { expect, test, type Locator, type Page } from '@playwright/test'
import { createHash, randomUUID } from 'node:crypto'
import { execFileSync } from 'node:child_process'
import { readFile, realpath } from 'node:fs/promises'
import { isAbsolute, relative, resolve } from 'node:path'
import type { components } from '../src/types/api/schema'
import { login, enterProject, OWNER_EMAIL, OWNER_PASSWORD } from './helpers'

type Envelope = components['schemas']['OtaTrustImportRequest']
type TrustState = components['schemas']['OtaTrustResponse']
interface SignedMaterial {
  envelope: Envelope
  envelopeText: string
  envelopeSha256: string
  bundleSha256: string
  bundleVersion: string
  resultingRevision: string
}
interface Fixture {
  projectId: string
  projectName: string
  tenantId: string
  deviceTypeId: string
  trustDomain: string
  alternateTrustDomain: string
  rootFingerprint: string
  rootSpkiBase64: string
  envelopes: SignedMaterial[]
  invalidExpectedStatus: number
  invalidExpectedCode: number
  crossDomainExpectedStatus: number
  crossDomainExpectedCode: number
}
interface Submission {
  key: string
  body: string
  bodyHash: string
  path: string
}

// 输入仅包含离线公开签包；测试不持有任何根/发布私钥或下载地址。
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
    throw new Error('信任导入材料必须位于独占忽略目录')
  return target
}
async function ownedRuntime(baseURL: string, fixturePath: string) {
  const runtime = JSON.parse(
    await readFile(await ownedPath(process.env.E2E_OTA_TRUST_IMPORT_RUNTIME!), 'utf8')
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
    throw new Error('拒绝非独占受控信任导入运行栈')
  if (
    runtime.fixtureSha256 !== sha(await readFile(fixturePath)) ||
    runtime.configSha256 !== sha(await readFile(await ownedPath('logs/016-d-config.json')))
  )
    throw new Error('受控签包或两域精确根配置摘要不匹配')
  const jar = await ownedPath(runtime.jarPath ?? runtime.frozenJar)
  if (sha(await readFile(jar)) !== (runtime.jarSha256 ?? runtime.jarSha))
    throw new Error('信任导入冻结候选已漂移')
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
    throw new Error('信任导入8088进程或端口所有权不符')
  }
}
function domainPath(fixture: Fixture, domain = fixture.trustDomain) {
  return `/api/v1/projects/${fixture.projectId}/ota/trust-domains/${encodeURIComponent(domain)}`
}
function awaitResponse(page: Page, path: string, method: 'GET' | 'POST') {
  return page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === path && response.request().method() === method
  )
}
async function readDomain(page: Page, fixture: Fixture, domain = fixture.trustDomain) {
  return page.evaluate(
    async ({ projectId, domain }) => {
      const apiPath = '/src/api/ota.ts'
      const api = await import(apiPath)
      try {
        return await api.fetchOtaTrustDomain(projectId, domain)
      } catch (failure) {
        if ((failure as { code?: number }).code === 70013) return null
        throw new Error('受控信任域真实读取失败')
      }
    },
    { projectId: fixture.projectId, domain }
  ) as Promise<TrustState | null>
}
async function submitApi(page: Page, fixture: Fixture, domain: string, body: string, key: string) {
  return page.evaluate(
    async ({ projectId, domain, body, key }) => {
      const apiPath = '/src/api/ota.ts'
      const api = await import(apiPath)
      try {
        await api.importOtaTrustBundle(projectId, domain, JSON.parse(body), key)
        return 0
      } catch (failure) {
        return (failure as { code?: number }).code ?? -1
      }
    },
    { projectId: fixture.projectId, domain, body, key }
  )
}
function expectAuthority(value: TrustState | null, fixture: Fixture, material: SignedMaterial) {
  expect(!!value).toBe(true)
  expect(value!.trustDomain).toBe(fixture.trustDomain)
  expect(value!.revision).toBe(material.resultingRevision)
  expect(value!.bundleVersion).toBe(material.bundleVersion)
  expect(value!.bundleSha256).toBe(material.bundleSha256)
  expect(value!.rootFingerprint).toBe(fixture.rootFingerprint)
  expect(value!.rootProfile).toBe('TC_OTA_ED25519_V1')
}
async function closeDialog(dialog: Locator) {
  await dialog.getByRole('button', { name: '关闭', exact: true }).click()
  await expect(dialog).not.toBeVisible()
}
async function loadFile(dialog: Locator, text: string) {
  await dialog.getByTestId('ota-trust-import-file').setInputFiles({
    name: 'controlled-public-envelope.json',
    mimeType: 'application/json',
    buffer: Buffer.from(text)
  })
}
async function openIntent(page: Page, fixture: Fixture, text: string) {
  await page.getByTestId('ota-trust-import-open').click()
  const dialog = page.getByTestId('ota-trust-import-dialog')
  await expect(dialog).toBeVisible()
  await dialog.getByRole('textbox', { name: '绑定信任域' }).fill(fixture.trustDomain)
  await loadFile(dialog, text)
  const read = awaitResponse(page, domainPath(fixture), 'GET')
  await dialog.getByTestId('ota-trust-import-check').click()
  const response = await read
  expect([200, 404].includes(response.status())).toBe(true)
  const confirmation = dialog.getByRole('checkbox', {
    name: '已确认本项目与信任域已预配受控离线根'
  })
  await expect(dialog.getByTestId('ota-trust-import-revision')).toBeVisible()
  if (response.status() === 404) {
    const visibleConfirmation = dialog.getByTestId('ota-trust-import-root-confirm')
    await expect(visibleConfirmation).toBeVisible()
    await visibleConfirmation.click()
    await expect(confirmation).toBeChecked()
  }
  return dialog
}

test('受控离线信任包真实导入、拒绝、完成墓碑及断响应原意图恢复', async ({ page }, testInfo) => {
  test.setTimeout(180_000)
  test.skip(
    !process.env.E2E_OTA_TRUST_IMPORT_RUNTIME || !process.env.E2E_OTA_TRUST_IMPORT_FIXTURE,
    '需要两域精确受控根配置、公开预签材料及独占冻结后端'
  )
  const fixturePath = await ownedPath(process.env.E2E_OTA_TRUST_IMPORT_FIXTURE!)
  await ownedRuntime(testInfo.project.use.baseURL ?? '', fixturePath)
  const fixture = JSON.parse(await readFile(fixturePath, 'utf8')) as Fixture
  if (
    !fixture.projectName ||
    fixture.trustDomain === fixture.alternateTrustDomain ||
    !/^[0-9a-f]{64}$/.test(fixture.rootFingerprint) ||
    !Array.isArray(fixture.envelopes) ||
    fixture.envelopes.length !== 6 ||
    fixture.envelopes.some((entry) => sha(entry.envelopeText) !== entry.envelopeSha256) ||
    /privateKey|PRIVATE KEY|downloadUrl|X-Amz-|plainSecret/.test(JSON.stringify(fixture))
  )
    throw new Error('受控公开签包材料不匹配冻结合同')
  await login(page, OWNER_EMAIL, OWNER_PASSWORD)
  await enterProject(page, fixture.projectName)
  const before = await readDomain(page, fixture)
  const revision = before?.revision ?? '0'
  const first = fixture.envelopes.find(
    (entry) =>
      entry.envelope.expectedRevision === revision &&
      BigInt(entry.bundleVersion) === BigInt(before?.bundleVersion ?? '0') + 1n
  )
  const second =
    first &&
    fixture.envelopes.find(
      (entry) =>
        entry.envelope.expectedRevision === first.resultingRevision &&
        BigInt(entry.bundleVersion) === BigInt(first.bundleVersion) + 1n
    )
  if (!first || !second) throw new Error('精确预签材料不足两版；不能重造已丢弃根或篡改CAS')
  const submissions: Submission[] = []
  page.on('request', (request) => {
    const path = new URL(request.url()).pathname
    if (
      request.method() !== 'POST' ||
      !path.includes('/ota/trust-domains/') ||
      !path.endsWith('/bundles')
    )
      return
    const body = request.postData() ?? ''
    submissions.push({
      path,
      body,
      bodyHash: sha(body),
      key: request.headers()['idempotency-key'] ?? ''
    })
  })
  await page.goto('/#/ota/firmwares')
  await expect(page.getByTestId('ota-trust-import-open')).toBeVisible()
  // 结构合法而数学签名错误的材料必须到真实后端拒绝，不能被本地校验冒充后端验根。
  const invalidEnvelope = structuredClone(first.envelope)
  const signature = Buffer.from(invalidEnvelope.signature, 'base64')
  signature[0] ^= 1
  invalidEnvelope.signature = signature.toString('base64')
  let dialog = await openIntent(page, fixture, JSON.stringify(invalidEnvelope))
  const invalidRead = awaitResponse(page, domainPath(fixture) + '/bundles', 'POST')
  await dialog.getByTestId('ota-trust-import-submit').click()
  const invalidResponse = await invalidRead
  expect(invalidResponse.status()).toBe(fixture.invalidExpectedStatus)
  expect((await invalidResponse.json()).code).toBe(fixture.invalidExpectedCode)
  expect(await readDomain(page, fixture)).toEqual(before)
  await closeDialog(dialog)
  // 页面也必须拒绝绑定域与签包域不一致，真实后端反例随后单独核对。
  await page.getByTestId('ota-trust-import-open').click()
  dialog = page.getByTestId('ota-trust-import-dialog')
  await dialog.getByRole('textbox', { name: '绑定信任域' }).fill(fixture.alternateTrustDomain)
  await loadFile(dialog, first.envelopeText)
  await expect(dialog.getByTestId('ota-trust-import-bundle-sha')).toContainText(first.bundleSha256)
  await expect(
    dialog.getByText('材料绑定域与所填域不同，不能导入。', { exact: true })
  ).toBeVisible()
  await expect(dialog.getByTestId('ota-trust-import-submit')).toBeDisabled()
  const countBeforeCross = submissions.length
  await closeDialog(dialog)
  expect(submissions.length).toBe(countBeforeCross)
  // 同一有效根与签名不能授权URL绑定的另一域；次域保持未登记。
  expect(await readDomain(page, fixture, fixture.alternateTrustDomain)).toBeNull()
  const crossRead = awaitResponse(
    page,
    domainPath(fixture, fixture.alternateTrustDomain) + '/bundles',
    'POST'
  )
  const crossCode = await submitApi(
    page,
    fixture,
    fixture.alternateTrustDomain,
    first.envelopeText,
    randomUUID()
  )
  expect((await crossRead).status()).toBe(fixture.crossDomainExpectedStatus)
  expect(crossCode).toBe(fixture.crossDomainExpectedCode)
  expect(await readDomain(page, fixture, fixture.alternateTrustDomain)).toBeNull()
  expect(await readDomain(page, fixture)).toEqual(before)
  // 实际页面读取当前修订，首次导入须人工确认已预配根；浏览器不提供根配置输入。
  dialog = await openIntent(page, fixture, first.envelopeText)
  await expect(dialog.getByTestId('ota-trust-import-revision')).toContainText(revision)
  await expect(dialog.getByTestId('ota-trust-import-bundle-version')).toContainText(
    first.bundleVersion
  )
  await expect(dialog.getByTestId('ota-trust-import-bundle-sha')).toContainText(first.bundleSha256)
  const normalRead = awaitResponse(page, domainPath(fixture) + '/bundles', 'POST')
  await dialog.getByTestId('ota-trust-import-submit').click()
  expect((await normalRead).status()).toBe(200)
  const normal = submissions.at(-1)!
  expect(!!normal.key && normal.path === domainPath(fixture) + '/bundles').toBe(true)
  expectAuthority(await readDomain(page, fixture), fixture, first)
  // 公共幂等墓碑不重放响应、不重做导入，原键原正文重复只能返回10014。
  const duplicateRead = awaitResponse(page, domainPath(fixture) + '/bundles', 'POST')
  expect(await submitApi(page, fixture, fixture.trustDomain, normal.body, normal.key)).toBe(10014)
  expect((await duplicateRead).status()).toBe(409)
  expect(
    submissions.at(-1)!.bodyHash === normal.bodyHash && submissions.at(-1)!.key === normal.key
  ).toBe(true)
  expectAuthority(await readDomain(page, fixture), fixture, first)
  await closeDialog(dialog)
  dialog = await openIntent(page, fixture, second.envelopeText)
  const countBeforeUnknown = submissions.length
  const cdp = await page.context().newCDPSession(page)
  let resolvePaused!: (status: number) => void
  let rejectPaused!: (failure: Error) => void
  const paused = new Promise<number>((resolve, reject) => {
    resolvePaused = resolve
    rejectPaused = reject
  })
  const timer = setTimeout(
    () => rejectPaused(new Error('真实成功响应未进入有界网络中断点')),
    25_000
  )
  cdp.on('Fetch.requestPaused', (event: { requestId: string; responseStatusCode?: number }) => {
    void (async () => {
      try {
        // Response阶段已有真实HTTP结果；不提供任何伪造响应或替代后端JSON。
        if (event.responseStatusCode !== 200) {
          await cdp.send('Fetch.continueRequest', { requestId: event.requestId })
          rejectPaused(new Error('待中断的实际导入未成功提交'))
          return
        }
        await cdp.send('Fetch.failRequest', {
          requestId: event.requestId,
          errorReason: 'ConnectionClosed'
        })
        resolvePaused(event.responseStatusCode)
      } catch {
        rejectPaused(new Error('真实响应网络中断未完成'))
      }
    })()
  })
  try {
    await cdp.send('Fetch.enable', {
      patterns: [{ urlPattern: '*' + domainPath(fixture) + '/bundles', requestStage: 'Response' }]
    })
    await dialog.getByTestId('ota-trust-import-submit').click()
    expect(await paused).toBe(200)
  } finally {
    clearTimeout(timer)
    await cdp.send('Fetch.disable')
    await cdp.detach()
  }
  await expect(dialog.getByRole('button', { name: '使用原键恢复', exact: true })).toBeVisible()
  expect(submissions.length - countBeforeUnknown).toBe(1)
  const unknown = submissions.at(-1)!
  expect(unknown.key !== normal.key).toBe(true)
  // 必须先查权威已提交事实，之后才显式恢复原意图，绝不盲换键重发。
  const committed = await readDomain(page, fixture)
  expectAuthority(committed, fixture, second)
  await expect(dialog.getByRole('textbox', { name: '绑定信任域' })).toBeDisabled()
  await expect(dialog.getByTestId('ota-trust-import-file')).toBeDisabled()
  const recoveryRead = awaitResponse(page, domainPath(fixture) + '/bundles', 'POST')
  await dialog.getByRole('button', { name: '使用原键恢复', exact: true }).click()
  const recoveryResponse = await recoveryRead
  expect(recoveryResponse.status()).toBe(409)
  expect((await recoveryResponse.json()).code).toBe(10014)
  await expect(dialog.getByTestId('ota-trust-import-notice')).toContainText('原请求已完成')
  await expect(dialog.getByTestId('ota-trust-import-submit')).toBeDisabled()
  expect(submissions.length - countBeforeUnknown).toBe(2)
  const recovery = submissions.at(-1)!
  expect(recovery.key === unknown.key && recovery.bodyHash === unknown.bodyHash).toBe(true)
  expect(await readDomain(page, fixture)).toEqual(committed)
  const countBeforeRead = submissions.length
  const authoritativeRead = awaitResponse(page, domainPath(fixture), 'GET')
  await dialog.getByTestId('ota-trust-import-check').click()
  expect((await authoritativeRead).status()).toBe(200)
  await expect(dialog.getByTestId('ota-trust-import-current')).toContainText(second.bundleSha256)
  await expect(dialog.getByTestId('ota-trust-import-notice')).toContainText(
    '当前公开包摘要与原材料一致'
  )
  await expect(dialog.getByTestId('ota-trust-import-submit')).toBeDisabled()
  expect(submissions.length).toBe(countBeforeRead)
  const publicOnly = await page.evaluate(
    (materials) => {
      const stores = [localStorage, sessionStorage]
      return stores.every((storage) =>
        Array.from({ length: storage.length }, (_, i) => storage.getItem(storage.key(i)!)).every(
          (value) => !materials.some((material) => value?.includes(material))
        )
      )
    },
    [first.envelope.signature, second.envelope.signature, fixture.rootSpkiBase64]
  )
  expect(publicOnly).toBe(true)
  await closeDialog(dialog)
  await page.getByTestId('ota-trust-import-open').click()
  dialog = page.getByTestId('ota-trust-import-dialog')
  await expect(dialog.getByRole('textbox', { name: '绑定信任域' })).toHaveValue('')
  await expect(dialog.getByTestId('ota-trust-import-file')).toHaveValue('')
  await expect(dialog.getByTestId('ota-trust-import-current')).toHaveCount(0)
  expect(submissions.length).toBe(countBeforeRead)
  // 载入公开材料后通过产品真实项目切换销毁旧身份的域、文件和恢复意图。
  await dialog.getByRole('textbox', { name: '绑定信任域' }).fill(fixture.trustDomain)
  await loadFile(dialog, second.envelopeText)
  await expect(dialog.getByTestId('ota-trust-import-bundle-sha')).toContainText(second.bundleSha256)
  const alternate = await page.evaluate(async (projectId) => {
    const apiPath = '/src/api/project.ts'
    const api = await import(apiPath)
    const selected = (await api.fetchProjects()).find(
      (item: { id: string; status: string; myRole?: string }) =>
        item.id !== projectId &&
        item.status === 'ACTIVE' &&
        ['OWNER', 'ADMIN'].includes(item.myRole ?? '')
    )
    return selected ? { id: selected.id as string, name: selected.name as string } : null
  }, fixture.projectId)
  if (!alternate) throw new Error('缺少可实际进入的独立项目以验证身份清理')
  await enterProject(page, alternate.name)
  await page.goto('/#/ota/firmwares')
  await page.getByTestId('ota-trust-import-open').click()
  dialog = page.getByTestId('ota-trust-import-dialog')
  await expect(dialog.getByRole('textbox', { name: '绑定信任域' })).toHaveValue('')
  await expect(dialog.getByTestId('ota-trust-import-current')).toHaveCount(0)
  await expect(dialog.getByTestId('ota-trust-import-bundle-sha')).toHaveCount(0)
  expect(submissions.length).toBe(countBeforeRead)
  await closeDialog(dialog)
})
