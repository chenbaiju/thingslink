import { expect, test } from '@playwright/test'
import { login, enterProject, OWNER_EMAIL, OWNER_PASSWORD } from './helpers'

test.use({ trace: 'off', video: 'off', screenshot: 'off' })
/** 真实HTTP恢复；仅瞬断反例中断浏览器请求，不伪造成功数据。 */
test('草稿恢复：自动首读、独立周期校准与网络失败显式恢复', async ({ page }) => {
  test.setTimeout(180_000)
  await page.setViewportSize({ width: 1920, height: 1080 })
  await login(page, OWNER_EMAIL, OWNER_PASSWORD)
  await enterProject(page, 'E2E项目')
  const fixture = await page.evaluate(async () => {
    const devicePath = '/src/api/device.ts'
    const userPath = '/src/store/modules/user.ts'
    const api = await import(devicePath)
    const { useUserStore } = await import(userPath)
    const projectId = useUserStore().info.currentProjectId
    const suffix = Date.now()
    const type = await api.fetchCreateDeviceType(projectId, {
      typeKey: `binding_${suffix}`,
      name: `绑定模型${suffix}`,
      deviceKind: 'DIRECT',
      payloadProtocol: 'STANDARD',
      networkType: 'WIFI'
    })
    await api.fetchCreateDevicePropertyDefinition(projectId, type.id, {
      propertyKey: 'temperature',
      name: '温度',
      accessType: 'REPORT',
      dataType: 'NUMBER',
      unit: '℃',
      decimalPlaces: 2,
      sortOrder: 0
    })
    await api.fetchPublishDeviceType(projectId, type.id)
    const device = await api.fetchCreateDevice(projectId, {
      deviceTypeId: type.id,
      deviceKey: `binding_${suffix}`,
      name: `绑定设备${suffix}`
    })
    return { deviceId: device.id as string }
  })
  await page.goto('/#/dashboard/designer')
  await page.getByTestId('dashboard-create').click()
  await page.getByTestId('dashboard-name').fill('设备绑定草稿')
  await page.getByTestId('dashboard-create-confirm').click()
  await expect(page).toHaveURL(/dashboardId=/)
  await page.getByRole('button', { name: '读取设备目录', exact: true }).click()
  await expect(
    page.getByLabel('绑定设备', { exact: true }).locator(`option[value="${fixture.deviceId}"]`)
  ).toHaveCount(1)
  const metadataResponse = page.waitForResponse((response) =>
    new URL(response.url()).pathname.endsWith(`/${fixture.deviceId}/binding-metadata`)
  )
  await page.getByLabel('绑定设备', { exact: true }).selectOption(fixture.deviceId)
  expect((await metadataResponse).status()).toBe(200)
  await page
    .locator('.el-select')
    .filter({ has: page.getByRole('combobox', { name: '绑定顶层属性', exact: true }) })
    .click()
  await page.getByRole('option', { name: 'temperature（temperature）', exact: true }).click()
  await page.getByRole('button', { name: '添加设备组件', exact: true }).click()
  await expect(page.locator('[data-kind="VALUE_CARD"]')).toHaveCount(1)
  await expect(page.getByTestId('designer-save-state')).toHaveAttribute('data-status', 'saved')
  await page.reload()
  const status = page.getByTestId('preview-rest-state')
  const preview = page.getByRole('region', { name: '草稿设备数据预览' })
  await expect(status).toHaveAttribute('data-state', 'REST_READY')
  await expect(preview).toContainText('暂无采集值')
  // 真实60秒定时校准，不快进浏览器时钟或伪造数据接口。
  const firstReadyAt = Date.now()
  const requests: number[] = []
  const observe = (request: import('@playwright/test').Request) => {
    if (new URL(request.url()).pathname.endsWith('/current-value-snapshots/query'))
      requests.push(Date.now())
  }
  page.on('request', observe)
  await expect.poll(() => requests.length, { timeout: 70_000, intervals: [1000] }).toBe(1)
  // UI观察存在毫秒传递延迟；精确60秒边界另由纯调度测试验证。
  expect(requests[0]! - firstReadyAt).toBeGreaterThanOrEqual(59_000)
  await expect(status).toHaveAttribute('data-state', 'REST_READY')
  await expect(preview).toContainText('暂无采集值')
  page.off('request', observe)
  // 只注入一次网络断开；后续恢复重新走真实后端，不用route.fulfill替代权威事实。
  const currentRoute = '**/current-value-snapshots/query'
  await page.route(currentRoute, (route) => route.abort('connectionfailed'), { times: 1 })
  await page.getByRole('button', { name: '刷新草稿数据', exact: true }).click()
  await expect(status).toHaveAttribute('data-state', 'RETRY_REQUIRED')
  await expect(preview).not.toContainText('暂无采集值')
  await expect(preview).toContainText('自动校准已停止')
  await page.getByRole('button', { name: '刷新草稿数据', exact: true }).click()
  await expect(status).toHaveAttribute('data-state', 'REST_READY')
  await expect(preview).toContainText('暂无采集值')
})
