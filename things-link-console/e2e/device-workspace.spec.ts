import { expect, test } from '@playwright/test'
import { enterProject, login, OWNER_EMAIL, OWNER_PASSWORD } from './helpers'

// 本旅程只创建类型和设备，不读取或拍摄凭据；核心请求全部经过真实后端。
test.use({ trace: 'off', video: 'off', screenshot: 'off' })

test('物模型工作区：已发布类型可经预选入口创建设备并重读确认绑定', async ({ page }) => {
  await login(page, OWNER_EMAIL, OWNER_PASSWORD)
  await enterProject(page, process.env.E2E_PROJECT_NAME ?? 'E2E项目')
  const fixture = await page.evaluate(async () => {
    const devicePath = '/src/api/device.ts'
    const userPath = '/src/store/modules/user.ts'
    const device = await import(devicePath)
    const { useUserStore } = await import(userPath)
    const projectId = useUserStore().info.currentProjectId as string
    const suffix = Date.now()
    const type = await device.fetchCreateDeviceType(projectId, {
      typeKey: `workspace_${suffix}`,
      name: `工作区模型${suffix}`,
      deviceKind: 'DIRECT',
      payloadProtocol: 'STANDARD',
      networkType: 'WIFI'
    })
    await device.fetchCreateDevicePropertyDefinition(projectId, type.id!, {
      propertyKey: 'temperature',
      name: '温度',
      dataType: 'NUMBER',
      accessType: 'REPORT',
      sortOrder: 0
    })
    await device.fetchPublishDeviceType(projectId, type.id!)
    return {
      projectId,
      typeId: type.id!,
      typeName: type.name!,
      deviceKey: `workspace_device_${suffix}`,
      deviceName: `工作区设备${suffix}`
    }
  })
  await page.goto(
    `/#/device/types?resourceId=${fixture.typeId}&contextProjectId=${fixture.projectId}`
  )
  const workspace = page.locator('.device-types__workspace')
  await expect(workspace).toContainText(fixture.typeName)
  await expect(workspace).toContainText('已发布 · 物模型已冻结')
  await expect(workspace.locator('.el-loading-mask')).toHaveCount(0)
  await expect(page.locator('.device-types')).toHaveCSS('opacity', '1')
  if (process.env.E2E_RUN_DIR)
    await page.screenshot({ path: `${process.env.E2E_RUN_DIR}/type-workspace.png`, fullPage: true })
  await expect(workspace.getByRole('button', { name: '继续创建设备', exact: true })).toHaveCount(0)
  await page.goto(
    `/#/device/list?createTypeId=${fixture.typeId}&contextProjectId=${fixture.projectId}`
  )
  const form = page.getByRole('dialog', { name: '创建设备', exact: true })
  await expect(form).toBeVisible()
  await expect(form.locator('.el-select')).toContainText(fixture.typeName)
  await expect(page).not.toHaveURL(/createTypeId=/)
  await form.getByRole('textbox', { name: '设备名称', exact: true }).fill(fixture.deviceName)
  await form.getByRole('textbox', { name: '设备标识符', exact: true }).fill(fixture.deviceKey)
  const saved = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      new URL(response.url()).pathname === `/api/v1/projects/${fixture.projectId}/devices`
  )
  await form.getByRole('button', { name: '确定', exact: true }).click()
  const response = await saved
  expect(response.ok()).toBeTruthy()
  const created = await response.json()
  expect(created.deviceTypeId).toBe(fixture.typeId)
  await expect(form).toBeHidden()
  const row = page
    .locator('.device-list__data tbody tr')
    .filter({ has: page.getByText(fixture.deviceName, { exact: true }) })
  await expect(row).toBeVisible()
  await expect(row).toContainText(fixture.typeName)
  const detail = await page.evaluate(
    async ({ projectId, id }) => {
      const path = '/src/api/device.ts'
      const device = await import(path)
      const value = await device.fetchDeviceDetail(projectId, id)
      return { id: value.id, typeId: value.deviceTypeId, name: value.name }
    },
    { projectId: fixture.projectId, id: created.id }
  )
  expect(detail).toEqual({ id: created.id, typeId: fixture.typeId, name: fixture.deviceName })
  if (process.env.E2E_RUN_DIR)
    await page.screenshot({ path: `${process.env.E2E_RUN_DIR}/created-device.png`, fullPage: true })
})
