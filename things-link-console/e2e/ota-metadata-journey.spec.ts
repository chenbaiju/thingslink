import { expect, test, type Locator } from '@playwright/test'
import { createHash } from 'node:crypto'
import { execFileSync } from 'node:child_process'
import { readFile, realpath } from 'node:fs/promises'
import { isAbsolute, relative, resolve } from 'node:path'
import type { components } from '../src/types/api/schema'
import { login, enterProject, OWNER_EMAIL, OWNER_PASSWORD } from './helpers'

type TrustKey = components['schemas']['OtaTrustKeyResponse']
type Baseline = components['schemas']['OtaTypeBaselineVersionResponse']
type ImportEnvelope = components['schemas']['OtaTrustImportRequest']
interface DomainFixture {
  trustDomain: string
  bundleVersion: string
  bundleSha256: string
  keys: TrustKey[]
}
interface MetadataFixture {
  projectId: string
  tenantId: string
  projectName: string
  deviceTypeId: string
  typeName: string
  emptyDeviceTypeId: string
  emptyTypeName: string
  emptyProjectName: string
  domains: DomainFixture[]
  baseline: Baseline
  baselines?: Baseline[]
  rotationFixtures: {
    signedEnvelope: ImportEnvelope
    metadata: { bundleVersion: string; keys: TrustKey[] }
  }[]
}

// 登录口令与测试材料不进入失败像素、网络trace或ARIA快照。
test.use({ trace: 'off', video: 'off', screenshot: 'off' })
test.afterEach(async ({ page }) => {
  await page.close()
})

async function ownedLogPath(path: string) {
  const root = await realpath(resolve('logs'))
  const target = await realpath(resolve(path))
  const suffix = relative(root, target)
  if (!suffix || isAbsolute(suffix) || suffix === '..' || suffix.startsWith('../'))
    throw new Error('拒绝专用日志目录之外的OTA夹具或构件')
  return target
}

async function checkOwnedRuntime(baseURL: string) {
  const path = await ownedLogPath(process.env.E2E_OTA_METADATA_RUNTIME!)
  const runtime = JSON.parse(await readFile(path, 'utf8'))
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
    throw new Error('拒绝非专用OTA元数据运行栈')
  const jar = await ownedLogPath(runtime.frozenJar)
  const hash = createHash('sha256')
    .update(await readFile(jar))
    .digest('hex')
  if (hash !== runtime.jarSha) throw new Error('OTA元数据冻结JAR已漂移')
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
    throw new Error('冻结OTA后端进程或8088监听所有权不符')
  }
}

async function expectKeys(table: Locator, keys: TrustKey[]) {
  const states = { PREPARED: '待启用', ACTIVE: '使用中', VERIFY_ONLY: '仅验签', REVOKED: '已撤销' }
  await expect(table.locator('tbody tr')).toHaveCount(keys.length)
  for (const key of keys) {
    const row = table.getByRole('row').filter({
      has: table.page().getByRole('cell', { name: key.keyVersion, exact: true })
    })
    await expect(row).toHaveCount(1)
    await expect(row).toContainText(key.fingerprint)
    await expect(row).toContainText(states[key.state])
    await expect(row).toContainText(key.signatureProfile)
    await expect(row).toContainText(new Date(key.notBefore * 1000).toISOString())
    await expect(row).toContainText(new Date(key.notAfter * 1000).toISOString())
    await expect(row).toContainText('不含终点')
  }
}

test('OTA公开信任域、发布键和类型基线真实读取、分页、换包及作用域清理', async ({
  page
}, testInfo) => {
  test.setTimeout(180_000)
  test.skip(
    !process.env.E2E_OTA_METADATA_RUNTIME || !process.env.E2E_OTA_METADATA_FIXTURE,
    '需要显式独占栈、冻结候选及已真实登记的公开OTA夹具'
  )
  await checkOwnedRuntime(testInfo.project.use.baseURL ?? '')
  const fixture = JSON.parse(
    await readFile(await ownedLogPath(process.env.E2E_OTA_METADATA_FIXTURE!), 'utf8')
  ) as MetadataFixture
  const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
  if (
    ![fixture.projectId, fixture.tenantId, fixture.deviceTypeId, fixture.emptyDeviceTypeId].every(
      (value) => typeof value === 'string' && uuid.test(value)
    ) ||
    !fixture.projectName ||
    !fixture.typeName ||
    !fixture.emptyTypeName ||
    !fixture.emptyProjectName ||
    !Array.isArray(fixture.domains) ||
    fixture.domains.length !== 51 ||
    !Array.isArray(fixture.rotationFixtures) ||
    !/^[0-9a-f]{64}$/.test(fixture.baseline?.baselineHash)
  )
    throw new Error('OTA公开夹具不满足真实分页与基线合同')

  await login(page, OWNER_EMAIL, OWNER_PASSWORD)
  await enterProject(page, fixture.projectName)
  const identity = await page.evaluate(async () => {
    const path = '/src/store/modules/user.ts'
    const user = (await import(path)).useUserStore()
    return { projectId: user.info.currentProjectId, tenantId: user.info.tenantId }
  })
  expect(identity).toEqual({ projectId: fixture.projectId, tenantId: fixture.tenantId })

  const domain = fixture.domains[0]
  const current = await page.evaluate(
    async ({ projectId, trustDomain }) => {
      const path = '/src/utils/http/index.ts'
      const http = (await import(path)).default
      return http.get({
        url: `/api/v1/projects/${projectId}/ota/trust-domains/${encodeURIComponent(trustDomain)}`,
        showErrorMessage: false
      }) as Promise<{ revision: string; bundleVersion: string; bundleSha256: string }>
    },
    { projectId: fixture.projectId, trustDomain: domain.trustDomain }
  )
  const currentKeys =
    current.bundleVersion === domain.bundleVersion
      ? domain.keys
      : fixture.rotationFixtures.find(
          (entry) => entry.metadata.bundleVersion === current.bundleVersion
        )?.metadata.keys
  const rotation = fixture.rotationFixtures.find(
    (entry) =>
      entry.metadata.bundleVersion === String(BigInt(current.bundleVersion) + 1n) &&
      entry.signedEnvelope.expectedRevision === current.revision
  )
  if (
    !currentKeys ||
    currentKeys.length !== 51 ||
    !rotation ||
    rotation.metadata.keys.length !== 51
  )
    throw new Error('受控换包夹具耗尽或不匹配当前权威包及修订')

  const otaMutations: string[] = []
  page.on('request', (request) => {
    const path = new URL(request.url()).pathname
    if (path.includes('/ota/') && !['GET', 'HEAD', 'OPTIONS'].includes(request.method()))
      otaMutations.push(`${request.method()} ${path}`)
  })
  await page.goto('/#/ota/firmwares')
  await page.getByTestId('ota-metadata-open').click()
  const drawer = page.getByTestId('ota-metadata-drawer')
  await expect(drawer).toBeVisible()
  await expect(drawer).toContainText('OTA 信任与类型基线')
  await expect(drawer).toContainText('请先选择信任域')
  await expect(drawer).toContainText('请先选择已发布设备类型')
  const domains = drawer.getByTestId('ota-trust-domains')
  await expect(domains.locator('tbody tr')).toHaveCount(20)
  for (const count of [40, 51]) {
    await drawer.getByTestId('ota-trust-domains-more').click()
    await expect(domains.locator('tbody tr')).toHaveCount(count)
  }
  await expect(drawer.getByTestId('ota-trust-domains-more')).toHaveCount(0)
  for (const entry of fixture.domains) {
    const row = domains
      .getByRole('row')
      .filter({ has: page.getByText(entry.trustDomain, { exact: true }) })
    await expect(row).toHaveCount(1)
    await expect(row).toContainText(entry === domain ? current.bundleSha256 : entry.bundleSha256)
  }

  await drawer
    .getByRole('button', { name: `查看 ${domain.trustDomain} 发布键`, exact: true })
    .click()
  await expect(drawer.getByTestId('ota-trust-selected-domain')).toContainText(domain.trustDomain)
  const keys = drawer.getByTestId('ota-trust-keys')
  await expectKeys(keys, currentKeys.slice(0, 20))
  for (const count of [40, 51]) {
    await drawer.getByTestId('ota-trust-keys-more').click()
    await expectKeys(keys, currentKeys.slice(0, count))
  }
  await expect(drawer.getByTestId('ota-trust-keys-more')).toHaveCount(0)
  await drawer.getByTestId('ota-trust-keys-refresh').click()
  await expectKeys(keys, currentKeys.slice(0, 20))

  // 已由主线签好的合成包原样送入真实控制面；不修改签名，不造新根或私钥。
  const imported = await page.evaluate(
    async (input) => {
      const path = '/src/utils/http/index.ts'
      const http = (await import(path)).default
      return http.post({
        url: `/api/v1/projects/${input.projectId}/ota/trust-domains/${encodeURIComponent(input.trustDomain)}/bundles`,
        params: input.envelope,
        headers: { 'Idempotency-Key': crypto.randomUUID() },
        showErrorMessage: false
      }) as Promise<{ bundleVersion: string }>
    },
    {
      projectId: fixture.projectId,
      trustDomain: domain.trustDomain,
      envelope: rotation.signedEnvelope
    }
  )
  expect(imported.bundleVersion).toBe(rotation.metadata.bundleVersion)
  const staleCursor = page.waitForResponse((response) => {
    const url = new URL(response.url())
    return (
      url.pathname.endsWith(`/ota/trust-domains/${domain.trustDomain}/keys`) &&
      url.searchParams.has('cursor') &&
      response.status() === 400
    )
  })
  await drawer.getByTestId('ota-trust-keys-more').click()
  await staleCursor
  await expect(drawer.getByTestId('ota-trust-keys-reset')).toContainText('信任包已变化')
  await expectKeys(keys, rotation.metadata.keys.slice(0, 20))
  for (const count of [40, 51]) {
    await drawer.getByTestId('ota-trust-keys-more').click()
    await expectKeys(keys, rotation.metadata.keys.slice(0, count))
  }
  await expect(drawer.getByTestId('ota-trust-keys-more')).toHaveCount(0)

  const select = drawer.getByTestId('ota-baseline-type')
  await select.click()
  await page
    .getByRole('option', { name: `${fixture.typeName}（${fixture.deviceTypeId}）`, exact: true })
    .click()
  const baselines = drawer.getByTestId('ota-baseline-versions')
  for (const entry of fixture.baselines ?? [fixture.baseline]) {
    const row = baselines.getByRole('row').filter({ hasText: entry.baselineHash })
    await expect(row).toHaveCount(1)
    await expect(row).toContainText(String(entry.baselineVersion))
  }
  await select.click()
  await page
    .getByRole('option', {
      name: `${fixture.emptyTypeName}（${fixture.emptyDeviceTypeId}）`,
      exact: true
    })
    .click()
  await expect(drawer).toContainText('该类型尚未登记基线版本')
  await expect(drawer.getByText(fixture.baseline.baselineHash, { exact: true })).toHaveCount(0)
  await drawer.getByRole('button', { name: '关闭', exact: true }).click()
  await expect(drawer).not.toBeVisible()
  await page.getByTestId('ota-metadata-open').click()
  await expect(drawer).toContainText('请先选择信任域')
  await expect(drawer).toContainText('请先选择已发布设备类型')

  // 项目切换走真实项目入口和作用域换发；新项目不能继承旧域、键或类型选择。
  await enterProject(page, fixture.emptyProjectName)
  await page.goto('/#/ota/firmwares')
  await page.getByTestId('ota-metadata-open').click()
  await expect(drawer).toContainText('本项目尚未登记信任域')
  await expect(drawer).toContainText('请先选择信任域')
  await expect(drawer.getByText(domain.trustDomain, { exact: true })).toHaveCount(0)
  await expect(drawer.getByText(fixture.baseline.baselineHash, { exact: true })).toHaveCount(0)
  expect(otaMutations).toEqual([
    `POST /api/v1/projects/${fixture.projectId}/ota/trust-domains/${domain.trustDomain}/bundles`
  ])
})
