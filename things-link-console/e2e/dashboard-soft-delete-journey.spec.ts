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
  expectedShareNotFound: { status: number; code: number }
  expectedAppSchemaNotFound: { status: number; code: number }
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
    await readFile(await ownedPath(process.env.E2E_DASHBOARD_SOFT_DELETE_RUNTIME!), 'utf8')
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
function responseFor(page: Page, path: string, method: 'GET' | 'POST') {
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
  const name = `017-A-${label}-${Date.now()}`,
    text = `017-A静态公开文字-${label}`
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
async function openDeleteConfirmation(page: Page, fixture: PublicFixture, dashboard: Dashboard) {
  const refresh = page.getByTestId('publication-refresh')
  await expect(refresh).toBeEnabled({ timeout: 10_000 })
  const [read] = await Promise.all([
    responseFor(page, dashboardBase(fixture, dashboard.id), 'GET'),
    refresh.click({ timeout: 10_000 })
  ])
  expect(read.status()).toBe(200)
  const trigger = page.getByTestId('publication-soft-delete')
  await expect(trigger).toBeEnabled({ timeout: 10_000 })
  await trigger.click({ timeout: 10_000 })
  const dialog = page.getByRole('dialog', { name: '确认软删除看板', exact: true })
  await expect(dialog).toBeVisible({ timeout: 10_000 })
  return dialog.getByRole('button', { name: '软删除', exact: true })
}
async function expectDesignerCleared(page: Page, dashboard: Dashboard, terminal: string) {
  await expect(page.getByTestId('designer-delete-result')).toContainText(terminal)
  await expect(page.getByTestId('designer-text-content')).toHaveCount(0)
  await expect(page.getByTestId('designer-add-text')).toHaveCount(0)
  await expect(page.getByRole('region', { name: '草稿设备数据预览', exact: true })).toHaveCount(0)
  await expect(page.getByRole('region', { name: '匿名只读分享管理', exact: true })).toHaveCount(0)
  await expect(page.getByTestId('grants-open')).toHaveCount(0)
  await expect(page.getByTestId('publication-soft-delete')).toHaveCount(0)
  await expect(page.getByTestId(`list-edit-${dashboard.id}`)).toHaveCount(0)
  await expect
    .poll(() => new URLSearchParams(new URL(page.url()).hash.split('?')[1]).has('dashboardId'), {
      timeout: 10_000
    })
    .toBe(false)
}

test('看板软删除真实204传播及断响应原键完成恢复', async ({ page }, testInfo) => {
  test.setTimeout(240_000)
  page.setDefaultTimeout(10_000)
  test.skip(
    !process.env.E2E_DASHBOARD_SOFT_DELETE_RUNTIME ||
      !process.env.E2E_DASHBOARD_SOFT_DELETE_FIXTURE,
    '需要独占当前候选、受管4017匿名宿主与公开项目材料'
  )
  const fixturePath = await ownedPath(process.env.E2E_DASHBOARD_SOFT_DELETE_FIXTURE!),
    runtime = await ownedRuntime(testInfo.project.use.baseURL ?? '', fixturePath),
    fixture = JSON.parse(await readFile(fixturePath, 'utf8')) as PublicFixture
  if (
    !uuid(fixture.projectId) ||
    !uuid(fixture.tenantId) ||
    !fixture.projectKey ||
    /secret|token|password|downloadUrl/i.test(JSON.stringify(fixture))
  )
    throw new Error('软删公开项目材料不符合范围')
  const directory = resolve('logs', '017-a-public-checkpoint-' + randomUUID())
  await mkdir(directory)
  const deletions: Deletion[] = []
  page.on('request', (request) => {
    const path = new URL(request.url()).pathname,
      match = path.match(/^\/api\/v1\/projects\/[^/]+\/dashboards\/([^/]+)\/soft-delete$/)
    if (!match || request.method() !== 'POST') return
    const raw = request.postData() ?? ''
    let parsed: Record<string, unknown> = {}
    try {
      parsed = JSON.parse(raw)
    } catch {
      /* 仅捕获闭集布尔值，不持久化请求正文。 */
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
    const target = await createPublished(page, fixture, 'target', directory),
      survivor = await createPublished(page, fixture, 'survivor', directory)
    marker('TWO_DASHBOARDS_CREATED_SAVED_AND_PUBLISHED_IN_UI', deletions.length)
    const configuration = await management(
      dashboardBase(fixture, target.id) + '/shares/configuration'
    )
    requireStatus(configuration, 200, 'SHARE_CONFIGURATION')
    if (
      configuration.value.available !== true ||
      configuration.value.hostOrigin !== runtime.shareOrigin ||
      configuration.value.hostVersion !== runtime.hostVersion
    )
      throw new Error('实际分享资格与受控匿名宿主不匹配')
    const share = await management(dashboardBase(fixture, target.id) + '/shares', 'POST', {
      dashboardVersionId: target.versionId,
      expectedDashboardPublicationRevision: target.publicationRevision,
      expiresInSeconds: 600,
      refererPolicy: 'HOST_ORIGIN',
      hostCompatibility: configuration.value.hostCompatibility,
      variables: []
    })
    requireStatus(share, 201, 'SHARE_CREATE')
    if (
      !uuid(share.value.shareId) ||
      typeof share.value.secret !== 'string' ||
      !/^sh_[A-Za-z0-9_-]{43}$/.test(share.value.secret)
    )
      throw new Error('原生HTTP分享能力响应无效')
    const shareId = share.value.shareId as string
    shareSecret = share.value.secret
    await checkpoint(directory, 'share-created', {
      dashboardId: target.id,
      shareId,
      status: 'ACTIVE'
    })
    const createdApp = await management(
      `/api/v1/projects/${fixture.projectId}/applications`,
      'POST',
      {
        managementName: '017-A双精确引用-' + Date.now(),
        content: {
          formatVersion: 'tc.application/v1',
          displayName: '017-A双精确引用',
          hostCompatibility: { minInclusive: '1.0.0', maxExclusive: '1.0.1' },
          dashboardRefs: [
            { dashboardId: target.id, dashboardVersionId: target.versionId, title: '待删精确版本' },
            {
              dashboardId: survivor.id,
              dashboardVersionId: survivor.versionId,
              title: '存活精确版本'
            }
          ],
          entryDashboardId: survivor.id
        }
      }
    )
    requireStatus(createdApp, 201, 'APP_CREATE')
    const applicationId = createdApp.value.id as string,
      appKey = createdApp.value.appKey as string
    if (!uuid(applicationId) || !/^app_[0-9a-f]{32}$/.test(appKey))
      throw new Error('真实应用身份无效')
    await checkpoint(directory, 'application-created', { applicationId, appKey })
    const publishedApp = await management(
      `/api/v1/projects/${fixture.projectId}/applications/${applicationId}/versions`,
      'POST',
      { expectedDraftRevision: '0', expectedPublicationRevision: '0' }
    )
    requireStatus(publishedApp, 201, 'APP_PUBLISH')
    const applicationVersionId = publishedApp.value.id as string
    if (!uuid(applicationVersionId)) throw new Error('真实应用版本身份无效')
    await checkpoint(directory, 'application-published', {
      applicationId,
      applicationVersionId,
      publicationRevision: '1'
    })
    const username = 'soft_delete_' + randomBytes(8).toString('hex'),
      password = 'Aa!' + randomBytes(18).toString('hex')
    const user = await management(`/api/v1/projects/${fixture.projectId}/end-users`, 'POST', {
      username,
      password,
      displayName: '017-A READ用户'
    })
    requireStatus(user, 200, 'USER_PROVISION')
    const appUserId = user.value.id as string
    if (!uuid(appUserId)) throw new Error('真实终端用户身份无效')
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
    for (const dashboard of [target, survivor]) {
      requireStatus(
        await management(
          `/api/v1/projects/${fixture.projectId}/end-users/${appUserId}/dashboard-grants/${dashboard.id}`,
          'PUT',
          { expectedRevision: '0', status: 'ACTIVE' }
        ),
        200,
        'READ_GRANT'
      )
      await checkpoint(directory, 'grant-' + (dashboard.id === target.id ? 'target' : 'survivor'), {
        appUserId,
        dashboardId: dashboard.id,
        status: 'ACTIVE',
        revision: '1'
      })
    }
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
      throw new Error('App独立RAM会话缺失')
    appAccess = signedIn.value.accessToken
    appRefresh = signedIn.value.refreshToken
    const shareProbe = (kind: 'context' | 'schema') =>
      nativeHttp(`/api/v1/shares/${shareId}/${kind}`, {
        'X-Share-Token': shareSecret,
        Referer: runtime.shareOrigin + `/app/share/${shareId}`
      })
    const appProbe = (dashboard?: Dashboard) =>
      nativeHttp(
        `/api/v1/app/applications/${appKey}` +
          (dashboard
            ? `/versions/${applicationVersionId}/dashboards/${dashboard.versionId}/schema?expectedPublicationRevision=1`
            : '/current'),
        { Authorization: `Bearer ${appAccess}` }
      )
    for (const kind of ['context', 'schema'] as const)
      requireStatus(await shareProbe(kind), 200, 'BEFORE_SHARE_' + kind.toUpperCase())
    const beforeTarget = await appProbe(target),
      beforeSurvivor = await appProbe(survivor)
    requireStatus(beforeTarget, 200, 'BEFORE_APP_TARGET')
    requireStatus(beforeSurvivor, 200, 'BEFORE_APP_SURVIVOR')
    requireStatus(await appProbe(), 200, 'BEFORE_APP_CURRENT')
    expect(
      beforeTarget.value.dashboardId === target.id &&
        beforeTarget.value.dashboardVersionId === target.versionId &&
        JSON.stringify(beforeTarget.value.schema).includes(target.text)
    ).toBe(true)
    const appVersionPath = `/api/v1/projects/${fixture.projectId}/applications/${applicationId}/versions/${applicationVersionId}`,
      appCatalogPath = `/api/v1/projects/${fixture.projectId}/applications/${applicationId}`,
      grantPath = `/api/v1/projects/${fixture.projectId}/end-users/${appUserId}/dashboard-grants/${target.id}`
    const beforeVersion = await management(appVersionPath),
      beforeCatalog = await management(appCatalogPath),
      beforeGrant = await management(grantPath)
    for (const result of [beforeVersion, beforeCatalog, beforeGrant])
      requireStatus(result, 200, 'BEFORE_RETAINED_METADATA')
    const retainedVersionHash = sha(JSON.stringify(beforeVersion.value)),
      retainedCatalogHash = sha(JSON.stringify(beforeCatalog.value)),
      retainedGrantHash = sha(JSON.stringify(beforeGrant.value)),
      survivorHash = sha(JSON.stringify(beforeSurvivor.value))
    marker('REAL_SHARE_AND_APP_SCHEMAS_READABLE_WITH_ACTIVE_GRANTS', deletions.length)
    await page.goto(`/#/dashboard/designer?dashboardId=${target.id}`)
    const targetTextComponent = page
      .getByTestId('designer-canvas')
      .locator('article[data-kind="TEXT"]')
      .filter({ hasText: target.text })
    await expect(targetTextComponent).toBeVisible({ timeout: 10_000 })
    await targetTextComponent.click({ timeout: 10_000 })
    await expect(page.getByTestId('designer-text-content')).toHaveValue(target.text, {
      timeout: 10_000
    })
    const confirm = await openDeleteConfirmation(page, fixture, target)
    const [deleted] = await Promise.all([
      responseFor(page, dashboardBase(fixture, target.id) + '/soft-delete', 'POST'),
      confirm.click({ timeout: 10_000 })
    ])
    expect(deleted.status()).toBe(204)
    const deleteHeaders = deleted.headers()
    expect(deleteHeaders['location'] === undefined).toBe(true)
    expect(
      deleteHeaders['content-length'] === undefined || deleteHeaders['content-length'] === '0'
    ).toBe(true)
    expect(deletions.length).toBe(1)
    expect(
      deletions[0]!.closedBody &&
        deletions[0]!.revision === target.publicationRevision &&
        !!deletions[0]!.key
    ).toBe(true)
    await expectDesignerCleared(page, target, '收到204无正文回执')
    await expect(page.getByTestId(`list-edit-${survivor.id}`)).toBeVisible({ timeout: 10_000 })
    await checkpoint(directory, 'target-deleted', { dashboardId: target.id, status: 204 })
    for (const suffix of ['', '/draft', '/versions', `/versions/${target.versionId}`]) {
      const gone = await management(dashboardBase(fixture, target.id) + suffix)
      expect(gone.status).toBe(fixture.expectedManagementNotFound.status)
      expect(gone.code).toBe(fixture.expectedManagementNotFound.code)
    }
    for (const kind of ['context', 'schema'] as const) {
      const gone = await shareProbe(kind)
      expect(gone.status).toBe(fixture.expectedShareNotFound.status)
      expect(gone.code).toBe(fixture.expectedShareNotFound.code)
    }
    const goneSchema = await appProbe(target)
    expect(goneSchema.status).toBe(fixture.expectedAppSchemaNotFound.status)
    expect(goneSchema.code).toBe(fixture.expectedAppSchemaNotFound.code)
    const survivorAfter = await appProbe(survivor)
    requireStatus(survivorAfter, 200, 'AFTER_APP_SURVIVOR')
    expect(sha(JSON.stringify(survivorAfter.value))).toBe(survivorHash)
    requireStatus(await appProbe(), 200, 'AFTER_APP_CURRENT')
    for (const [path, hash] of [
      [appVersionPath, retainedVersionHash],
      [appCatalogPath, retainedCatalogHash],
      [grantPath, retainedGrantHash]
    ]) {
      const retained = await management(path!)
      requireStatus(retained, 200, 'AFTER_RETAINED_METADATA')
      expect(sha(JSON.stringify(retained.value))).toBe(hash)
    }
    const historyGrant = await management(
      `/api/v1/projects/${fixture.projectId}/end-users/${appUserId}/dashboard-grants`
    )
    requireStatus(historyGrant, 200, 'AFTER_GRANT_HISTORY')
    expect(
      historyGrant.value.items.some(
        (item: any) =>
          item.dashboardId === target.id && item.status === 'ACTIVE' && item.revision === '1'
      )
    ).toBe(true)
    marker('DELETION_REJECTS_OLD_SHARE_AND_EXACT_APP_SCHEMA_RETAINING_HISTORY', deletions.length)
    // 另一个全新看板实际完成204后仅丢传输；不更换请求键，不改库或伪造服务端结果。
    const unknown = await createPublished(page, fixture, 'unknown', directory),
      unknownConfirm = await openDeleteConfirmation(page, fixture, unknown),
      cdp = await page.context().newCDPSession(page)
    let resolvePaused!: (status: number) => void, rejectPaused!: (failure: Error) => void
    const paused = new Promise<number>((resolve, reject) => {
        resolvePaused = resolve
        rejectPaused = reject
      }),
      timer = setTimeout(() => rejectPaused(new Error('实际软删响应未进入有界中断点')), 20_000)
    cdp.on('Fetch.requestPaused', (event: { requestId: string; responseStatusCode?: number }) => {
      void (async () => {
        try {
          if (event.responseStatusCode !== 204) {
            await cdp.send('Fetch.continueRequest', { requestId: event.requestId })
            rejectPaused(new Error('待中断的实际软删未成功'))
            return
          }
          await cdp.send('Fetch.failRequest', {
            requestId: event.requestId,
            errorReason: 'ConnectionClosed'
          })
          resolvePaused(event.responseStatusCode)
        } catch {
          rejectPaused(new Error('软删响应中断失败'))
        }
      })()
    })
    try {
      await cdp.send('Fetch.enable', {
        patterns: [
          {
            urlPattern: '*' + dashboardBase(fixture, unknown.id) + '/soft-delete',
            requestStage: 'Response'
          }
        ]
      })
      await unknownConfirm.click({ timeout: 10_000 })
      expect(await paused).toBe(204)
    } finally {
      clearTimeout(timer)
      await cdp.send('Fetch.disable')
      await cdp.detach()
    }
    await expect(page.getByTestId('designer-delete-lock')).toContainText('删除结果待确认')
    await expect(page.getByTestId('designer-text-content')).toBeDisabled()
    await expect(page.getByTestId('designer-add-text')).toBeDisabled()
    await expect(page.getByTestId('publication-retry')).toBeVisible({ timeout: 10_000 })
    expect(deletions.length).toBe(2)
    const original = deletions[1]!
    expect(
      original.closedBody && original.revision === unknown.publicationRevision && !!original.key
    ).toBe(true)
    const gone = await management(dashboardBase(fixture, unknown.id))
    expect(gone.status).toBe(404)
    expect(gone.code).toBe(60034)
    marker('ACTUAL204_INTERRUPTED_AND_READONLY404_CONFIRMED', deletions.length)
    const refresh = page.getByTestId('publication-refresh')
    await expect(refresh).toBeEnabled({ timeout: 10_000 })
    const [read] = await Promise.all([
      responseFor(page, dashboardBase(fixture, unknown.id), 'GET'),
      refresh.click({ timeout: 10_000 })
    ])
    expect(read.status()).toBe(404)
    const retry = page.getByTestId('publication-retry')
    await expect(retry).toBeEnabled({ timeout: 10_000 })
    expect(deletions.length).toBe(2)
    const [restored] = await Promise.all([
      responseFor(page, dashboardBase(fixture, unknown.id) + '/soft-delete', 'POST'),
      retry.click({ timeout: 10_000 })
    ])
    expect(restored.status()).toBe(409)
    expect(deletions.length).toBe(3)
    expect(
      deletions[2]!.key === original.key &&
        deletions[2]!.bodyHash === original.bodyHash &&
        deletions[2]!.id === original.id
    ).toBe(true)
    await expectDesignerCleared(page, unknown, '原软删除请求已完成；完成标记不重放原204回执。')
    await expect(page.getByTestId(`list-edit-${survivor.id}`)).toBeVisible({ timeout: 10_000 })
    await expect(page.getByTestId('publication-retry')).toHaveCount(0)
    await checkpoint(directory, 'unknown-completed', { dashboardId: unknown.id, status: 10014 })
    const unchanged = await management(dashboardBase(fixture, unknown.id))
    expect(unchanged.status).toBe(404)
    expect(unchanged.code).toBe(60034)
    const persisted = await page.evaluate(
      (keys) =>
        [localStorage, sessionStorage].some((storage) =>
          Array.from({ length: storage.length }, (_, i) => storage.getItem(storage.key(i)!)).some(
            (value) => keys.some((key) => value?.includes(key))
          )
        ),
      deletions.map((item) => item.key)
    )
    expect(persisted).toBe(false)
    marker('ORIGINAL_KEY10014_TERMINAL_WITHOUT_NEW_DELETE_INTENT', deletions.length)
  } finally {
    if (appRefresh) {
      try {
        await nativeHttp('/api/v1/app/auth/logout', {}, 'POST', { refreshToken: appRefresh })
      } catch {
        /* 固定HTTP异常不回显私密会话，也不掩盖主失败。 */
      }
    }
    ownerToken = ''
    shareSecret = ''
    appAccess = ''
    appRefresh = ''
  }
})
