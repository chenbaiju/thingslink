import { generateKeyPairSync, randomBytes, randomUUID, sign } from 'node:crypto'
import { expect, test } from '@playwright/test'
import { login, resetSession } from './helpers'

test.use({ video: 'off' })

const email = process.env.E2E_SHC_OPERATOR_EMAIL
const password = process.env.E2E_SHC_PASSWORD
const noPermissionEmail = process.env.E2E_SHC_NO_PERMISSION_EMAIL

/** 仅为浏览器验收构造合法申请；声明租户仍未获客户归属审核。 */
function uuid7() {
  const bytes = randomBytes(16)
  bytes.writeUIntBE(Date.now(), 0, 6)
  bytes[6] = (bytes[6] & 0x0f) | 0x70
  bytes[8] = (bytes[8] & 0x3f) | 0x80
  const hex = bytes.toString('hex')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}
function uuidBytes(value: string) {
  return Buffer.from(value.replaceAll('-', ''), 'hex')
}
function requestFixture(tenantId: string, deploymentId: string) {
  const requestId = uuid7()
  const { publicKey, privateKey } = generateKeyPairSync('ed25519')
  const spki = publicKey.export({ type: 'spki', format: 'der' })
  const length = Buffer.alloc(2)
  length.writeUInt16BE(spki.length)
  const body = Buffer.concat([
    uuidBytes(requestId),
    uuidBytes(deploymentId),
    uuidBytes(tenantId),
    length,
    spki
  ])
  const signature = sign(null, Buffer.concat([Buffer.from('TC-SH-ENROLL-V1\0'), body]), privateKey)
  return { requestId, bytes: Buffer.concat([Buffer.from('TCSHREQ1\0'), body, signature]) }
}

/** 真实 Chromium、独立数据库的管理进程和真实待审 API；不使用 page.route 模拟。 */
test('发行方待审申请页面：权限、上传、去重、冲突与键集翻页', async ({ page }) => {
  if (!email || !password || !noPermissionEmail) {
    throw new Error('缺少自部署待审浏览器验收的隔离账号')
  }
  const tenantId = randomUUID()
  const deploymentId = randomUUID()
  const original = requestFixture(tenantId, deploymentId)
  const conflict = requestFixture(tenantId, deploymentId)
  await login(page, email, password)
  await expect(
    page.locator('#app-sidebar').getByText('自部署授权申请', { exact: true })
  ).toBeVisible()
  await page.locator('#app-sidebar').getByText('自部署授权申请', { exact: true }).click()
  const file = page.locator('[data-testid="enrollment-file"]')
  const channel = page.locator('[data-testid="enrollment-channel"]')
  const upload = page.getByRole('button', { name: '接收待审申请' })
  const intakeResponse = () =>
    page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' &&
        response.url().endsWith('/api/v1/operations/self-hosted/enrollment-requests')
    )

  await file.setInputFiles({
    name: 'oversize.tcshreq',
    mimeType: 'application/octet-stream',
    buffer: Buffer.alloc(252)
  })
  await expect(page.getByText('请选择不超过 251 字节的有效 .tcshreq 申请文件。')).toBeVisible()
  await expect(upload).toBeDisabled()

  await file.setInputFiles({
    name: 'enrollment-request.tcshreq',
    mimeType: 'application/octet-stream',
    buffer: original.bytes
  })
  await channel.click()
  await page.getByRole('option', { name: '离线介质' }).click()
  const firstUpload = intakeResponse()
  await upload.click()
  expect((await firstUpload).status()).toBe(200)
  const result = page.locator('[data-testid="enrollment-result"]')
  await expect(result).toContainText('已登记或同封套重复提交 · 待审')
  await expect(result).toContainText('声明租户：')
  await expect(result).toContainText('未核验')
  await expect(result).toContainText('首次来源：离线介质')
  await expect(page.locator('[data-testid="pending-enrollment-table"] tbody')).toContainText(
    original.requestId
  )

  await channel.click()
  await page.getByRole('option', { name: '联网传递' }).click()
  const repeatedUpload = intakeResponse()
  await upload.click()
  expect((await repeatedUpload).status()).toBe(200)
  await expect(result).toContainText('首次来源：离线介质')
  await expect(page.locator('[data-testid="pending-enrollment-table"] tbody')).toContainText(
    original.requestId
  )
  const duplicateCount = await page.evaluate(async (requestId) => {
    const modulePath = '/src/api/self-hosted-enrollment.ts'
    const { fetchPendingEnrollments } = await import(modulePath)
    return (await fetchPendingEnrollments(50)).filter(
      (entry: { requestId: string }) => entry.requestId === requestId
    ).length
  }, original.requestId)
  expect(duplicateCount).toBe(1)

  await file.setInputFiles({
    name: 'conflict.tcshreq',
    mimeType: 'application/octet-stream',
    buffer: conflict.bytes
  })
  await expect(result).toHaveCount(0)
  const conflictingUpload = intakeResponse()
  await upload.click()
  expect((await conflictingUpload).status()).toBe(409)
  await expect(page.locator('.el-alert__title').getByText('申请身份冲突')).toBeVisible()
  await expect(result).toHaveCount(0)

  const moreRequests = Array.from({ length: 26 }, () =>
    requestFixture(tenantId, randomUUID()).bytes.toString('base64')
  )
  await page.evaluate(async (encoded) => {
    const modulePath = '/src/api/self-hosted-enrollment.ts'
    const { receiveEnrollment } = await import(modulePath)
    for (const value of encoded) {
      const binary = atob(value)
      const bytes = Uint8Array.from(binary, (character) => character.charCodeAt(0))
      await receiveEnrollment(bytes.buffer, 'OFFLINE')
    }
  }, moreRequests)
  await page.getByRole('button', { name: '刷新第一页' }).click()
  await expect(page.locator('[data-testid="pending-enrollment-table"] tbody tr')).toHaveCount(25)
  await page.getByRole('button', { name: '下一页' }).click()
  await expect(page.getByText('第 2 页')).toBeVisible()
  expect(
    await page.locator('[data-testid="pending-enrollment-table"] tbody tr').count()
  ).toBeGreaterThanOrEqual(2)

  await resetSession(page)
  await login(page, noPermissionEmail, password)
  await expect(
    page.locator('#app-sidebar').getByText('自部署授权申请', { exact: true })
  ).toHaveCount(0)
  await page.goto('/#/self-hosted-enrollment')
  await expect(page.locator('[data-testid="enrollment-file"]')).toHaveCount(0)
})
