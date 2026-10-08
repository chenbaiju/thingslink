import { expect, test, type Download, type Page } from '@playwright/test'
import { createHash, randomUUID } from 'node:crypto'
import { execFileSync, spawnSync } from 'node:child_process'
import { mkdir, readFile, realpath } from 'node:fs/promises'
import { isAbsolute, relative, resolve } from 'node:path'
import { login, enterProject, OWNER_EMAIL, OWNER_PASSWORD } from './helpers'

interface ReleaseFixture {
  projectId: string
  tenantId: string
  projectName: string
  deviceTypeId: string
  firmwareId: string
  firmwareVersion: string
  sha256Hex: string
  sizeBytes: number
  objectVersionId: string
  contentBytesBase64: string
}

const signedAddress =
  /X-Amz-(?:Signature|Credential|Algorithm|Expires)|[?&](?:Signature|AWSAccessKeyId)=/i

// 管理票据是短时bearer秘密，禁用所有网络、像素和自动失败媒体记录。
test.use({ trace: 'off', video: 'off', screenshot: 'off' })
test.afterEach(async ({ page }) => {
  await page.close()
})

async function ownedPath(path: string) {
  const root = await realpath(resolve('logs'))
  const target = await realpath(resolve(path))
  const suffix = relative(root, target)
  if (!suffix || isAbsolute(suffix) || suffix === '..' || suffix.startsWith('../'))
    throw new Error('拒绝专用日志目录之外的下载夹具或制品')
  return target
}

async function qualifyRuntime(baseURL: string) {
  const runtime = JSON.parse(
    await readFile(await ownedPath(process.env.E2E_OTA_RELEASE_DOWNLOAD_RUNTIME!), 'utf8')
  )
  const origin = new URL(baseURL)
  if (
    !/^tc_console_[A-Za-z0-9_]+$/.test(runtime.database) ||
    runtime.postgresContainer !== 'tc-console-012a-pg' ||
    runtime.postgresPort !== 5547 ||
    runtime.backendPort !== 8088 ||
    runtime.redisDatabase !== 14 ||
    runtime.vitePort !== 3017 ||
    !Number.isSafeInteger(runtime.pid) ||
    runtime.pid <= 1 ||
    !/^[0-9a-f]{64}$/.test(runtime.jarSha) ||
    origin.protocol !== 'http:' ||
    !['localhost', '127.0.0.1'].includes(origin.hostname) ||
    origin.port !== '3017'
  )
    throw new Error('拒绝非独占发布物下载运行栈')
  const jar = await ownedPath(runtime.frozenJar)
  if (
    createHash('sha256')
      .update(await readFile(jar))
      .digest('hex') !== runtime.jarSha
  )
    throw new Error('发布物下载冻结JAR已漂移')
  try {
    const command = execFileSync('ps', ['-p', String(runtime.pid), '-o', 'command='], {
      encoding: 'utf8'
    })
    const listener = execFileSync(
      'lsof',
      ['-nP', '-a', '-p', String(runtime.pid), '-iTCP:8088', '-sTCP:LISTEN', '-t'],
      { encoding: 'utf8' }
    )
    if (!command.includes(jar) || listener.trim() !== String(runtime.pid)) throw new Error('owner')
  } catch {
    throw new Error('独占后端进程或监听归属不符')
  }
}

/** 包装浏览器原始异常，避免其调用日志或自动ARIA诊断包含供应商地址。 */
async function publicAction(action: () => Promise<unknown>) {
  try {
    await action()
  } catch {
    throw new Error('发布物下载页面操作未完成，原始诊断不留存')
  }
}

/** 只返回布尔事实，禁止把页面文本、地址、storage值带入Playwright值断言。 */
async function uiFact(page: Page, selector: string, state: 'visible' | 'absent') {
  try {
    return await page.evaluate(
      ({ selector, state }) => {
        const element = document.querySelector<HTMLElement>(selector)
        const visible = !!element && element.getClientRects().length > 0
        return state === 'visible' ? visible : !visible
      },
      { selector, state }
    )
  } catch {
    throw new Error('页面公开状态读取失败')
  }
}

async function noPersistentAddress(page: Page) {
  try {
    return await page.evaluate(() => {
      const pattern =
        /X-Amz-(?:Signature|Credential|Algorithm|Expires)|[?&](?:Signature|AWSAccessKeyId)=/i
      return (
        !pattern.test(document.documentElement.outerHTML) &&
        !pattern.test(location.href) &&
        ![...Object.values(localStorage), ...Object.values(sessionStorage)].some((value) =>
          pattern.test(value)
        )
      )
    })
  } catch {
    throw new Error('票据持久化边界读取失败')
  }
}

/** 仅在Node内存比较本轮真实票据；任何读取失败都不得解释为没有泄露。 */
async function ticketLogPrivacy(ticketUrl: string, startedAt: string) {
  try {
    const signature = new URL(ticketUrl).searchParams.get('X-Amz-Signature') ?? ''
    if (!/^[0-9a-f]{64}$/.test(signature)) throw new Error('signature')
    const absent = (content: string) => !content.includes(ticketUrl) && !content.includes(signature)
    const service = await Promise.all(
      ['info', 'warn', 'error', 'debug'].map(async (level) =>
        readFile(await ownedPath(`logs/016-b-service/${level}/${level}.log`), 'utf8')
      )
    )
    const stdout = (
      await Promise.all(
        ['logs/016-b-backend.log', 'logs/016-b-backend-bucket.log'].map(async (file) =>
          readFile(await ownedPath(file), 'utf8')
        )
      )
    ).join('\n')
    // spawnSync显式捕获两流，避免execFileSync错误分支把Docker日志stderr打印给报告。
    const minio = spawnSync('docker', ['logs', '--since', startedAt, 'tc-minio'], {
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'pipe'],
      timeout: 10_000,
      maxBuffer: 32 * 1024 * 1024
    })
    if (
      minio.error ||
      minio.signal ||
      minio.status !== 0 ||
      typeof minio.stdout !== 'string' ||
      typeof minio.stderr !== 'string'
    )
      throw new Error('collection')
    return {
      backendService: service.every(absent),
      backendStdout: absent(stdout),
      minioSinceStart: absent(minio.stdout) && absent(minio.stderr)
    }
  } catch {
    throw new Error('本轮票据日志保密证据采集失败；不输出日志、地址或签名')
  }
}

test('已发布固件真实下载固定版本字节、票据清理及60秒自然到期拒绝', async ({ page }, testInfo) => {
  test.setTimeout(180_000)
  const startedAt = new Date().toISOString()
  test.skip(
    !process.env.E2E_OTA_RELEASE_DOWNLOAD_RUNTIME || !process.env.E2E_OTA_RELEASE_DOWNLOAD_FIXTURE,
    '需要显式独占运行栈及真实READY发布物公开夹具'
  )
  await qualifyRuntime(testInfo.project.use.baseURL ?? '')
  const fixture = JSON.parse(
    await readFile(await ownedPath(process.env.E2E_OTA_RELEASE_DOWNLOAD_FIXTURE!), 'utf8')
  ) as ReleaseFixture
  const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
  const expectedBytes = Buffer.from(fixture.contentBytesBase64 ?? '', 'base64')
  if (
    ![fixture.projectId, fixture.tenantId, fixture.deviceTypeId, fixture.firmwareId].every(
      (v) => typeof v === 'string' && uuid.test(v)
    ) ||
    !fixture.projectName ||
    !fixture.firmwareVersion ||
    !fixture.objectVersionId ||
    !Number.isSafeInteger(fixture.sizeBytes) ||
    fixture.sizeBytes <= 0 ||
    fixture.sizeBytes !== expectedBytes.byteLength ||
    !/^[0-9a-f]{64}$/.test(fixture.sha256Hex) ||
    createHash('sha256').update(expectedBytes).digest('hex') !== fixture.sha256Hex
  )
    throw new Error('发布物公开夹具字节或身份不一致')
  await publicAction(() => login(page, OWNER_EMAIL, OWNER_PASSWORD))
  await publicAction(() => enterProject(page, fixture.projectName))
  const prepared = await page.evaluate(
    async (input) => {
      const userPath = '/src/store/modules/user.ts',
        otaPath = '/src/api/ota.ts'
      const user = (await import(userPath)).useUserStore()
      const detail = await (
        await import(otaPath)
      ).fetchOtaFirmware(input.projectId, input.firmwareId)
      return (
        user.info.currentProjectId === input.projectId &&
        user.info.tenantId === input.tenantId &&
        detail.id === input.firmwareId &&
        detail.deviceTypeId === input.deviceTypeId &&
        detail.firmwareVersion === input.firmwareVersion &&
        detail.status === 'READY'
      )
    },
    {
      projectId: fixture.projectId,
      tenantId: fixture.tenantId,
      firmwareId: fixture.firmwareId,
      deviceTypeId: fixture.deviceTypeId,
      firmwareVersion: fixture.firmwareVersion
    }
  )
  expect(prepared).toBe(true)

  let addressLeak = false,
    signedNavigation = false,
    posts = 0,
    ticketUrl = ''
  const openedPages: Page[] = []
  let acceptDownload: ((download: Download) => void) | undefined
  const downloaded = new Promise<Download>((accept) => {
    acceptDownload = accept
  })
  const observe = (surface: Page) => {
    surface.on('download', (download) => acceptDownload?.(download))
    surface.on('console', (event) => {
      if (signedAddress.test(event.text())) addressLeak = true
    })
    surface.on('pageerror', (error) => {
      if (signedAddress.test(error.message)) addressLeak = true
    })
    surface.on('framenavigated', (frame) => {
      if (signedAddress.test(frame.url())) signedNavigation = true
    })
  }
  observe(page)
  page.context().on('page', (surface) => {
    openedPages.push(surface)
    observe(surface)
  })
  const issuancePath = `/api/v1/projects/${fixture.projectId}/ota/firmwares/${fixture.firmwareId}/release/downloads`
  page.on('request', (request) => {
    if (new URL(request.url()).pathname === issuancePath && request.method() === 'POST') posts++
  })
  try {
    await publicAction(() => page.goto('/#/ota/firmwares'))
    const row = page.getByRole('row').filter({ hasText: fixture.firmwareVersion })
    await publicAction(() => row.getByTestId('ota-release-download-open').click())
    await expect
      .poll(() => uiFact(page, '[data-testid="ota-release-download-dialog"]', 'visible'))
      .toBe(true)
    expect(await noPersistentAddress(page)).toBe(true)
    const responsePromise = page.waitForResponse(
      (response) =>
        new URL(response.url()).pathname === issuancePath && response.request().method() === 'POST'
    )
    const issuedAt = Date.now()
    await publicAction(() => page.getByTestId('ota-release-download-issue').click())
    let expiresAt = 0,
      signatureExpiresAt = 0
    try {
      const response = await responsePromise
      if (response.status() !== 200) throw new Error('issue')
      const ticket = await response.json()
      ticketUrl = ticket.downloadUrl
      const url = new URL(ticketUrl)
      const signedAt = url.searchParams.get('X-Amz-Date') ?? ''
      const date = /^(\d{4})(\d{2})(\d{2})T(\d{2})(\d{2})(\d{2})Z$/.exec(signedAt)
      expiresAt = Date.parse(ticket.expiresAt)
      if (
        !date ||
        ticket.firmwareId !== fixture.firmwareId ||
        !['localhost', '127.0.0.1'].includes(url.hostname) ||
        url.protocol !== 'http:' ||
        url.username ||
        url.password ||
        !url.searchParams.has('X-Amz-Signature') ||
        url.searchParams.get('X-Amz-Expires') !== '60' ||
        url.searchParams.get('versionId') !== fixture.objectVersionId ||
        !Number.isFinite(expiresAt) ||
        expiresAt - issuedAt < 55_000 ||
        expiresAt - issuedAt > 65_000
      )
        throw new Error('ticket')
      signatureExpiresAt =
        Date.UTC(+date[1], +date[2] - 1, +date[3], +date[4], +date[5], +date[6]) + 60_000
    } catch {
      throw new Error('管理票据固定版本、60秒TTL或签发状态不符')
    }

    let timer: ReturnType<typeof setTimeout> | undefined
    let download: Download
    try {
      download = await Promise.race([
        downloaded,
        new Promise<never>((_, reject) => {
          timer = setTimeout(() => reject(new Error('download timeout')), 20_000)
        })
      ])
      const directory = resolve('logs/016-b-downloads')
      await mkdir(directory, { recursive: true })
      const output = resolve(directory, `${randomUUID()}.bin`)
      await download.saveAs(output)
      const bytes = await readFile(output)
      expect(bytes.byteLength === fixture.sizeBytes).toBe(true)
      expect(createHash('sha256').update(bytes).digest('hex') === fixture.sha256Hex).toBe(true)
      expect(bytes.equals(expectedBytes)).toBe(true)
    } catch {
      throw new Error('真实浏览器下载未完成或固定版本字节不符，原始诊断不留存')
    } finally {
      if (timer) clearTimeout(timer)
    }
    expect(signedNavigation).toBe(false)
    expect(addressLeak).toBe(false)
    expect(posts).toBe(1)
    expect(await noPersistentAddress(page)).toBe(true)
    await expect
      .poll(() => uiFact(page, '[data-testid="ota-release-download-expiry"]', 'visible'))
      .toBe(true)
    await publicAction(() =>
      page
        .getByTestId('ota-release-download-dialog')
        .getByRole('button', { name: '关闭', exact: true })
        .click()
    )
    await expect
      .poll(() => uiFact(page, '[data-testid="ota-release-download-dialog"]', 'absent'))
      .toBe(true)
    await publicAction(() => row.getByTestId('ota-release-download-open').click())
    await expect
      .poll(() => uiFact(page, '[data-testid="ota-release-download-dialog"]', 'visible'))
      .toBe(true)
    expect(await uiFact(page, '[data-testid="ota-release-download-expiry"]', 'absent')).toBe(true)
    expect(await noPersistentAddress(page)).toBe(true)
    expect(posts).toBe(1)

    // 不缩短签名TTL、不改变时钟；分段等待真实票据和存储签名均自然到期。
    const deadline = Math.max(expiresAt, signatureExpiresAt) + 2_000
    console.info('[OTA下载] 等待真实60秒管理票据自然到期')
    while (Date.now() < deadline)
      await new Promise((done) => setTimeout(done, Math.min(10_000, deadline - Date.now())))
    let status = 0,
      expiredReason = false
    try {
      const response = await fetch(ticketUrl, {
        method: 'GET',
        credentials: 'omit',
        redirect: 'error',
        signal: AbortSignal.timeout(10_000)
      })
      status = response.status
      expiredReason = /Request has expired|ExpiredToken|RequestExpired/i.test(await response.text())
    } catch {
      throw new Error('管理票据到期后的真实存储请求未完成，地址不留存')
    }
    expect(status).toBe(403)
    expect(expiredReason).toBe(true)
    expect(await noPersistentAddress(page)).toBe(true)
    expect(signedNavigation).toBe(false)
    expect(addressLeak).toBe(false)
    expect(posts).toBe(1)
    const logPrivacy = await ticketLogPrivacy(ticketUrl, startedAt)
    expect(logPrivacy.backendService).toBe(true)
    expect(logPrivacy.backendStdout).toBe(true)
    expect(logPrivacy.minioSinceStart).toBe(true)
  } finally {
    ticketUrl = ''
    acceptDownload = undefined
    for (const surface of openedPages) {
      try {
        await surface.close()
      } catch {
        /* 弹出页已关闭时无需重复关闭。 */
      }
    }
  }
})
