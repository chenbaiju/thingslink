import { expect, test } from '@playwright/test'
import { readFile, realpath } from 'node:fs/promises'
import { isAbsolute, relative, resolve } from 'node:path'
import { login, enterProject, OWNER_EMAIL, OWNER_PASSWORD } from './helpers'

// 本片只读公开材料；登录与当前会话仍不进入自动失败媒体。
test.use({ trace: 'off', video: 'off', screenshot: 'off' })
test.afterEach(async ({ page }) => {
  await page.close()
})

test('管理端按需读取真实READY发布证明，刷新与关闭不签发下载或写入发布物', async ({
  page
}, testInfo) => {
  test.skip(
    !process.env.E2E_OWNED_RUNTIME || !process.env.E2E_OTA_RELEASE_PROOF_FIXTURE,
    '需要已冻结的独占真实运行栈及当前受控READY发布物公开身份夹具'
  )
  const root = await realpath(resolve('logs'))
  const file = await realpath(resolve(process.env.E2E_OTA_RELEASE_PROOF_FIXTURE!))
  const suffix = relative(root, file)
  if (!suffix || isAbsolute(suffix) || suffix === '..' || suffix.startsWith('../'))
    throw new Error('公开发布证明夹具必须位于专用logs目录')
  const fixture = JSON.parse(await readFile(file, 'utf8')) as {
    projectId: string
    projectName: string
    firmwareId: string
    firmwareVersion: string
  }
  const uuid = /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/
  if (
    !uuid.test(fixture.projectId) ||
    !uuid.test(fixture.firmwareId) ||
    !fixture.projectName ||
    !fixture.firmwareVersion
  )
    throw new Error('公开发布证明夹具身份不完整')
  await page.setViewportSize({ width: 1440, height: 1000 })
  await login(page, OWNER_EMAIL, OWNER_PASSWORD)
  await enterProject(page, fixture.projectName)
  const expected = await page.evaluate(async ({ projectId, firmwareId }) => {
    const path = '/src/api/ota.ts'
    const api = await import(path)
    return api.fetchOtaRelease(projectId, firmwareId)
  }, fixture)
  expect(expected.firmwareId).toBe(fixture.firmwareId)
  let writes = 0,
    reads = 0
  page.on('request', (request) => {
    if (request.url().includes(`/ota/firmwares/${fixture.firmwareId}`)) {
      if (request.method() !== 'GET') writes++
      if (new URL(request.url()).pathname.endsWith('/release')) reads++
    }
  })
  await page.goto('/#/ota/firmwares')
  const row = page
    .getByRole('row')
    .filter({ has: page.getByText(fixture.firmwareVersion, { exact: true }) })
  await expect(row).toHaveCount(1)
  await expect(row.getByTestId('ota-release-proof-open')).toBeVisible()
  expect(reads).toBe(0)
  await row.getByTestId('ota-release-proof-open').click()
  const dialog = page.getByTestId('ota-release-proof-dialog')
  const content = page.getByTestId('ota-release-proof-content')
  await expect(content).toBeVisible()
  await expect(content).toContainText(expected.artifactSha256)
  await expect(content).toContainText(expected.keyFingerprint)
  await expect(content).toContainText(`${expected.artifactSize} 字节`)
  await expect(dialog).toContainText('不代表设备验签通过或获得升级资格')
  await dialog.getByText('查看原始公开材料（Base64）', { exact: true }).click()
  await expect(dialog.getByRole('textbox', { name: 'Manifest Base64', exact: true })).toHaveValue(
    expected.manifestBase64
  )
  await expect(dialog.getByRole('textbox', { name: '签名 Base64', exact: true })).toHaveValue(
    expected.signatureBase64
  )
  await expect(
    dialog.getByRole('textbox', { name: '发布公钥 SPKI Base64', exact: true })
  ).toHaveValue(expected.publicKeySpkiBase64)
  await dialog.getByTestId('ota-release-proof-refresh').click()
  await expect(content).toBeVisible()
  expect(reads).toBe(2)
  await dialog.screenshot({ path: testInfo.outputPath('release-proof-desktop.png') })
  await page.setViewportSize({ width: 390, height: 844 })
  await expect
    .poll(async () => dialog.evaluate((element) => element.scrollWidth <= element.clientWidth + 1))
    .toBe(true)
  await dialog.screenshot({ path: testInfo.outputPath('release-proof-mobile.png') })
  await dialog.getByRole('button', { name: '关闭', exact: true }).click()
  await expect(dialog).toBeHidden()
  expect(writes).toBe(0)
  expect(reads).toBe(2)
})
