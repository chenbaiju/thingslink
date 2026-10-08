import { expect, test, type Page, type Response } from '@playwright/test'
import { guardDeviceHistoryRuntime, type Seed } from './device-history-runtime'
import {
  enterProject,
  login,
  openDeviceDetails,
  openDeviceList,
  OWNER_EMAIL,
  OWNER_PASSWORD
} from './helpers'

// 此旅程只读取公开设备/类型事实，不记录登录材料或自动诊断画面。
test.use({ trace: 'off', video: 'off', screenshot: 'off' })
test.afterEach(async ({ page }) => {
  await page.close()
})
const uuid = (value: unknown): value is string =>
  typeof value === 'string' && /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(value)
const stage = (value: string) => console.log(JSON.stringify({ stage: value, businessWrites: 0 }))
const typeBodies = new WeakMap<Response, Promise<any>>()
function typeResponse(page: Page, project: string) {
  return page.waitForResponse(
    (response) => {
      const path = new URL(response.url()).pathname
      const prefix = `/api/v1/projects/${project}/device-types/`
      if (
        response.request().method() !== 'GET' ||
        !path.startsWith(prefix) ||
        !uuid(path.slice(prefix.length))
      )
        return false
      const body = response.json()
      void body.catch(() => undefined)
      typeBodies.set(response, body)
      return true
    },
    { timeout: 20_000 }
  )
}
async function readyRow(page: Page, seed: Seed) {
  await openDeviceList(page)
  await page.getByPlaceholder('请输入名称或标识符').fill(seed.deviceKey, { timeout: 10_000 })
  const response = page.waitForResponse(
    (value) =>
      value.request().method() === 'GET' &&
      new URL(value.url()).pathname === `/api/v1/projects/${seed.projectId}/devices/search`,
    { timeout: 20_000 }
  )
  await page.locator('.device-advanced-filter .search-button').click({ timeout: 10_000 })
  expect((await response).status()).toBe(200)
  const row = page
    .locator('.device-list__data tbody tr')
    .filter({ has: page.getByText(seed.deviceName, { exact: true }) })
    .first()
  await expect(row).toBeVisible({ timeout: 10_000 })
  return row
}
async function settledDetails(page: Page) {
  await expect(page.locator('.device-detail > .el-loading-mask')).toBeHidden({ timeout: 20_000 })
}
async function publicType(page: Page, response: Response, device: any, seed: Seed) {
  expect(response.status()).toBe(200)
  const type = await (typeBodies.get(response) ?? response.json())
  expect(
    uuid(type.id) && type.id === device.deviceTypeId && type.projectId === seed.projectId
  ).toBe(true)
  expect(typeof type.name === 'string' && type.name.length > 0).toBe(true)
  expect(['DIRECT', 'GATEWAY', 'SUB_DEVICE'].includes(type.deviceKind)).toBe(true)
  await expect(page.getByTestId('device-detail-type-summary')).toContainText(type.name, {
    timeout: 10_000
  })
  await expect(page.getByTestId('device-detail-type-unavailable')).toHaveCount(0)
  const topology = type.deviceKind === 'GATEWAY' || Boolean(device.gatewayId)
  const modbus = ['STANDARD_GATEWAY', 'MODBUS_RTU_CLOUD_GATEWAY'].includes(type.payloadProtocol)
  await expect(page.getByRole('tab', { name: '拓扑', exact: true })).toHaveCount(topology ? 1 : 0)
  await expect(page.getByRole('tab', { name: 'Modbus 点位', exact: true })).toHaveCount(
    modbus ? 1 : 0
  )
  return type
}

test('设备详情每次单条读取类型，关闭后真实迟响应不能恢复旧能力', async ({ page, baseURL }) => {
  test.setTimeout(150_000)
  test.skip(
    !process.env.E2E_DEVICE_TYPE_DETAIL_RUNTIME || !process.env.E2E_DEVICE_TYPE_DETAIL_SEED,
    '需BE-002独占冻结runtime与C真实摄取公开设备seed'
  )
  const seed = await guardDeviceHistoryRuntime(baseURL!, {
    runtimePath: process.env.E2E_DEVICE_TYPE_DETAIL_RUNTIME!,
    seedPath: process.env.E2E_DEVICE_TYPE_DETAIL_SEED!,
    qualification: 'BE-002-A-DEVICE-TYPE'
  })
  stage('BE-002-A-GUARD')
  await login(page, OWNER_EMAIL, OWNER_PASSWORD)
  await enterProject(page, seed.projectName)
  let row = await readyRow(page, seed)
  let gets = 0,
    searches = 0
  const prefix = `/api/v1/projects/${seed.projectId}/device-types/`
  page.on('request', (request) => {
    const path = new URL(request.url()).pathname
    if (path === prefix + 'search') searches++
    if (request.method() === 'GET' && path.startsWith(prefix) && uuid(path.slice(prefix.length)))
      gets++
  })
  const deviceReply = page
    .waitForResponse(
      (response) =>
        response.request().method() === 'GET' &&
        new URL(response.url()).pathname ===
          `/api/v1/projects/${seed.projectId}/devices/${seed.deviceId}`,
      { timeout: 20_000 }
    )
    .then(async (response) => {
      expect(response.status()).toBe(200)
      return response.json()
    })
  const typeReply = typeResponse(page, seed.projectId)
  void typeReply.catch(() => undefined)
  await openDeviceDetails(page, row, 10_000)
  const device = await deviceReply
  expect(
    device.id === seed.deviceId && device.deviceKey === seed.deviceKey && uuid(device.deviceTypeId)
  ).toBe(true)
  const first = await publicType(page, await typeReply, device, seed)
  await settledDetails(page)
  expect({ gets, searches }).toEqual({ gets: 1, searches: 0 })
  stage('BE-002-A-FIRST-GET')
  await page.locator('.device-detail__back').click({ timeout: 10_000 })
  await expect(page.locator('.device-detail')).toHaveCount(0, { timeout: 10_000 })

  // 暂停真实单条200响应，不替换其正文。其他详情接口完成后仍能真实点击返回。
  row = await readyRow(page, seed)
  gets = 0
  searches = 0
  const cdp = await page.context().newCDPSession(page)
  const path = prefix + first.id
  let heldId = ''
  let resolveHeld!: (status: number) => void
  const held = new Promise<number>((resolve) => {
    resolveHeld = resolve
  })
  let deadline: ReturnType<typeof setTimeout> | undefined
  const bounded = Promise.race([
    held,
    new Promise<number>((_, reject) => {
      deadline = setTimeout(
        () => reject(new Error('DEVICE_TYPE_REAL_RESPONSE_HOLD_TIMEOUT')),
        20_000
      )
    })
  ])
  void bounded.catch(() => undefined)
  cdp.on('Fetch.requestPaused', (event) => {
    if (new URL(event.request.url).pathname === path && event.responseStatusCode === 200) {
      heldId = event.requestId
      resolveHeld(200)
    } else void cdp.send('Fetch.continueRequest', { requestId: event.requestId }).catch(() => {})
  })
  await cdp.send('Fetch.enable', {
    patterns: [
      { urlPattern: '*8088' + path, requestStage: 'Response' },
      { urlPattern: '*3017' + path, requestStage: 'Response' }
    ]
  })
  try {
    await openDeviceDetails(page, row, 10_000)
    expect(await bounded).toBe(200)
    if (deadline) clearTimeout(deadline)
    await settledDetails(page)
    await expect(page.getByTestId('device-detail-type-summary')).toContainText('读取中', {
      timeout: 10_000
    })
    await expect(page.getByRole('tab', { name: 'Modbus 点位', exact: true })).toHaveCount(0)
    expect({ gets, searches }).toEqual({ gets: 1, searches: 0 })
    await page.locator('.device-detail__back').click({ timeout: 10_000 })
    await expect(page.locator('.device-detail')).toHaveCount(0, { timeout: 10_000 })
    await cdp.send('Fetch.continueRequest', { requestId: heldId })
    await expect(page.getByTestId('device-detail-type-summary')).toHaveCount(0)
    await expect(page.getByRole('tab', { name: 'Modbus 点位', exact: true })).toHaveCount(0)
    stage('BE-002-A-REAL-LATE-RESPONSE')
  } finally {
    if (deadline) clearTimeout(deadline)
    await cdp.send('Fetch.disable')
    await cdp.detach()
  }
  row = await readyRow(page, seed)
  gets = 0
  searches = 0
  const reopened = typeResponse(page, seed.projectId)
  await openDeviceDetails(page, row, 10_000)
  const current = await publicType(page, await reopened, device, seed)
  await settledDetails(page)
  expect(current.id === first.id && current.name === first.name).toBe(true)
  expect({ gets, searches }).toEqual({ gets: 1, searches: 0 })
  stage('BE-002-A-PASS')
})
