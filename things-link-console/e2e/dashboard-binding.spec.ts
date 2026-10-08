import { expect, test } from '@playwright/test'
import { login, enterProject, OWNER_EMAIL, OWNER_PASSWORD } from './helpers'

/** 数据准备使用真实Console API；绑定、保存重开和预览均由浏览器操作，不mock后端结果。 */
test('单设备绑定：权威模型选择、保存重开与草稿快照', async ({ page }) => {
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
  await page
    .locator('.el-select')
    .filter({ has: page.getByRole('combobox', { name: '绑定组件类型', exact: true }) })
    .click()
  await page.getByRole('option', { name: '设备状态', exact: true }).click()
  await page.getByRole('button', { name: '添加设备组件', exact: true }).click()
  await expect(page.locator('[data-kind="STATUS"]')).toHaveCount(1)
  await expect(page.getByTestId('designer-save-state')).toHaveAttribute('data-status', 'saved')
  await page.getByRole('textbox', { name: '绑定组件标题', exact: true }).fill('已重新绑定的状态')
  await page.getByRole('button', { name: '替换选中组件绑定', exact: true }).click()
  await expect(page.locator('[data-kind="STATUS"]')).toHaveCount(1)
  await expect(page.getByTestId('designer-save-state')).toHaveAttribute('data-status', 'saved')
  await page.reload()
  await expect(page.locator('[data-kind="VALUE_CARD"]')).toHaveCount(1)
  await expect(page.locator('[data-kind="STATUS"]')).toHaveCount(1)
  await page.getByRole('button', { name: '刷新草稿数据', exact: true }).click()
  const preview = page.getByRole('region', { name: '草稿设备数据预览' })
  await expect(preview).toContainText('暂无采集值')
  await expect(preview).toContainText('未激活')
  await expect(preview).toContainText('已重新绑定的状态')
  await page.screenshot({ path: 'test-results/dashboard-binding-preview.png', fullPage: true })
})
