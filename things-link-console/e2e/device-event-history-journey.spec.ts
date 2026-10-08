import { expect, test, type Locator, type Page, type Response } from '@playwright/test'
import { guardDeviceHistoryRuntime, type Seed } from './device-history-runtime'
import {
  enterProject,
  login,
  openDeviceDetails,
  openDeviceList,
  OWNER_EMAIL,
  OWNER_PASSWORD
} from './helpers'

interface PublicPage {
  ids: string[]
  eventKeys: string[]
  levels: string[]
  models: string[]
  next: boolean
}
// 管理JWT只由正常登录保留于浏览器RAM；参数只看真实脱敏投影，关闭自动诊断捕获。
test.use({ trace: 'off', video: 'off', screenshot: 'off' })
test.afterEach(async ({ page }) => {
  await page.close()
})
const uuid = (value: unknown): value is string =>
  typeof value === 'string' && /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(value)
const historyPath = (seed: Seed) =>
  `/api/v1/projects/${seed.projectId}/devices/${seed.deviceId}/events`
const capturedBodies = new WeakMap<Response, Promise<string>>()
function responseFor(page: Page, path: string) {
  return page.waitForResponse(
    (response) => {
      if (new URL(response.url()).pathname !== path || response.request().method() !== 'GET')
        return false
      // 收到响应头时立即开始读取，避免流式无缓存响应在UI完成后被浏览器丢弃。
      if (path.endsWith('/events')) {
        const body = response.text()
        void body.catch(() => undefined)
        capturedBodies.set(response, body)
      }
      return true
    },
    { timeout: 25_000 }
  )
}
async function publicPage(response: Response): Promise<PublicPage> {
  expect(response.status()).toBe(200)
  expect(response.headers()['cache-control']).toBe('no-store')
  // 只提取公开排序/模型字段；数值精度断言来自页面词法投影，不用JSON数字替代它。
  const value = JSON.parse(await (capturedBodies.get(response) ?? response.text()))
  if (!Array.isArray(value.items) || value.items.some((item: any) => !uuid(item.messageId)))
    throw new Error('EVENT_REAL_PAGE_IDENTITY_INVALID')
  return {
    ids: value.items.map((item: any) => item.messageId),
    eventKeys: value.items.map((item: any) => item.eventKey),
    levels: value.items.map((item: any) => item.level),
    models: value.items.map((item: any) => item.thingModelVersionId),
    next: typeof value.nextCursor === 'string'
  }
}
async function refresh(page: Page, seed: Seed) {
  const [response] = await Promise.all([
    responseFor(page, historyPath(seed)),
    page.getByTestId('device-event-history-refresh').click()
  ])
  return response
}
async function openHistory(page: Page, seed: Seed) {
  await openDeviceList(page)
  await page.getByPlaceholder('请输入名称或标识符').fill(seed.deviceKey)
  await page.locator('.device-advanced-filter .search-button').click()
  const row = page
    .locator('.device-list__data tbody tr')
    .filter({ has: page.getByText(seed.deviceName, { exact: true }) })
    .first()
  await expect(row).toBeVisible()
  await openDeviceDetails(page, row)
  const [response] = await Promise.all([
    responseFor(page, historyPath(seed)),
    page.getByRole('tab', { name: '事件历史', exact: true }).click()
  ])
  await expect(page.getByTestId('device-event-history')).toBeVisible()
  return publicPage(response)
}
const rows = (page: Page) => page.getByTestId('device-event-history-table').locator('tbody tr')
async function level(page: Page, value: string) {
  const wrapper = page.getByTestId('device-event-history').locator('.el-select__wrapper')
  await wrapper.evaluate((element) => element.scrollIntoView({ block: 'center' }))
  await wrapper.click({ timeout: 15_000 })
  await page
    .locator('.el-select-dropdown:visible')
    .getByRole('option', { name: value, exact: true })
    .click()
}
async function fill(region: Locator, label: string, value: string) {
  await region.getByRole('textbox', { name: label, exact: true }).fill(value)
}

test('真实MQTT事件分页、原模型、精度、脱敏、筛选与关闭代次', async ({ page, baseURL }) => {
  test.setTimeout(180_000)
  test.skip(
    !process.env.E2E_EVENT_HISTORY_RUNTIME || !process.env.E2E_EVENT_HISTORY_SEED,
    '需根准备独占冻结查询候选及真实TLS MQTT公开checkpoint'
  )
  const seed = await guardDeviceHistoryRuntime(baseURL!, {
      runtimePath: process.env.E2E_EVENT_HISTORY_RUNTIME!,
      seedPath: process.env.E2E_EVENT_HISTORY_SEED!,
      qualification: 'BE-001-C-HISTORY'
    }),
    region = page.getByTestId('device-event-history')
  await login(page, OWNER_EMAIL, OWNER_PASSWORD)
  await enterProject(page, seed.projectName)
  const first = await openHistory(page, seed)
  console.log('stage=HISTORY_FIRST_PAGE status=PASS')
  const ordered = [...seed.messages].sort((a, b) => b.messageId.localeCompare(a.messageId))
  expect(first.ids).toEqual(ordered.slice(0, 20).map((value) => value.messageId))
  expect(first.models.every((value) => value === seed.thingModelVersionId)).toBe(true)
  expect(first.next).toBe(true)
  await expect(rows(page)).toHaveCount(20)
  await expect(region).toContainText(seed.modelVersion)
  await expect(page.getByTestId('device-event-history-window')).toContainText('存储保留 90 天')
  for (const decimal of [false, true]) {
    const material = ordered.find(
      (value) => value.eventKey === 'alarm' && value.temperatureText!.startsWith('0.') === decimal
    )!
    const index = first.ids.indexOf(material.messageId)
    expect(index >= 0).toBe(true)
    const [response] = await Promise.all([
      responseFor(page, historyPath(seed) + '/' + material.messageId),
      rows(page).nth(index).getByTestId('device-event-detail-open').click()
    ])
    expect(response.status()).toBe(200)
    await expect(page.getByTestId('device-event-detail')).toContainText(material.messageId)
    await expect(page.getByTestId('device-event-detail-params')).toContainText(
      material.temperatureText!
    )
    await expect(page.getByTestId('device-event-detail-params')).not.toContainText('sensor_token')
    await expect(page.getByTestId('device-event-detail-redaction')).toContainText(
      '已按存储规则脱敏'
    )
    await page
      .getByTestId('device-event-detail')
      .getByRole('button', { name: '关闭事件详情', exact: true })
      .click()
  }
  console.log('stage=HISTORY_EXACT_PRECISION status=PASS')
  const [nextResponse] = await Promise.all([
    responseFor(page, historyPath(seed)),
    page.getByTestId('device-event-history-next').click()
  ])
  const second = await publicPage(nextResponse)
  expect(second.ids.length).toBe(seed.eventCount - 20)
  expect(second.ids.slice(0, 7)).toEqual(ordered.slice(20).map((value) => value.messageId))
  expect(second.ids).toContain(seed.originalAlarmMessageId)
  expect(new Set([...first.ids, ...second.ids]).size).toBe(seed.eventCount)
  await expect(rows(page)).toHaveCount(9)
  await expect(page.getByTestId('device-event-history-next')).toBeDisabled()
  const originalIndex = second.ids.indexOf(seed.originalAlarmMessageId)
  const [originalResponse] = await Promise.all([
    responseFor(page, historyPath(seed) + '/' + seed.originalAlarmMessageId),
    rows(page).nth(originalIndex).getByTestId('device-event-detail-open').click()
  ])
  expect(originalResponse.status()).toBe(200)
  await expect(page.getByTestId('device-event-detail-redaction')).toContainText('已按存储规则脱敏')
  await expect(page.getByTestId('device-event-detail-params')).not.toContainText('sensor_token')
  console.log('stage=HISTORY_29_AND_ANCHOR status=PASS')
  await fill(region, '事件键', 'alarm')
  await level(page, 'WARNING')
  await fill(region, '原模型版本 ID', seed.thingModelVersionId)
  await fill(region, '事件时间从', seed.occurredAt)
  await fill(region, '事件时间至', new Date(Date.parse(seed.occurredAt) + 1000).toISOString())
  // 新C锚点也在同一发生时刻，真实告警筛选必须包含它，不能沿用旧B不同时间的25条假设。
  const alarms = [
    ...ordered.filter((value) => value.eventKey === 'alarm'),
    { messageId: seed.originalAlarmMessageId }
  ].sort((a, b) => b.messageId.localeCompare(a.messageId))
  const filtered = await publicPage(await refresh(page, seed))
  expect(filtered.ids).toEqual(alarms.slice(0, 20).map((value) => value.messageId))
  expect(filtered.levels.every((value) => value === 'WARNING')).toBe(true)
  expect(filtered.models.every((value) => value === seed.thingModelVersionId)).toBe(true)
  const [alarmResponse] = await Promise.all([
    responseFor(page, historyPath(seed)),
    page.getByTestId('device-event-history-next').click()
  ])
  expect((await publicPage(alarmResponse)).ids).toEqual(
    alarms.slice(20).map((value) => value.messageId)
  )
  await expect(rows(page)).toHaveCount(6)
  console.log('stage=HISTORY_FILTER_26 status=PASS')
  await fill(region, '事件时间从', '2000-01-01T00:00:00Z')
  await fill(region, '事件时间至', '2000-01-02T00:00:00Z')
  expect((await publicPage(await refresh(page, seed))).ids.length).toBe(0)
  await expect(region).toContainText('筛选范围内没有可读事件')
  await expect(page.getByTestId('device-event-history-next')).toBeDisabled()
  // 保留合法模型UUID，以真实服务端反向时间窗400验证错误状态。
  await fill(region, '事件时间从', seed.occurredAt)
  const invalid = await refresh(page, seed)
  expect(invalid.status()).toBe(400)
  await expect(page.getByTestId('device-event-history-error')).toContainText('读取不可用')
  const [resetResponse] = await Promise.all([
    responseFor(page, historyPath(seed)),
    region.getByRole('button', { name: '清除筛选', exact: true }).click()
  ])
  expect((await publicPage(resetResponse)).ids).toEqual(first.ids)
  await expect(page.getByTestId('device-event-history-error')).toHaveCount(0)

  console.log('stage=HISTORY_EMPTY_ERROR_RESET status=PASS')
  // 暂停真实后端200响应，不替换正文；关闭后释放不得恢复原目录/筛选/详情。
  const cdp = await page.context().newCDPSession(page)
  let heldId = ''
  let settle!: (status: number) => void
  const held = new Promise<number>((resolve) => {
    settle = resolve
  })
  await cdp.send('Fetch.enable', {
    patterns: [
      { urlPattern: '*8088' + historyPath(seed) + '*', requestStage: 'Response' },
      { urlPattern: '*3017' + historyPath(seed) + '*', requestStage: 'Response' }
    ]
  })
  cdp.on('Fetch.requestPaused', (event) => {
    if (
      new URL(event.request.url).pathname === historyPath(seed) &&
      new URL(event.request.url).searchParams.get('eventKey') === 'empty'
    ) {
      heldId = event.requestId
      settle(event.responseStatusCode ?? 0)
    } else void cdp.send('Fetch.continueRequest', { requestId: event.requestId }).catch(() => {})
  })
  await fill(region, '事件键', 'empty')
  await page.getByTestId('device-event-history-refresh').click()
  const heldStatus = await Promise.race([
    held,
    new Promise<number>((_, reject) =>
      setTimeout(() => reject(new Error('EVENT_REAL_RESPONSE_HOLD_TIMEOUT')), 15_000)
    )
  ])
  expect(heldStatus).toBe(200)
  await page.locator('.device-detail__back').click()
  await expect(page.getByTestId('device-event-history')).toHaveCount(0)
  let heldResponseReleased = false
  try {
    await cdp.send('Fetch.continueRequest', { requestId: heldId })
    heldResponseReleased = true
  } catch {
    /* 关闭会取消原fetch；不存在的旧请求也不能重建组件。 */
  }
  await cdp.send('Fetch.disable')
  await cdp.detach()
  const reopened = await openHistory(page, seed)
  expect(reopened.ids).toEqual(first.ids)
  await expect(rows(page)).toHaveCount(20)
  await expect(region.getByRole('textbox', { name: '事件键', exact: true })).toHaveValue('')
  await expect(page.getByTestId('device-event-detail')).toHaveCount(0)
  console.log(
    JSON.stringify({
      stage: 'BE-001-C-EVENT-HISTORY',
      seededMessages: seed.messages.length,
      observedMessages: seed.eventCount,
      precisionKinds: 2,
      heldResponseReleased,
      businessWrites: 0
    })
  )
})
