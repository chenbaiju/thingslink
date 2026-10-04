import { generateKeyPairSync, randomBytes, randomUUID, sign } from 'node:crypto'
import { expect, test } from '@playwright/test'
import { login, resetSession } from './helpers'

test.use({ video: 'off' })

const operator = process.env.E2E_SHC_OPERATOR_EMAIL
const firstReviewer = process.env.E2E_SHC_REVIEWER_ONE_EMAIL
const secondReviewer = process.env.E2E_SHC_REVIEWER_TWO_EMAIL
const password = process.env.E2E_SHC_PASSWORD
const evidence = 'a'.repeat(64)

function uuid7() {
  const bytes = randomBytes(16)
  bytes.writeUIntBE(Date.now(), 0, 6)
  bytes[6] = (bytes[6] & 0x0f) | 0x70
  bytes[8] = (bytes[8] & 0x3f) | 0x80
  const hex = bytes.toString('hex')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}
function requestFixture() {
  const requestId = uuid7()
  const deploymentId = randomUUID()
  const tenantId = randomUUID()
  const { publicKey, privateKey } = generateKeyPairSync('ed25519')
  const spki = publicKey.export({ type: 'spki', format: 'der' })
  const length = Buffer.alloc(2)
  length.writeUInt16BE(spki.length)
  const body = Buffer.concat([
    Buffer.from(requestId.replaceAll('-', ''), 'hex'),
    Buffer.from(deploymentId.replaceAll('-', ''), 'hex'),
    Buffer.from(tenantId.replaceAll('-', ''), 'hex'),
    length,
    spki
  ])
  const signature = sign(null, Buffer.concat([Buffer.from('TC-SH-ENROLL-V1\0'), body]), privateKey)
  return { requestId, bytes: Buffer.concat([Buffer.from('TCSHREQ1\0'), body, signature]) }
}

/** 真浏览器对接独立数据库与发行方管理进程；测试账号的授权由隔离数据库夹具准备。 */
test('独立审核者菜单、同人幂等、第二人一致及证据冲突', async ({ page }) => {
  if (!operator || !firstReviewer || !secondReviewer || !password) {
    throw new Error('缺少自部署审核浏览器验收的隔离账号')
  }
  const fixture = requestFixture()
  const reviewUrl =
    '/api/v1/operations/self-hosted/enrollment-requests/' + fixture.requestId + '/review'
  const responseFor = (method: string) =>
    page.waitForResponse(
      (response) => response.request().method() === method && response.url().endsWith(reviewUrl)
    )
  const load = async () => {
    await page.locator('[data-testid="review-request-id"]').fill(fixture.requestId)
    const pending = responseFor('GET')
    await page.getByRole('button', { name: '读取申请摘要' }).click()
    expect((await pending).status()).toBe(200)
    await expect(page.locator('[data-testid="review-detail"]')).toContainText(fixture.requestId)
  }
  const form = async (digest: string) => {
    await page.locator('[data-testid="review-organization"]').fill('CASE/2026-01')
    await page.locator('[data-testid="review-evidence"]').fill(digest)
    await page.locator('[data-testid="review-tier"]').click()
    await page.getByRole('option', { name: '免费版 FREE' }).click()
    await page.locator('[data-testid="review-confirm"]').click()
  }
  const submit = async (status: number, count?: number) => {
    const pending = responseFor('POST')
    await page.getByRole('button', { name: '记录独立审核事实' }).click()
    const response = await pending
    expect(response.status()).toBe(status)
    if (status === 200)
      expect(await response.json()).toMatchObject({
        attestationCount: count,
        readyForIssuanceReview: true
      })
  }

  await login(page, operator, password)
  await expect(
    page.locator('#app-sidebar').getByText('自部署申请审核', { exact: true })
  ).toHaveCount(0)
  await page.locator('#app-sidebar').getByText('自部署授权申请', { exact: true }).click()
  await page.locator('[data-testid="enrollment-file"]').setInputFiles({
    name: 'review.tcshreq',
    mimeType: 'application/octet-stream',
    buffer: fixture.bytes
  })
  await page.locator('[data-testid="enrollment-channel"]').click()
  await page.getByRole('option', { name: '离线介质' }).click()
  await page.getByRole('button', { name: '接收待审申请' }).click()
  await expect(page.locator('[data-testid="enrollment-result"]')).toContainText(fixture.requestId)

  await resetSession(page)
  await login(page, firstReviewer, password)
  await expect(
    page.locator('#app-sidebar').getByText('自部署授权申请', { exact: true })
  ).toHaveCount(0)
  await page.locator('#app-sidebar').getByText('自部署申请审核', { exact: true }).click()
  await load()
  await expect(page.locator('[data-testid="review-detail"]')).toContainText(
    '现有审核事实：0（至少 1 份；最多 2 份）'
  )
  await form(evidence)
  await submit(200, 1)
  // ADR0230：一份责任审核已满足人数前置，仍不等于客户归属确认或授权签发。
  await expect(page.locator('[data-testid="review-progress"]')).toContainText(
    '审核记录已具备，仍须核对客户归属与签发资格'
  )
  await expect(page.locator('[data-testid="review-progress"]')).toContainText('已记录 1 份审核事实')
  await page.locator('[data-testid="review-confirm"]').click()
  await submit(200, 1)
  await expect(page.locator('[data-testid="review-progress"]')).toContainText('已记录 1 份审核事实')
  await page.locator('[data-testid="review-evidence"]').fill('b'.repeat(64))
  await page.locator('[data-testid="review-confirm"]').click()
  await submit(409)
  await expect(page.getByText('重新读取摘要及审核进度后人工核对', { exact: false })).toBeVisible()

  await resetSession(page)
  await login(page, secondReviewer, password)
  await page.locator('#app-sidebar').getByText('自部署申请审核', { exact: true }).click()
  await load()
  await form('b'.repeat(64))
  await submit(409)
  await expect(page.locator('[data-testid="review-progress"]')).toHaveCount(0)
  await load()
  await form(evidence)
  await submit(200, 2)
  await expect(page.locator('[data-testid="review-progress"]')).toContainText('已记录 2 份审核事实')
  await expect(page.locator('[data-testid="review-progress"]')).toContainText('不构成授权签发')
})
