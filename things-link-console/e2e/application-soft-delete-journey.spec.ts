import { expect, test, type Page } from '@playwright/test'
import { createHash, randomBytes, randomUUID } from 'node:crypto'
import { execFileSync } from 'node:child_process'
import { mkdir, readFile, realpath, writeFile } from 'node:fs/promises'
import { isAbsolute, relative, resolve } from 'node:path'
import { login, enterProject, OWNER_EMAIL, OWNER_PASSWORD } from './helpers'

interface PublicFixture {
  projectId: string
  projectName: string
  projectKey: string
  tenantId: string
  expectedManagementNotFound: { status: number; code: number }
  expectedAppUnavailable: { status: number; code: number }
}
interface NativeResult {
  status: number
  code?: number
  value: Record<string, any>
  empty: boolean
  location: boolean
}
interface Dashboard {
  id: string
  versionId: string
  publicationRevision: string
  name: string
  text: string
}
interface Application {
  id: string
  appKey: string
  versionId: string
  publicationRevision: string
  name: string
  displayName: string
}
interface Deletion {
  id: string
  key: string
  bodyHash: string
  revision?: string
  closedBody: boolean
}

// 分享能力、App令牌及管理令牌只存在Node RAM；传播探针使用原生HTTP，不进入Playwright诊断。
test.use({ trace: 'off', video: 'off', screenshot: 'off' })
test.afterEach(async ({ page }) => {
  await page.close()
})
const sha = (value: string | Buffer) => createHash('sha256').update(value).digest('hex')
const uuid = (value: unknown): value is string =>
  typeof value === 'string' && /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(value)
const marker = (stage: string, deletionPosts: number) =>
  console.log(JSON.stringify({ stage, deletionPosts }))
async function ownedPath(path: string) {
  const root = await realpath(resolve('logs')),
    target = await realpath(resolve(path)),
    suffix = relative(root, target)
  if (!suffix || isAbsolute(suffix) || suffix === '..' || suffix.startsWith('../'))
    throw new Error('软删材料必须位于独占忽略目录')
  return target
}
function listener(pid: number, port: number) {
  try {
    return (
      execFileSync(
        'lsof',
        ['-nP', '-a', '-p', String(pid), `-iTCP:${port}`, '-sTCP:LISTEN', '-t'],
        { encoding: 'utf8', timeout: 3_000 }
      ).trim() === String(pid)
    )
  } catch {
    return false
  }
}
async function ownedRuntime(baseURL: string, fixturePath: string) {
  const runtime = JSON.parse(
    await readFile(await ownedPath(process.env.E2E_APPLICATION_SOFT_DELETE_RUNTIME!), 'utf8')
  )
  const origin = new URL(baseURL)
  if (
    origin.protocol !== 'http:' ||
    !['localhost', '127.0.0.1'].includes(origin.hostname) ||
    origin.port !== '3017' ||
    !/^tc_console_012a_[A-Za-z0-9_]+$/.test(runtime.database) ||
    runtime.postgresContainer !== 'tc-console-012a-pg' ||
    runtime.postgresPort !== 5547 ||
    runtime.redisDatabase !== 14 ||
    runtime.backendPort !== 8088 ||
    runtime.vitePort !== 3017 ||
    runtime.webappPort !== 4017 ||
    runtime.shareEnabled !== true ||
    runtime.shareOrigin !== 'http://localhost:4017' ||
    runtime.hostVersion !== '1.0.0' ||
    !Number.isSafeInteger(runtime.pid) ||
    runtime.pid <= 1 ||
    !Number.isSafeInteger(runtime.webappPid) ||
    runtime.webappPid <= 1 ||
    !/^[0-9a-f]{64}$/.test(runtime.jarSha256 ?? runtime.jarSha)
  )
    throw new Error('拒绝非独占软删运行栈或匿名宿主')
  if (
    runtime.fixtureSha256 !== sha(await readFile(fixturePath)) ||
    runtime.configSha256 !== sha(await readFile(await ownedPath(runtime.configPath)))
  )
    throw new Error('软删受控配置或公开材料摘要不匹配')
  const jar = await ownedPath(runtime.jarPath ?? runtime.frozenJar)
  if (sha(await readFile(jar)) !== (runtime.jarSha256 ?? runtime.jarSha))
    throw new Error('软删冻结候选已漂移')
  let command = ''
  try {
    command = execFileSync('ps', ['-p', String(runtime.pid), '-o', 'command='], {
      encoding: 'utf8',
      timeout: 3_000
    })
  } catch {
    throw new Error('软删后端进程不存在')
  }
  if (!command.includes(jar) || !listener(runtime.pid, 8088) || !listener(runtime.webappPid, 4017))
    throw new Error('软删后端或匿名宿主端口所有权不符')
  const descriptorPath = await ownedPath(runtime.hostDescriptorPath),
    descriptorBytes = await readFile(descriptorPath),
    descriptor = JSON.parse(descriptorBytes.toString('utf8'))
  const registry = await ownedPath(runtime.hostRegistry)
  if (
    !descriptorPath.startsWith(registry + '/') ||
    runtime.hostDescriptorSha256 !== sha(descriptorBytes) ||
    descriptor.formatVersion !== 'tc.webapp-host/v1' ||
    descriptor.hostVersion !== runtime.hostVersion ||
    descriptor.artifactDigestAlgorithm !== 'SHA-256' ||
    descriptor.artifactDigest !== runtime.hostDigest ||
    !/^[0-9a-f]{64}$/.test(runtime.hostDigest)
  )
    throw new Error('受管宿主描述或制品摘要不匹配')
  return runtime
}
/** 有界真实socket请求；任何网络/JSON异常只暴露固定类码，不附fetch cause或请求头。 */
async function nativeHttp(
  path: string,
  headers: Record<string, string>,
  method = 'GET',
  body?: unknown
): Promise<NativeResult> {
  // 真实PRO读取/管理写按自然节奏发送，不修改后端限流资格。
  await new Promise((resolve) => setTimeout(resolve, 260))
  let response: Response
  try {
    response = await fetch('http://127.0.0.1:8088' + path, {
      method,
      headers: {
        Accept: 'application/json',
        ...headers,
        ...(body === undefined
          ? {}
          : { 'Content-Type': 'application/json', 'Idempotency-Key': randomUUID() })
      },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
      credentials: 'omit',
      redirect: 'error',
      cache: 'no-store',
      signal: AbortSignal.timeout(15_000)
    })
  } catch {
    throw new Error('NATIVE_HTTP_NETWORK_CLASS')
  }
  let bytes: Uint8Array
  try {
    bytes = new Uint8Array(await response.arrayBuffer())
  } catch {
    throw new Error('NATIVE_HTTP_BODY_CLASS')
  }
  if (bytes.length > 4 * 1024 * 1024) throw new Error('NATIVE_HTTP_BODY_LIMIT')
  let value: Record<string, any> = {}
  if (bytes.length) {
    try {
      value = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes))
    } catch {
      throw new Error('NATIVE_HTTP_JSON_CLASS')
    }
  }
  return {
    status: response.status,
    code: Number.isSafeInteger(value.code) ? value.code : undefined,
    value,
    empty: !bytes.length,
    location: response.headers.has('location')
  }
}
function requireStatus(result: NativeResult, status: number, stage: string) {
  if (result.status !== status)
    throw new Error(`${stage}_HTTP_${result.status}_CODE_${result.code ?? 0}`)
}
const dashboardBase = (fixture: PublicFixture, id: string) =>
  `/api/v1/projects/${fixture.projectId}/dashboards/${id}`
function responseFor(page: Page, path: string, method: 'GET' | 'POST' | 'PUT') {
  return page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === path && response.request().method() === method,
    { timeout: 20_000 }
  )
}
async function checkpoint(directory: string, stage: string, metadata: Record<string, unknown>) {
  const fields = new Set([
    'dashboardId',
    'dashboardVersionId',
    'publicationRevision',
    'shareId',
    'status',
    'applicationId',
    'appKey',
    'applicationVersionId',
    'appUserId',
    'revision'
  ])
  if (Object.keys(metadata).some((field) => !fields.has(field)))
    throw new Error('公开检查点字段越界')
  await writeFile(
    resolve(directory, stage + '.json'),
    JSON.stringify({ stage, ...metadata }, null, 2) + '\n',
    { flag: 'wx' }
  )
}
async function createPublished(
  page: Page,
  fixture: PublicFixture,
  label: string,
  directory: string
): Promise<Dashboard> {
  await page.goto('/#/dashboard/designer')
  const name = `017-B-${label}-${Date.now()}`,
    text = `017-B静态公开文字-${label}`
  const create = page.getByTestId('dashboard-create')
  await expect(create).toBeEnabled({ timeout: 10_000 })
  await create.click({ timeout: 10_000 })
  await page.getByTestId('dashboard-name').fill(name)
  const [created] = await Promise.all([
    responseFor(page, `/api/v1/projects/${fixture.projectId}/dashboards`, 'POST'),
    page.getByTestId('dashboard-create-confirm').click({ timeout: 10_000 })
  ])
  expect(created.status()).toBe(201)
  await expect(page).toHaveURL(/dashboardId=/)
  const id = new URLSearchParams(new URL(page.url()).hash.split('?')[1]).get('dashboardId')
  if (!uuid(id)) throw new Error('真实创建后缺少公开看板身份')
  await checkpoint(directory, label + '-created', { dashboardId: id })
  await page.getByTestId('designer-add-text').click({ timeout: 10_000 })
  await page.getByTestId('designer-text-content').fill(text)
  await expect(page.getByTestId('designer-save-state')).toHaveAttribute('data-status', 'saved')
  await page.getByTestId('publication-publish').click({ timeout: 10_000 })
  const dialog = page.getByRole('dialog')
  const [published] = await Promise.all([
    responseFor(page, dashboardBase(fixture, id) + '/versions', 'POST'),
    dialog.getByRole('button', { name: '发布', exact: true }).click({ timeout: 10_000 })
  ])
  expect(published.status()).toBe(201)
  const status = page.getByTestId('publication-status')
  await expect(status).toHaveAttribute('data-version-id', /^[a-f0-9-]{36}$/)
  const versionId = (await status.getAttribute('data-version-id'))!
  await checkpoint(directory, label + '-published', {
    dashboardId: id,
    dashboardVersionId: versionId,
    publicationRevision: '1'
  })
  return { id, versionId, publicationRevision: '1', name, text }
}
const applicationBase = (fixture: PublicFixture, id?: string) =>
  `/api/v1/projects/${fixture.projectId}/applications` + (id ? `/${id}` : '')
const applicationRow = (page: Page, name: string) =>
  page
    .getByRole('region', { name: '应用目录', exact: true })
    .getByRole('listitem')
    .filter({ hasText: name })
function independentShareHash(kind: 'context' | 'schema', value: Record<string, any>) {
  if (kind === 'schema') return sha(JSON.stringify(value))
  // historyAnchorAt是每次读取的数据库时间，持久分享身份和授权范围才应保持不变。
  const { historyAnchorAt, ...projection } = value
  if (typeof historyAnchorAt !== 'string' || !Number.isFinite(Date.parse(historyAnchorAt)))
    throw new Error('独立分享读取缺少权威历史时间锚点')
  return sha(JSON.stringify(projection))
}
async function createApplicationUi(
  page: Page,
  fixture: PublicFixture,
  dashboard: Dashboard,
  label: string,
  directory: string,
  publish: boolean
): Promise<Application> {
  await page.goto('/#/dashboard/applications')
  const name = `017-B-${label}-${Date.now()}`,
    displayName = `017-B公开应用-${label}`
  const draft = page.getByRole('region', { name: '应用草稿', exact: true }),
    previousId = (await draft.count()) ? await draft.getAttribute('data-application-id') : null
  await page.getByLabel('管理名称', { exact: true }).fill(name)
  const create = page.getByRole('button', { name: '创建应用', exact: true })
  await expect(create).toBeEnabled({ timeout: 10_000 })
  const [created] = await Promise.all([
    responseFor(page, applicationBase(fixture), 'POST'),
    create.click({ timeout: 10_000 })
  ])
  expect(created.status()).toBe(201)
  let id: unknown
  try {
    id = (await created.json()).id
  } catch {
    throw new Error('应用创建公开身份读取失败')
  }
  if (!uuid(id)) throw new Error('真实应用创建后缺少公开身份')
  expect(id !== previousId).toBe(true)
  await expect(draft).toHaveAttribute('data-application-id', id, { timeout: 10_000 })
  await expect(page.getByLabel('公开展示名', { exact: true })).toHaveValue(name, {
    timeout: 10_000
  })
  await checkpoint(directory, label + '-created', { applicationId: id })
  marker(
    label === 'target'
      ? 'TARGET_APP_EXACT_NEW_DRAFT_READY'
      : 'UNPUBLISHED_APP_EXACT_NEW_DRAFT_READY',
    0
  )
  await page.getByLabel('公开展示名', { exact: true }).fill(displayName)
  await page.getByLabel('最低宿主版本', { exact: true }).fill('1.0.0')
  await page.getByLabel('最高宿主版本', { exact: true }).fill('1.0.1')
  const [catalog] = await Promise.all([
    responseFor(page, `/api/v1/projects/${fixture.projectId}/dashboards`, 'GET'),
    page.getByRole('button', { name: '读取看板目录', exact: true }).click({ timeout: 10_000 })
  ])
  expect(catalog.status()).toBe(200)
  const select = page.getByLabel('看板', { exact: true })
  for (let step = 0; step < 10; step++) {
    if (await select.locator(`option[value="${dashboard.id}"]`).count()) break
    const next = page.getByRole('button', { name: '下一页看板', exact: true })
    if (!(await next.isEnabled())) throw new Error('真实引用看板目录没有所需公开身份')
    const [read] = await Promise.all([
      responseFor(page, `/api/v1/projects/${fixture.projectId}/dashboards`, 'GET'),
      next.click({ timeout: 10_000 })
    ])
    expect(read.status()).toBe(200)
  }
  await expect(select.locator(`option[value="${dashboard.id}"]`)).toHaveCount(1, {
    timeout: 10_000
  })
  const [versions] = await Promise.all([
    responseFor(page, dashboardBase(fixture, dashboard.id) + '/versions', 'GET'),
    select.selectOption(dashboard.id, { timeout: 10_000 })
  ])
  expect(versions.status()).toBe(200)
  const versionSelect = page.getByLabel('不可变版本', { exact: true })
  await expect(versionSelect.locator(`option[value="${dashboard.versionId}"]`)).toHaveCount(1, {
    timeout: 10_000
  })
  await versionSelect.selectOption(dashboard.versionId, { timeout: 10_000 })
  await page.getByLabel('新导航标题', { exact: true }).fill('保留的精确看板')
  await page.getByRole('button', { name: '添加或替换引用', exact: true }).click({ timeout: 10_000 })
  await expect(page.getByLabel('入口看板', { exact: true })).toHaveValue(dashboard.id, {
    timeout: 10_000
  })
  const save = page.getByRole('button', { name: '保存应用草稿', exact: true })
  await expect(save).toBeEnabled({ timeout: 10_000 })
  const [saved] = await Promise.all([
    responseFor(page, applicationBase(fixture, id) + '/draft', 'PUT'),
    save.click({ timeout: 10_000 })
  ])
  expect(saved.status()).toBe(200)
  await expect(save).toBeDisabled({ timeout: 10_000 })
  await expect(draft).toHaveAttribute('data-application-id', id, { timeout: 10_000 })
  await expect(draft).toContainText('草稿修订 1', { timeout: 10_000 })
  await expect(page.getByLabel('公开展示名', { exact: true })).toHaveValue(displayName, {
    timeout: 10_000
  })
  await checkpoint(directory, label + '-saved', { applicationId: id, revision: '1' })
  let versionId = ''
  if (publish) {
    const trigger = page.getByTestId('application-publication-publish')
    await expect(trigger).toBeEnabled({ timeout: 10_000 })
    await trigger.click({ timeout: 10_000 })
    const [published] = await Promise.all([
      responseFor(page, applicationBase(fixture, id) + '/versions', 'POST'),
      page
        .getByRole('dialog')
        .getByRole('button', { name: '发布', exact: true })
        .click({ timeout: 10_000 })
    ])
    expect(published.status()).toBe(201)
    const status = page.getByTestId('application-publication-status')
    await expect(status).toHaveAttribute('data-version-id', /^[a-f0-9-]{36}$/, { timeout: 10_000 })
    versionId = (await status.getAttribute('data-version-id'))!
    await checkpoint(directory, label + '-published', {
      applicationId: id,
      applicationVersionId: versionId,
      publicationRevision: '1'
    })
  }
  return { id, name, displayName, appKey: '', versionId, publicationRevision: publish ? '1' : '0' }
}
async function openApplication(page: Page, fixture: PublicFixture, application: Application) {
  const read = page.getByRole('button', { name: '读取应用目录', exact: true })
  await expect(read).toBeEnabled({ timeout: 10_000 })
  const [catalog] = await Promise.all([
    responseFor(page, applicationBase(fixture), 'GET'),
    read.click({ timeout: 10_000 })
  ])
  expect(catalog.status()).toBe(200)
  const row = applicationRow(page, application.name)
  await expect(row).toBeVisible({ timeout: 10_000 })
  const [draft] = await Promise.all([
    responseFor(page, applicationBase(fixture, application.id) + '/draft', 'GET'),
    row.getByRole('button', { name: '编辑', exact: true }).click({ timeout: 10_000 })
  ])
  expect(draft.status()).toBe(200)
  await expect(page.getByLabel('公开展示名', { exact: true })).toHaveValue(
    application.displayName,
    { timeout: 10_000 }
  )
}
async function openDeleteConfirmation(
  page: Page,
  fixture: PublicFixture,
  application: Application
) {
  const refresh = page.getByTestId('application-publication-refresh')
  await expect(refresh).toBeEnabled({ timeout: 10_000 })
  const [read] = await Promise.all([
    responseFor(page, applicationBase(fixture, application.id), 'GET'),
    refresh.click({ timeout: 10_000 })
  ])
  expect(read.status()).toBe(200)
  const trigger = page.getByTestId('application-publication-soft-delete')
  await expect(trigger).toBeEnabled({ timeout: 10_000 })
  await trigger.click({ timeout: 10_000 })
  const dialog = page.getByRole('dialog', { name: '确认软删除应用', exact: true })
  await expect(dialog).toBeVisible({ timeout: 10_000 })
  return dialog.getByRole('button', { name: '软删除', exact: true })
}
async function expectApplicationCleared(page: Page, application: Application, terminal: string) {
  await expect(page.getByTestId('application-delete-result')).toContainText(terminal, {
    timeout: 10_000
  })
  await expect(page.getByRole('region', { name: '应用草稿', exact: true })).toHaveCount(0)
  await expect(page.getByRole('region', { name: '应用发布与历史恢复', exact: true })).toHaveCount(0)
  await expect(page.getByLabel('公开展示名', { exact: true })).toHaveCount(0)
  await expect(page.getByLabel('看板', { exact: true })).toHaveCount(0)
  await expect(page.getByLabel('不可变版本', { exact: true })).toHaveCount(0)
  await expect(page.getByText('远端草稿（只读比较）', { exact: true })).toHaveCount(0)
  await expect(applicationRow(page, application.name)).toHaveCount(0, { timeout: 10_000 })
  await expect(page.getByTestId('application-publication-retry')).toHaveCount(0)
}

test('应用软删除真实204传播、保留独立看板分享授权及未发布应用原键恢复', async ({
  page
}, testInfo) => {
  test.setTimeout(240_000)
  page.setDefaultTimeout(10_000)
  test.skip(
    !process.env.E2E_APPLICATION_SOFT_DELETE_RUNTIME ||
      !process.env.E2E_APPLICATION_SOFT_DELETE_FIXTURE,
    '需要独占当前候选、受管4017宿主与公开项目材料'
  )
  const fixturePath = await ownedPath(process.env.E2E_APPLICATION_SOFT_DELETE_FIXTURE!),
    runtime = await ownedRuntime(testInfo.project.use.baseURL ?? '', fixturePath),
    fixture = JSON.parse(await readFile(fixturePath, 'utf8')) as PublicFixture
  if (
    !uuid(fixture.projectId) ||
    !uuid(fixture.tenantId) ||
    !fixture.projectKey ||
    /secret|token|password|downloadUrl/i.test(JSON.stringify(fixture)) ||
    fixture.expectedManagementNotFound.status !== 404 ||
    fixture.expectedManagementNotFound.code !== 60030 ||
    fixture.expectedAppUnavailable.status !== 404 ||
    fixture.expectedAppUnavailable.code !== 60023
  )
    throw new Error('应用软删公开材料不符合范围')
  const directory = resolve('logs', '017-b-public-checkpoint-' + randomUUID())
  await mkdir(directory)
  const deletions: Deletion[] = []
  page.on('request', (request) => {
    const match = new URL(request.url()).pathname.match(
      /^\/api\/v1\/projects\/[^/]+\/applications\/([^/]+)\/soft-delete$/
    )
    if (!match || request.method() !== 'POST') return
    const raw = request.postData() ?? ''
    let parsed: Record<string, unknown> = {}
    try {
      parsed = JSON.parse(raw)
    } catch {
      /* 只捕获闭集布尔值，不持久化正文。 */
    }
    deletions.push({
      id: match[1]!,
      key: request.headers()['idempotency-key'] ?? '',
      bodyHash: sha(raw),
      revision:
        typeof parsed.expectedPublicationRevision === 'string'
          ? parsed.expectedPublicationRevision
          : undefined,
      closedBody: Object.keys(parsed).join(',') === 'expectedPublicationRevision'
    })
  })
  let ownerToken = '',
    shareSecret = '',
    appAccess = '',
    appRefresh = ''
  try {
    marker('OWNED_HOST_AND_FIXTURE_VALIDATED', deletions.length)
    await login(page, OWNER_EMAIL, OWNER_PASSWORD)
    await enterProject(page, fixture.projectName)
    ownerToken = await page.evaluate(async () => {
      const path = '/src/store/modules/user.ts'
      return (await import(path)).useUserStore().accessToken
    })
    if (!ownerToken) throw new Error('管理身份仅RAM会话缺失')
    const management = (path: string, method = 'GET', body?: unknown) =>
      nativeHttp(path, { Authorization: `Bearer ${ownerToken}` }, method, body)
    const dashboard = await createPublished(page, fixture, 'retained-dashboard', directory),
      target = await createApplicationUi(page, fixture, dashboard, 'target', directory, true),
      unknown = await createApplicationUi(
        page,
        fixture,
        dashboard,
        'unknown-unpublished',
        directory,
        false
      )
    marker('REAL_UI_DASHBOARD_AND_PUBLISHED_AND_UNPUBLISHED_APPLICATIONS_READY', deletions.length)
    const catalog = await management(applicationBase(fixture, target.id))
    requireStatus(catalog, 200, 'APPLICATION_CATALOG')
    if (
      !/^app_[0-9a-f]{32}$/.test(catalog.value.appKey) ||
      catalog.value.currentVersionId !== target.versionId ||
      catalog.value.publicationRevision !== target.publicationRevision
    )
      throw new Error('真实应用公开发布身份不匹配')
    target.appKey = catalog.value.appKey
    await checkpoint(directory, 'target-public-identity', {
      applicationId: target.id,
      appKey: target.appKey,
      applicationVersionId: target.versionId,
      publicationRevision: target.publicationRevision
    })
    const configuration = await management(
      dashboardBase(fixture, dashboard.id) + '/shares/configuration'
    )
    requireStatus(configuration, 200, 'SHARE_CONFIGURATION')
    if (
      configuration.value.available !== true ||
      configuration.value.hostOrigin !== runtime.shareOrigin ||
      configuration.value.hostVersion !== runtime.hostVersion
    )
      throw new Error('分享资格与受控宿主不匹配')
    const share = await management(dashboardBase(fixture, dashboard.id) + '/shares', 'POST', {
      dashboardVersionId: dashboard.versionId,
      expectedDashboardPublicationRevision: dashboard.publicationRevision,
      expiresInSeconds: 600,
      refererPolicy: 'HOST_ORIGIN',
      hostCompatibility: configuration.value.hostCompatibility,
      variables: []
    })
    requireStatus(share, 201, 'SHARE_CREATE')
    if (!uuid(share.value.shareId) || !/^sh_[A-Za-z0-9_-]{43}$/.test(share.value.secret))
      throw new Error('独立分享公开身份或RAM能力无效')
    const shareId = share.value.shareId as string
    shareSecret = share.value.secret
    await checkpoint(directory, 'independent-share-created', {
      dashboardId: dashboard.id,
      shareId,
      status: 'ACTIVE'
    })
    const username = 'app_delete_' + randomBytes(8).toString('hex'),
      password = 'Aa!' + randomBytes(18).toString('hex')
    const user = await management(`/api/v1/projects/${fixture.projectId}/end-users`, 'POST', {
      username,
      password,
      displayName: '017-B READ用户'
    })
    requireStatus(user, 200, 'USER_PROVISION')
    const appUserId = user.value.id as string
    if (!uuid(appUserId)) throw new Error('真实终端用户公开身份无效')
    await checkpoint(directory, 'end-user-created', { appUserId })
    requireStatus(
      await management(
        `/api/v1/projects/${fixture.projectId}/end-users/${appUserId}/role`,
        'POST',
        { role: 'OBSERVER' }
      ),
      204,
      'USER_ROLE'
    )
    const grantPath = `/api/v1/projects/${fixture.projectId}/end-users/${appUserId}/dashboard-grants/${dashboard.id}`
    requireStatus(
      await management(grantPath, 'PUT', { expectedRevision: '0', status: 'ACTIVE' }),
      200,
      'READ_GRANT'
    )
    await checkpoint(directory, 'independent-grant-created', {
      appUserId,
      dashboardId: dashboard.id,
      status: 'ACTIVE',
      revision: '1'
    })
    const signedIn = await nativeHttp('/api/v1/app/auth/login', {}, 'POST', {
      projectKey: fixture.projectKey,
      username,
      password
    })
    requireStatus(signedIn, 200, 'APP_LOGIN')
    if (
      typeof signedIn.value.accessToken !== 'string' ||
      typeof signedIn.value.refreshToken !== 'string'
    )
      throw new Error('独立App仅RAM会话缺失')
    appAccess = signedIn.value.accessToken
    appRefresh = signedIn.value.refreshToken
    const appRoot = `/api/v1/app/applications/${target.appKey}`,
      appProbe = (kind: 'current' | 'schema' | 'resolve') =>
        nativeHttp(
          appRoot +
            (kind === 'schema'
              ? `/versions/${target.versionId}/dashboards/${dashboard.versionId}/schema?expectedPublicationRevision=${target.publicationRevision}`
              : '/' + kind),
          kind === 'resolve' ? {} : { Authorization: `Bearer ${appAccess}` }
        ),
      shareProbe = (kind: 'context' | 'schema') =>
        nativeHttp(`/api/v1/shares/${shareId}/${kind}`, {
          'X-Share-Token': shareSecret,
          Referer: runtime.shareOrigin + `/app/share/${shareId}`
        })
    for (const kind of ['current', 'schema', 'resolve'] as const) {
      const before = await appProbe(kind)
      requireStatus(before, 200, 'BEFORE_APP_' + kind.toUpperCase())
      if (kind === 'schema')
        expect(
          before.value.dashboardId === dashboard.id &&
            before.value.dashboardVersionId === dashboard.versionId &&
            JSON.stringify(before.value.schema).includes(dashboard.text)
        ).toBe(true)
    }
    const retained = new Map<string, string>()
    for (const suffix of ['', '/draft', '/versions', `/versions/${dashboard.versionId}`]) {
      const path = dashboardBase(fixture, dashboard.id) + suffix,
        value = await management(path)
      requireStatus(value, 200, 'BEFORE_DASHBOARD_METADATA')
      retained.set(path, sha(JSON.stringify(value.value)))
    }
    const beforeGrant = await management(grantPath)
    requireStatus(beforeGrant, 200, 'BEFORE_GRANT')
    retained.set(grantPath, sha(JSON.stringify(beforeGrant.value)))
    const shareHashes = new Map<'context' | 'schema', string>()
    for (const kind of ['context', 'schema'] as const) {
      const result = await shareProbe(kind)
      requireStatus(result, 200, 'BEFORE_INDEPENDENT_SHARE')
      shareHashes.set(kind, independentShareHash(kind, result.value))
    }
    marker('OLD_APP_RUNTIME_AND_INDEPENDENT_SHARE_AND_GRANT_ALL_READABLE', deletions.length)
    await openApplication(page, fixture, target)
    const compare = page.getByRole('button', { name: '读取远端比较', exact: true })
    const [comparison] = await Promise.all([
      responseFor(page, applicationBase(fixture, target.id) + '/draft', 'GET'),
      compare.click({ timeout: 10_000 })
    ])
    expect(comparison.status()).toBe(200)
    await expect(page.getByText('远端草稿（只读比较）', { exact: true })).toBeVisible({
      timeout: 10_000
    })
    const confirm = await openDeleteConfirmation(page, fixture, target)
    const [deleted] = await Promise.all([
      responseFor(page, applicationBase(fixture, target.id) + '/soft-delete', 'POST'),
      confirm.click({ timeout: 10_000 })
    ])
    expect(deleted.status()).toBe(204)
    const deleteHeaders = deleted.headers()
    expect(deleteHeaders.location === undefined).toBe(true)
    expect(
      deleteHeaders['content-length'] === undefined || deleteHeaders['content-length'] === '0'
    ).toBe(true)
    expect(deletions.length).toBe(1)
    expect(
      deletions[0]!.closedBody &&
        deletions[0]!.revision === target.publicationRevision &&
        !!deletions[0]!.key
    ).toBe(true)
    await expectApplicationCleared(page, target, '应用已软删除（收到204无正文回执）')
    await expect(applicationRow(page, unknown.name)).toBeVisible({ timeout: 10_000 })
    await checkpoint(directory, 'target-deleted', { applicationId: target.id, status: 204 })
    for (const suffix of ['', '/draft', '/versions', `/versions/${target.versionId}`]) {
      const gone = await management(applicationBase(fixture, target.id) + suffix)
      expect(gone.status).toBe(fixture.expectedManagementNotFound.status)
      expect(gone.code).toBe(fixture.expectedManagementNotFound.code)
    }
    for (const kind of ['current', 'schema', 'resolve'] as const) {
      const gone = await appProbe(kind)
      expect(gone.status).toBe(fixture.expectedAppUnavailable.status)
      expect(gone.code).toBe(fixture.expectedAppUnavailable.code)
    }
    for (const [path, hash] of retained) {
      const result = await management(path)
      requireStatus(result, 200, 'AFTER_INDEPENDENT_METADATA')
      expect(sha(JSON.stringify(result.value))).toBe(hash)
    }
    for (const [kind, hash] of shareHashes) {
      const result = await shareProbe(kind)
      requireStatus(result, 200, 'AFTER_INDEPENDENT_SHARE')
      expect(independentShareHash(kind, result.value)).toBe(hash)
    }
    const grantHistory = await management(
      `/api/v1/projects/${fixture.projectId}/end-users/${appUserId}/dashboard-grants`
    )
    requireStatus(grantHistory, 200, 'AFTER_GRANT_HISTORY')
    expect(
      grantHistory.value.items.some(
        (item: any) =>
          item.dashboardId === dashboard.id && item.status === 'ACTIVE' && item.revision === '1'
      )
    ).toBe(true)
    marker('APP_DELETION_HIDES_OLD_RUNTIME_RETAINING_DASHBOARD_SHARE_AND_GRANT', deletions.length)
    await openApplication(page, fixture, unknown)
    await expect(page.getByTestId('application-publication-status')).toHaveAttribute(
      'data-version-id',
      '',
      { timeout: 10_000 }
    )
    const unknownConfirm = await openDeleteConfirmation(page, fixture, unknown),
      cdp = await page.context().newCDPSession(page)
    let resolvePaused!: (value: { requestId: string; status: number }) => void,
      rejectPaused!: (failure: Error) => void
    const paused = new Promise<{ requestId: string; status: number }>((resolve, reject) => {
        resolvePaused = resolve
        rejectPaused = reject
      }),
      timer = setTimeout(() => rejectPaused(new Error('实际应用软删响应未进入有界暂停点')), 30_000)
    cdp.on('Fetch.requestPaused', (event: { requestId: string; responseStatusCode?: number }) => {
      void (async () => {
        try {
          if (event.responseStatusCode !== 204) {
            await cdp.send('Fetch.continueRequest', { requestId: event.requestId })
            rejectPaused(new Error('待中断的实际应用软删未成功'))
            return
          }
          resolvePaused({ requestId: event.requestId, status: event.responseStatusCode })
        } catch {
          rejectPaused(new Error('应用软删响应暂停失败'))
        }
      })()
    })
    try {
      await cdp.send('Fetch.enable', {
        patterns: [
          {
            urlPattern: '*' + applicationBase(fixture, unknown.id) + '/soft-delete',
            requestStage: 'Response'
          }
        ]
      })
      await unknownConfirm.click({ timeout: 10_000 })
      const held = await paused
      clearTimeout(timer)
      expect(held.status).toBe(204)
      await expect(page.getByTestId('application-delete-lock')).toContainText('删除结果待确认', {
        timeout: 10_000
      })
      await expect(page.getByLabel('公开展示名', { exact: true })).toBeDisabled({ timeout: 10_000 })
      await expect(page.getByTestId('application-save')).toBeDisabled({ timeout: 10_000 })
      await expect(page.getByTestId('application-create')).toBeDisabled({ timeout: 10_000 })
      await expect(page.getByTestId('application-directory-refresh')).toBeDisabled({
        timeout: 10_000
      })
      await expect(applicationRow(page, unknown.name)).toHaveCount(0, { timeout: 10_000 })
      marker('UNPUBLISHED_APP_ACTUAL204_PAUSED_WITH_EDIT_AND_NAVIGATION_LOCKED', deletions.length)
      // Chromium真实网络离线会触发原生offline；不手工派发DOM事件或伪造响应。
      await page.context().setOffline(true)
      await expect
        .poll(() => page.evaluate(() => navigator.onLine), { timeout: 10_000 })
        .toBe(false)
      await expect(page.getByTestId('application-error')).toContainText('已离线', {
        timeout: 10_000
      })
      await expect(page.getByLabel('公开展示名', { exact: true })).toHaveCount(0, {
        timeout: 10_000
      })
      await expect(
        page.getByRole('region', { name: '应用发布与历史恢复', exact: true })
      ).toBeVisible({ timeout: 10_000 })
      await expect(page.getByTestId('application-delete-lock')).toContainText('删除结果待确认', {
        timeout: 10_000
      })
      await cdp.send('Fetch.failRequest', {
        requestId: held.requestId,
        errorReason: 'ConnectionClosed'
      })
      await expect(page.getByTestId('application-publication-retry')).toBeVisible({
        timeout: 10_000
      })
      marker('REAL_BROWSER_OFFLINE_CLEARS_VIEW_AND_PRESERVES_LATE_DELETE_INTENT', deletions.length)
      await page.context().setOffline(false)
      await expect.poll(() => page.evaluate(() => navigator.onLine), { timeout: 10_000 }).toBe(true)
      await expect(page.getByTestId('application-publication-retry')).toBeEnabled({
        timeout: 10_000
      })
    } finally {
      clearTimeout(timer)
      await page.context().setOffline(false)
      await cdp.send('Fetch.disable')
      await cdp.detach()
    }
    await expect(page.getByTestId('application-delete-lock')).toContainText('删除结果待确认', {
      timeout: 10_000
    })
    await expect(page.getByLabel('公开展示名', { exact: true })).toHaveCount(0, { timeout: 10_000 })
    await expect(page.getByTestId('application-create')).toBeDisabled({ timeout: 10_000 })
    await expect(page.getByTestId('application-directory-refresh')).toBeDisabled({
      timeout: 10_000
    })
    await expect(applicationRow(page, unknown.name)).toHaveCount(0, { timeout: 10_000 })
    expect(deletions.length).toBe(2)
    const original = deletions[1]!
    expect(original.closedBody && original.revision === '0' && !!original.key).toBe(true)
    const gone = await management(applicationBase(fixture, unknown.id))
    expect(gone.status).toBe(404)
    expect(gone.code).toBe(60030)
    const refresh = page.getByTestId('application-publication-refresh')
    await expect(refresh).toBeEnabled({ timeout: 10_000 })
    const [read] = await Promise.all([
      responseFor(page, applicationBase(fixture, unknown.id), 'GET'),
      refresh.click({ timeout: 10_000 })
    ])
    expect(read.status()).toBe(404)
    await expect(page.getByTestId('application-delete-lock')).toContainText('删除结果待确认', {
      timeout: 10_000
    })
    await expect(page.getByLabel('公开展示名', { exact: true })).toHaveCount(0)
    await expect(page.getByLabel('看板', { exact: true })).toHaveCount(0)
    await expect(page.getByLabel('不可变版本', { exact: true })).toHaveCount(0)
    await expect(page.getByRole('region', { name: '应用发布与历史恢复', exact: true })).toBeVisible(
      { timeout: 10_000 }
    )
    const retry = page.getByTestId('application-publication-retry')
    await expect(retry).toBeEnabled({ timeout: 10_000 })
    expect(deletions.length).toBe(2)
    marker(
      'UNPUBLISHED_APP204_OFFLINE_INTERRUPTED_AND_READONLY404_PRESERVES_ORIGINAL_INTENT',
      deletions.length
    )
    const [restored] = await Promise.all([
      responseFor(page, applicationBase(fixture, unknown.id) + '/soft-delete', 'POST'),
      retry.click({ timeout: 10_000 })
    ])
    expect(restored.status()).toBe(409)
    expect(deletions.length).toBe(3)
    expect(
      deletions[2]!.key === original.key &&
        deletions[2]!.bodyHash === original.bodyHash &&
        deletions[2]!.id === original.id
    ).toBe(true)
    await expectApplicationCleared(page, unknown, '原软删除请求已完成；完成标记不重放原204回执。')
    await checkpoint(directory, 'unknown-completed', { applicationId: unknown.id, status: 10014 })
    const unchanged = await management(applicationBase(fixture, unknown.id))
    expect(unchanged.status).toBe(404)
    expect(unchanged.code).toBe(60030)
    // 原键不作为Playwright调用参数；storage只交给Node RAM计算布尔，不输出内容。
    let storageValues = await page.evaluate(() =>
      [localStorage, sessionStorage].flatMap((storage) =>
        Array.from({ length: storage.length }, (_, i) => storage.getItem(storage.key(i)!) ?? '')
      )
    )
    expect(storageValues.some((value) => deletions.some((item) => value.includes(item.key)))).toBe(
      false
    )
    storageValues = []
    marker('ORIGINAL_KEY10014_TERMINAL_WITHOUT_NEW_APP_DELETE_INTENT', deletions.length)
  } finally {
    if (appRefresh) {
      try {
        await nativeHttp('/api/v1/app/auth/logout', {}, 'POST', { refreshToken: appRefresh })
      } catch {
        /* 不回显私密会话且不掩盖主失败。 */
      }
    }
    ownerToken = ''
    shareSecret = ''
    appAccess = ''
    appRefresh = ''
    deletions.length = 0
  }
})
