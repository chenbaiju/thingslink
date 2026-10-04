import { expect, test, type Page } from '@playwright/test'
import { login, enterProject, OWNER_EMAIL, OWNER_PASSWORD } from './helpers'
import { readBoundModelVersion } from './device-model-fixture'
import { currentValueMessage, publishCurrentValues } from './current-value-mqtt'
import { captureRealJsonResponses } from './real-json-response'
type CatalogBody = { items: { deviceId: string }[]; hasMore: boolean }
test.use({ trace: 'off', video: 'off', screenshot: 'off' })
test('设备变量：精确目录、共享选择器双表、内存选择与持久默认分离', async ({ page }) => {
  test.setTimeout(180_000)
  // 单步先失败，让finally仍有时间清理并保留原始等待位置。
  page.setDefaultTimeout(20_000)
  await page.setViewportSize({ width: 1920, height: 1080 })
  await login(page, OWNER_EMAIL, OWNER_PASSWORD)
  await enterProject(page, 'E2E项目')
  const fixture = await page.evaluate(async () => {
    const apiPath = '/src/api/device.ts',
      userPath = '/src/store/modules/user.ts'
    const api = await import(apiPath),
      { useUserStore } = await import(userPath)
    const projectId = useUserStore().info.currentProjectId,
      suffix = Date.now(),
      key = `variables_${suffix}`
    const type = await api.fetchCreateDeviceType(projectId, {
      typeKey: key,
      name: `变量模型${suffix}`,
      deviceKind: 'DIRECT',
      payloadProtocol: 'STANDARD',
      networkType: 'WIFI'
    })
    await api.fetchCreateDevicePropertyDefinition(projectId, type.id, {
      propertyKey: 'temperature',
      name: '温度',
      dataType: 'NUMBER',
      unit: '℃',
      decimalPlaces: 2,
      accessType: 'REPORT',
      sortOrder: 0
    })
    await api.fetchPublishDeviceType(projectId, type.id)
    const devices = []
    for (let n = 1; n <= 3; n++) {
      const device = await api.fetchCreateDevice(projectId, {
        deviceTypeId: type.id,
        deviceKey: `${key}_${n}`,
        name: `变量设备${suffix}_${n}`
      })
      const credential = await api.fetchGenerateCredential(projectId, device.id)
      devices.push({
        id: device.id as string,
        key: device.deviceKey as string,
        name: device.name as string,
        credentialId: credential.id as string,
        secret: credential.plainSecret as string
      })
    }
    return { projectId: projectId as string, devices }
  })
  const captured = await captureRealJsonResponses(
    page,
    `/api/v1/projects/${fixture.projectId}/devices/catalog`
  )
  try {
    const projectKey = process.env.E2E_PROJECT_KEY
    if (!projectKey) throw new Error('缺少真实上报项目标识')
    for (const [index, device] of fixture.devices.entries()) {
      await publishCurrentValues({
        projectKey,
        deviceKey: device.key,
        secret: device.secret,
        port: Number(process.env.EMQX_MQTT_PORT ?? 1883),
        payload: currentValueMessage(
          `{"temperature":${(index + 1) * 10 + 0.5}}`,
          await readBoundModelVersion(fixture.projectId, device.id)
        )
      })
      device.secret = ''
    }
    const first = fixture.devices[0]!,
      second = fixture.devices[1]!
    await page.goto('/#/dashboard/designer')
    await page.getByTestId('dashboard-create').click()
    await page.getByTestId('dashboard-name').fill('设备变量统一验收')
    await page.getByTestId('dashboard-create-confirm').click()
    await expect(page).toHaveURL(/dashboardId=/)
    await page.getByRole('button', { name: '读取设备目录', exact: true }).click()
    const binding = page.waitForResponse((response) =>
      new URL(response.url()).pathname.endsWith(`/${first.id}/binding-metadata`)
    )
    await page.getByLabel('绑定设备', { exact: true }).selectOption(first.id)
    expect((await binding).status()).toBe(200)
    await page.getByTestId('variable-new').click()
    await page.getByLabel('变量标题', { exact: true }).fill('对比设备')
    await page.getByLabel('变量类型', { exact: true }).selectOption('DEVICE_MULTI')
    await page.getByLabel('变量模型', { exact: true }).selectOption('__binding')
    await page.getByLabel('变量最多设备数', { exact: true }).fill('3')
    await page.getByRole('button', { name: '读取变量设备目录', exact: true }).click()
    await page.getByLabel(`默认设备 ${first.name}`, { exact: true }).check()
    await page.getByTestId('variable-save').click()
    await saved(page)
    const variableKey = (await page
      .getByRole('button', { name: '编辑变量 对比设备', exact: true })
      .getAttribute('data-testid'))!.replace('variable-edit-', '')
    await page.getByLabel('组件设备变量', { exact: true }).selectOption(variableKey)
    await page.getByLabel('选择器每页数量', { exact: true }).fill('2')
    for (const title of ['主设备选择', '同步设备选择']) {
      await page.getByLabel('选择器标题', { exact: true }).fill(title)
      await page.getByTestId('variable-component-add').click()
      await saved(page)
    }
    await page.getByRole('button', { name: '编辑变量 对比设备', exact: true }).click()
    await page.getByRole('button', { name: '读取变量设备目录', exact: true }).click()
    const metadata = page.waitForResponse((response) =>
      new URL(response.url()).pathname.endsWith(`/${first.id}/binding-metadata`)
    )
    await page.getByLabel('变量属性元数据设备', { exact: true }).selectOption(first.id)
    expect((await metadata).status()).toBe(200)
    await page.getByLabel('变量组件类型', { exact: true }).selectOption('TABLE')
    await page.getByLabel('组件设备变量', { exact: true }).selectOption(variableKey)
    await page.getByLabel('多设备表每页行数', { exact: true }).fill('1')
    await page.getByLabel('第1列标题', { exact: true }).fill('温度')
    await page.getByLabel('第1列属性', { exact: true }).selectOption('temperature')
    for (const title of ['设备对比甲', '设备对比乙']) {
      await page.getByLabel('多设备表标题', { exact: true }).fill(title)
      await page.getByTestId('variable-component-add').click()
      await saved(page)
    }
    await expect(page.locator('[data-kind="DEVICE_SELECTOR"]')).toHaveCount(2)
    await expect(page.locator('[data-kind="TABLE"]')).toHaveCount(2)
    await expect(page.getByTestId('variable-delete')).toBeDisabled()
    await page.reload()
    await expect(page.locator('[data-kind="TABLE"]')).toHaveCount(2)
    const preview = page.getByRole('region', { name: '草稿设备数据预览' })
    const selectors = preview.locator(`[data-preview-selector="${variableKey}"]`)
    await expect(selectors).toHaveCount(2)
    for (const selector of await selectors.all())
      await expect(selector.locator(`[data-selected-device="${first.id}"]`)).toBeVisible()
    await page.getByRole('button', { name: '刷新草稿数据', exact: true }).click()
    const tables = preview.getByRole('region', { name: '多设备当前值快照' })
    await expect(tables).toHaveCount(2)
    for (const table of await tables.all()) {
      await expect(table).toContainText('10.50 ℃')
      await expect(table).toContainText('完整设备数：1')
    }
    // 每轮两次事实读取遵守4次/秒预算；等待窗口而非绕过预算或伪造响应。
    await page.waitForTimeout(1100)
    const catalog = page.waitForResponse((response) =>
      new URL(response.url()).pathname.endsWith('/devices/catalog')
    )
    await selectors.nth(0).getByRole('button', { name: '读取可选设备', exact: true }).click()
    const catalogResponse = await catalog
    expect(catalogResponse.status()).toBe(200)
    const catalogBody = captured.read<CatalogBody>(catalogResponse)
    expect(catalogBody.items).toHaveLength(2)
    expect(catalogBody.hasMore).toBe(true)
    expect(catalogBody.items.map((item: { deviceId: string }) => item.deviceId)).not.toContain(
      first.id
    )
    await expect(selectors.nth(0).locator(`[data-selected-device="${first.id}"]`)).toBeVisible()
    // 选择变化立即使旧目录节点退役；check的节点后置复核会等待已被清除的旧复选框。
    const secondChoice = selectors.nth(0).getByLabel(second.name, { exact: true })
    await expect(secondChoice).not.toBeChecked()
    await secondChoice.click()
    for (const selector of await selectors.all())
      await expect(selector.locator(`[data-selected-device="${second.id}"]`)).toBeVisible()
    for (const table of await tables.all()) await expect(table).toContainText('完整设备数：2')
    await expect(page.getByTestId('preview-rest-state')).toHaveAttribute('data-state', 'REST_READY')
    // S12-4k：变量变化使旧目录代次及游标失效，已选设备仍由完整事实轮保留。
    const nextCatalogPage = selectors
      .nth(0)
      .getByRole('button', { name: '下一页可选设备', exact: true })
    await expect(nextCatalogPage).toBeDisabled()
    const requests: string[] = [],
      observe = (request: { url(): string }) => {
        if (request.url().includes('/api/v1/')) requests.push(request.url())
      }
    page.on('request', observe)
    for (const table of await tables.all()) {
      await table.getByRole('button', { name: '下一页设备值', exact: true }).click()
      await expect(table).toContainText(second.name)
      await expect(table).toContainText('20.50 ℃')
    }
    page.off('request', observe)
    expect(requests).toEqual([])
    // 新代次须显式重读首页取得游标；不能等待已失效的旧游标自动恢复。
    const refreshedCatalog = page.waitForResponse(
      (response) => new URL(response.url()).pathname.endsWith('/devices/catalog'),
      { timeout: 20_000 }
    )
    await selectors.nth(0).getByRole('button', { name: '读取可选设备', exact: true }).click()
    const refreshedResponse = await refreshedCatalog
    expect(refreshedResponse.status()).toBe(200)
    const refreshedBody = captured.read<CatalogBody>(refreshedResponse)
    expect(refreshedBody.items).toHaveLength(2)
    expect(refreshedBody.hasMore).toBe(true)
    await expect(nextCatalogPage).toBeEnabled()
    await nextCatalogPage.click()
    await expect(selectors.nth(0).getByLabel(first.name, { exact: true })).toBeVisible()
    await expect(selectors.nth(0).locator(`[data-selected-device="${second.id}"]`)).toBeVisible()
    // 重开只恢复持久默认，运行选择未调用草稿保存。
    await page.reload()
    await expect(selectors.nth(0).locator(`[data-selected-device="${first.id}"]`)).toBeVisible()
    await expect(selectors.nth(0).locator(`[data-selected-device="${second.id}"]`)).toHaveCount(0)
    await page.getByRole('button', { name: '刷新草稿数据', exact: true }).click()
    for (const table of await tables.all()) await expect(table).toContainText('完整设备数：1')
    await page.setViewportSize({ width: 375, height: 812 })
    expect(
      await preview.evaluate((element) => element.scrollWidth <= element.clientWidth + 1)
    ).toBe(true)
    await page.screenshot({ path: 'test-results/dashboard-variables.png', fullPage: true })
  } finally {
    for (const device of fixture.devices) device.secret = ''
    await captured.stop()
    await page.evaluate(
      async ({ projectId, devices }) => {
        const apiPath = '/src/api/device.ts',
          api = await import(apiPath)
        for (const device of devices)
          await api.fetchRevokeCredential(projectId, device.id, device.credentialId)
      },
      {
        projectId: fixture.projectId,
        devices: fixture.devices.map(({ id, credentialId }) => ({ id, credentialId }))
      }
    )
  }
})
async function saved(page: Page) {
  await expect(page.getByTestId('designer-save-state')).toHaveAttribute('data-status', 'saved')
}
