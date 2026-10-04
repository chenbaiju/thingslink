import { appendFileSync } from 'node:fs'
import path from 'node:path'
import { captureResourcePackageQuota } from './resource-package-response'
import { expect, test } from '@playwright/test'
import { enterProject, login } from './helpers'
import type { ProjectQuotaOverviewResponse } from '../src/api/quota'

// This scenario handles ephemeral session/control credentials. Keep only JSON/JUnit outcomes.
test.use({ trace: 'off', video: 'off', screenshot: 'off' })

/** Only the opt-in Java fixture can provision the isolated simulated purchase/refund control. */
test('资源包购买与退款：套餐展示和真实设备准入一致', async ({ page, request }) => {
  test.skip(!process.env.E2E_RESOURCE_PACKAGE_CONTROL, '需要专属真实后端资源包夹具')
  const required = (key: string) => {
    const value = process.env[key]
    if (!value) throw new Error(`Missing resource package fixture setting: ${key}`)
    return value
  }
  const projectId = required('E2E_RESOURCE_PACKAGE_PROJECT_ID')
  const control = required('E2E_RESOURCE_PACKAGE_CONTROL')
  const credential = required('E2E_RESOURCE_PACKAGE_CREDENTIAL')
  const checkpointFile = path.join(required('E2E_RUN_DIR'), 'resource-package-checkpoints.jsonl')
  const checkpoint = (stage: string, status?: number) => {
    appendFileSync(
      checkpointFile,
      JSON.stringify({ stage, status, at: new Date().toISOString() }) + '\n'
    )
  }
  const quotaUrl = new URL(`/api/v1/projects/${projectId}/quota`, required('E2E_BASE_URL')).href
  const quotaCapture = await captureResourcePackageQuota(page, quotaUrl, checkpoint)
  let authorization = ''
  page.on('request', (req) => {
    if (new URL(req.url()).pathname.startsWith(`/api/v1/projects/${projectId}/`)) {
      authorization = req.headers().authorization ?? authorization
    }
  })
  checkpoint('login-start')
  await login(
    page,
    required('E2E_RESOURCE_PACKAGE_EMAIL'),
    required('E2E_RESOURCE_PACKAGE_PASSWORD')
  )
  checkpoint('login-complete')
  await enterProject(page, required('E2E_RESOURCE_PACKAGE_PROJECT_NAME'))
  await page.goto('/#/project/settings')
  const panel = page.locator('.plan-summary')
  await expect(panel).toBeVisible()
  await expect.poll(() => authorization.startsWith('Bearer ')).toBe(true)

  const assertPlan = async (limit: number, purchased: boolean, stage: string) => {
    checkpoint(`${stage}-reload-start`)
    const responsePromise = page.waitForResponse(
      (res) => res.url() === quotaUrl && res.request().method() === 'GET',
      { timeout: 30_000 }
    )
    const [response] = await Promise.all([responsePromise, page.reload({ timeout: 30_000 })])
    checkpoint(`${stage}-response-received`, response.status())
    expect(response.status()).toBe(200)
    const quota = quotaCapture.read<ProjectQuotaOverviewResponse>(response)
    expect(quota.tenantSharedPool?.deviceCount?.limit).toBe(limit)
    expect(quota.planSummary?.additions ?? []).toEqual(
      purchased
        ? [
            expect.objectContaining({
              source: 'PURCHASE',
              dimensionCode: 'DEVICES_MAX',
              amount: 2,
              status: 'ACTIVE',
              effectiveNow: true
            })
          ]
        : []
    )
    const row = (name: string) =>
      panel.getByTestId(name).locator('.el-table__body tr').filter({ hasText: '设备数上限' })
    await expect(row('plan-frozen-limits')).toContainText('3 COUNT')
    await expect(row('plan-effective-limits')).toContainText(`${limit} COUNT`)
    const additions = panel.getByTestId('plan-additions').locator('.el-table__body tr')
    await expect(additions).toHaveCount(purchased ? 1 : 0)
    if (purchased) {
      await expect(additions).toContainText('客户购买')
      await expect(additions).toContainText('2 COUNT')
      await expect(additions).toContainText('生效中')
      await expect(additions).toContainText('已计入')
    }
    checkpoint(`${stage}-verified`)
    return quota
  }
  const createDevice = async (key: string, accepted: boolean) => {
    const response = await page.request.post(`/api/v1/projects/${projectId}/devices`, {
      headers: { Authorization: authorization },
      data: { deviceKey: key, name: `资源包设备 ${key}` }
    })
    expect(response.status()).toBe(accepted ? 201 : 429)
    const body = await response.json()
    if (accepted) expect(body.id).toBeTruthy()
    else expect(body.code).toBe(30035)
  }
  const transition = async (action: 'purchase' | 'refund') => {
    const response = await request.post(`${control}/${action}`, {
      headers: { Authorization: `Bearer ${credential}`, 'Content-Length': '0' }
    })
    expect(response.status()).toBe(200)
    return response.json()
  }

  // FREE capacity is a real provisioned entitlement, not an operator adjustment.
  await test.step('初始套餐与有效额度', () => assertPlan(3, false, 'baseline'))
  for (let i = 1; i <= 3; i++) await createDevice(`package-${i}`, true)
  await createDevice('denied-before-purchase', false)
  // An unauthenticated request must not perform a transition.
  expect((await request.post(`${control}/purchase`)).status()).toBe(403)
  const controlHeaders = { Authorization: `Bearer ${credential}` }
  expect((await request.get(`${control}/purchase`, { headers: controlHeaders })).status()).toBe(405)
  expect(
    (await request.post(`${control}/purchase?tenantId=other`, { headers: controlHeaders })).status()
  ).toBe(400)
  expect(
    (
      await request.post(`${control}/purchase`, {
        headers: controlHeaders,
        data: { tenantId: 'other' }
      })
    ).status()
  ).toBe(400)
  expect(
    (
      await request.post(`${control}/unknown`, {
        headers: { ...controlHeaders, 'Content-Length': '0' }
      })
    ).status()
  ).toBe(404)
  const purchase = await transition('purchase')
  expect(purchase).toMatchObject({ limit: 5, status: 'ACTIVE', activated: true })
  const purchaseReplay = await transition('purchase')
  expect(purchaseReplay).toMatchObject({
    orderId: purchase.orderId,
    packageId: purchase.packageId,
    limit: 5,
    activated: false
  })
  await test.step('购买后套餐与有效额度', () => assertPlan(5, true, 'purchased'))
  for (let i = 4; i <= 5; i++) await createDevice(`package-${i}`, true)
  await createDevice('denied-at-five', false)

  const refund = await transition('refund')
  expect(refund).toMatchObject({
    orderId: purchase.orderId,
    packageId: purchase.packageId,
    limit: 3,
    status: 'REFUNDED'
  })
  expect(await transition('refund')).toEqual(refund)
  const quota = await test.step('退款后套餐与有效额度', () => assertPlan(3, false, 'refunded'))
  // Refunding capacity must preserve existing devices and deny additional admission.
  expect(quota.project?.deviceCount?.used).toBe(5)
  expect(quota.tenantSharedPool?.deviceCount).toMatchObject({ used: 5, remaining: 0 })
  await createDevice('denied-after-refund', false)
  checkpoint('complete')
})
