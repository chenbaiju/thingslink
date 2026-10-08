import { expect, test } from '@playwright/test'
import { login, enterProject, OWNER_EMAIL, OWNER_PASSWORD } from './helpers'

test.use({ trace: 'off', video: 'off', screenshot: 'off' })
test.afterEach(async ({ page }) => {
  await page.close()
})

test('实时票据工具：真实 WS/MQTT 首次签发、固定模型范围及关闭清理，不自动连接', async ({
  page
}) => {
  await login(page, OWNER_EMAIL, OWNER_PASSWORD)
  await enterProject(page, process.env.E2E_PROJECT_NAME ?? 'E2E项目')
  const fixture = await page.evaluate(async () => {
    const devicePath = '/src/api/device.ts',
      userPath = '/src/store/modules/user.ts',
      bindingPath = '/src/api/dashboard-binding.ts'
    const device = await import(devicePath),
      { useUserStore } = await import(userPath),
      binding = await import(bindingPath)
    const projectId = useUserStore().info.currentProjectId as string,
      suffix = Date.now()
    const type = await device.fetchCreateDeviceType(projectId, {
      typeKey: `ticket_${suffix}`,
      name: `票据模型${suffix}`,
      deviceKind: 'DIRECT',
      payloadProtocol: 'STANDARD',
      networkType: 'WIFI'
    })
    await device.fetchCreateDevicePropertyDefinition(projectId, type.id, {
      propertyKey: 'temperature',
      name: '温度',
      dataType: 'NUMBER',
      accessType: 'REPORT',
      sortOrder: 0
    })
    await device.fetchPublishDeviceType(projectId, type.id)
    const created = await device.fetchCreateDevice(projectId, {
      deviceTypeId: type.id,
      deviceKey: `ticket_${suffix}`,
      name: `票据设备${suffix}`
    })
    const metadata = await binding.fetchBindingMetadata(projectId, created.id)
    return {
      projectId,
      deviceId: created.id as string,
      name: created.name as string,
      modelVersionId: metadata.model.versionId as string
    }
  })
  await page.goto('/#/device/messages')
  const sockets: string[] = []
  page.on('websocket', (socket) => {
    sockets.push(new URL(socket.url()).pathname)
  })
  for (const protocol of ['WS', 'MQTT'] as const) {
    await page.getByRole('button', { name: '短期订阅票据', exact: true }).click()
    const dialog = page.getByRole('dialog', { name: '短期实时订阅票据', exact: true })
    if (protocol === 'MQTT') {
      await dialog.getByText('MQTT 3.1.1', { exact: true }).click()
      await expect(dialog.getByRole('radio', { name: 'MQTT 3.1.1', exact: true })).toBeChecked()
    }
    const device = dialog.getByRole('combobox', { name: '订阅设备', exact: true })
    await device.fill(fixture.name)
    await page.getByRole('option', { name: new RegExp(fixture.name) }).click()
    await expect(dialog).toContainText(fixture.modelVersionId)
    await dialog.getByRole('combobox', { name: '订阅属性', exact: true }).click()
    await page.getByRole('option', { name: 'temperature · NUMBER', exact: true }).click()
    await dialog.getByText(/不可变模型版本：/).click()
    const responsePromise = page.waitForResponse(
      (response) =>
        response.request().method() === 'POST' &&
        new URL(response.url()).pathname.endsWith('/realtime-tickets')
    )
    await dialog.getByRole('button', { name: '签发一次性票据', exact: true }).click()
    const response = await responsePromise
    expect(response.status()).toBe(201)
    expect(response.request().postDataJSON()).toEqual({
      protocol,
      eventTypes: ['device.property.report'],
      devices: [
        {
          deviceId: fixture.deviceId,
          expectedModelVersionId: fixture.modelVersionId,
          propertyKeys: ['temperature']
        }
      ]
    })
    // 首次响应秘密不进入 Node 断言、报告或附件；只在浏览器内检查并返回布尔值。
    await expect(dialog.getByRole('region', { name: '本次订阅连接参数' })).toBeVisible()
    expect(
      await page.evaluate(() => {
        const input = document.querySelector<HTMLInputElement>('input[aria-label="本次短期秘密"]')
        return !!input && /^tcrt1\.[0-9a-f-]{36}\.[A-Za-z0-9_-]{43}$/.test(input.value)
      })
    ).toBe(true)
    expect(
      await page.evaluate(
        () =>
          ![localStorage, sessionStorage].some((storage) =>
            Object.values(storage).some(
              (value) => typeof value === 'string' && value.includes('tcrt1.')
            )
          )
      )
    ).toBe(true)
    await dialog.getByRole('button', { name: '关闭并清除', exact: true }).click()
    await expect(dialog).toBeHidden()
    expect(
      await page.evaluate(() => !document.querySelector('input[aria-label="本次短期秘密"]'))
    ).toBe(true)
  }
  expect(sockets).not.toContain('/api/open/v1/realtime/ws')
})
