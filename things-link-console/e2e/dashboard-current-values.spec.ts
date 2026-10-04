import { expect, test, type Page } from '@playwright/test'
import { login, enterProject, OWNER_EMAIL, OWNER_PASSWORD } from './helpers'
import { currentValueMessage, publishCurrentValues } from './current-value-mqtt'
import { readBoundModelVersion } from './device-model-fixture'

test.use({ trace: 'off', video: 'off', screenshot: 'off' })
test('单设备完整当前值：真实MQTT、仪表越界、复合精度与本地分页', async ({ page }) => {
  test.setTimeout(180_000)
  await page.setViewportSize({ width: 1920, height: 1080 })
  await login(page, OWNER_EMAIL, OWNER_PASSWORD)
  await enterProject(page, 'E2E项目')
  const fixture = await page.evaluate(async () => {
    const apiPath = '/src/api/device.ts',
      userPath = '/src/store/modules/user.ts'
    const api = await import(apiPath),
      { useUserStore } = await import(userPath)
    const projectId = useUserStore().info.currentProjectId,
      suffix = Date.now()
    const deviceKey = `current_${suffix}`
    const type = await api.fetchCreateDeviceType(projectId, {
      typeKey: deviceKey,
      name: `完整当前值模型${suffix}`,
      deviceKind: 'DIRECT',
      payloadProtocol: 'STANDARD',
      networkType: 'WIFI'
    })
    const definitions = [
      {
        propertyKey: 'temperature',
        name: '温度',
        dataType: 'NUMBER',
        unit: '℃',
        decimalPlaces: 2,
        minimumValue: 0,
        maximumValue: 100
      },
      {
        propertyKey: 'payload',
        name: '复合对象',
        dataType: 'OBJECT',
        schema: JSON.stringify({
          type: 'object',
          additionalProperties: false,
          maxProperties: 2,
          properties: { big: { type: 'number' }, text: { type: 'string', maxLength: 4096 } }
        })
      },
      {
        propertyKey: 'samples',
        name: '列表值表格',
        dataType: 'LIST',
        schema: JSON.stringify({
          type: 'array',
          maxItems: 256,
          items: { type: 'string', maxLength: 4096 }
        })
      }
    ]
    for (const [index, definition] of definitions.entries())
      await api.fetchCreateDevicePropertyDefinition(projectId, type.id, {
        ...definition,
        accessType: 'REPORT',
        sortOrder: index
      })
    await api.fetchPublishDeviceType(projectId, type.id)
    const device = await api.fetchCreateDevice(projectId, {
      deviceTypeId: type.id,
      deviceKey,
      name: `完整当前值设备${suffix}`
    })
    const credential = await api.fetchGenerateCredential(projectId, device.id)
    return {
      projectId,
      deviceId: device.id as string,
      deviceKey,
      credentialId: credential.id as string,
      secret: credential.plainSecret as string
    }
  })
  try {
    const projectKey = process.env.E2E_PROJECT_KEY
    if (!projectKey) throw new Error('缺少真实上报项目标识')
    const modelVersion = await readBoundModelVersion(fixture.projectId, fixture.deviceId)
    await publishCurrentValues({
      projectKey,
      deviceKey: fixture.deviceKey,
      secret: fixture.secret,
      port: Number(process.env.EMQX_MQTT_PORT ?? 1883),
      payload: currentValueMessage(
        '{"temperature":12.5,"payload":{"big":9007199254740993.123456789,"text":"<script>window.__currentXss=1</script>"},"samples":["第一页A","第一页B","第二页C"]}',
        modelVersion
      )
    })
    fixture.secret = ''
    await page.goto('/#/dashboard/designer')
    await page.getByTestId('dashboard-create').click()
    await page.getByTestId('dashboard-name').fill('完整当前值验收')
    await page.getByTestId('dashboard-create-confirm').click()
    await expect(page).toHaveURL(/dashboardId=/)
    await page.getByRole('button', { name: '读取设备目录', exact: true }).click()
    const metadata = page.waitForResponse((response) =>
      new URL(response.url()).pathname.endsWith(`/${fixture.deviceId}/binding-metadata`)
    )
    await page.getByLabel('绑定设备', { exact: true }).selectOption(fixture.deviceId)
    expect((await metadata).status()).toBe(200)
    await select(page, '绑定组件类型', '仪表盘')
    await select(page, '绑定顶层属性', 'temperature（temperature）')
    await expect(page.getByRole('button', { name: '添加设备组件', exact: true })).toBeEnabled()
    await select(page, '绑定仪表量程', '显式设置边界')
    await page.getByRole('textbox', { name: '绑定仪表最小值', exact: true }).fill('0')
    await page.getByRole('textbox', { name: '绑定仪表最大值', exact: true }).fill('10')
    await add(page)
    await select(page, '绑定组件类型', '复合值')
    await select(page, '绑定顶层属性', 'payload（payload）')
    await add(page)
    await select(page, '绑定组件类型', '列表值表格')
    await select(page, '绑定顶层属性', 'samples（samples）')
    await page.getByRole('spinbutton', { name: '绑定每页行数', exact: true }).fill('2')
    await add(page)
    await page.reload()
    for (const kind of ['GAUGE', 'JSON_VIEW', 'TABLE'])
      await expect(page.locator(`[data-kind="${kind}"]`)).toHaveCount(1)
    const preview = page.getByRole('region', { name: '草稿设备数据预览' })
    // PUBACK只证明传输，等待真实管道落库并由预览读取，最长30秒。
    await expect(async () => {
      await page.getByRole('button', { name: '刷新草稿数据', exact: true }).click()
      await expect(preview.getByTestId('preview-gauge')).toHaveAttribute(
        'data-out-of-range',
        'true',
        { timeout: 3000 }
      )
    }).toPass({ timeout: 30_000, intervals: [1500, 2500] })
    await expect(preview).toContainText('超出量程')
    await expect(preview).toContainText('9007199254740993.123456789')
    await expect(preview).toContainText('<script>window.__currentXss=1</script>')
    expect(await page.evaluate(() => Object.hasOwn(window, '__currentXss'))).toBe(false)
    const list = preview.getByRole('region', { name: '完整列表快照' })
    await expect(list.getByTestId('preview-list-total')).toContainText('3')
    await expect(list).toContainText('第一页A')
    await expect(list).not.toContainText('第二页C')
    const requests: string[] = []
    const observe = (request: { url(): string }) => {
      if (request.url().includes('/api/v1/')) requests.push(new URL(request.url()).pathname)
    }
    page.on('request', observe)
    await list.getByRole('button', { name: '下一页列表', exact: true }).click()
    await expect(list).toContainText('第二页C')
    await list.getByRole('button', { name: '上一页列表', exact: true }).click()
    await expect(list).toContainText('第一页A')
    page.off('request', observe)
    expect(requests).toEqual([])
    // 复用同一仪表重绑MODEL：真实元数据量程0..100，保存重开后12.50不得判为越界。
    const gaugeId = await page.locator('[data-kind="GAUGE"]').getAttribute('data-component-id')
    expect(gaugeId).toBeTruthy()
    await rebindGauge(page, fixture.deviceId, 'MODEL')
    await page.reload()
    await expect(page.locator('[data-kind="GAUGE"]')).toHaveCount(1)
    await expect(page.locator('[data-kind="GAUGE"]')).toHaveAttribute('data-component-id', gaugeId!)
    await page.getByRole('button', { name: '刷新草稿数据', exact: true }).click()
    const modelGauge = preview.getByTestId('preview-gauge')
    await expect(modelGauge).toHaveAttribute('data-out-of-range', 'false')
    await expect(modelGauge).toContainText('量程：0 至 100')
    await expect(modelGauge.getByRole('img')).toHaveAttribute('aria-label', /12\.50.*量程内/)
    await expect(preview).toContainText('9007199254740993.123456789')
    // 恢复原显式量程，后续固定画布与窄屏仍验证同一组件的越界显示。
    await rebindGauge(page, fixture.deviceId, 'EXPLICIT')
    await expect(page.locator('[data-kind="GAUGE"]')).toHaveCount(1)
    await expect(page.locator('[data-kind="GAUGE"]')).toHaveAttribute('data-component-id', gaugeId!)
    await page.getByLabel('画布模式', { exact: true }).selectOption('FIXED_SCREEN')
    await expect(page.getByTestId('designer-save-state')).toHaveAttribute('data-status', 'saved')
    await page.reload()
    await expect(page.getByLabel('画布模式', { exact: true })).toHaveValue('FIXED_SCREEN')
    for (const kind of ['GAUGE', 'JSON_VIEW', 'TABLE'])
      await expect(page.locator(`[data-kind="${kind}"]`)).toHaveCount(1)
    await page.setViewportSize({ width: 375, height: 812 })
    await page.getByRole('button', { name: '刷新草稿数据', exact: true }).click()
    await expect(preview.getByTestId('preview-gauge')).toHaveAttribute('data-out-of-range', 'true')
    await expect(preview).toContainText('9007199254740993.123456789')
    await expect(list).toContainText('第一页A')
    await list.getByRole('button', { name: '下一页列表', exact: true }).click()
    await expect(list).toContainText('第二页C')
    expect(
      await preview.evaluate((element) => element.scrollWidth <= element.clientWidth + 1)
    ).toBe(true)
    await page.screenshot({ path: 'test-results/dashboard-current-values.png', fullPage: true })
  } finally {
    fixture.secret = ''
    await page.evaluate(
      async ({ projectId, deviceId, credentialId }) => {
        const apiPath = '/src/api/device.ts',
          api = await import(apiPath)
        await api.fetchRevokeCredential(projectId, deviceId, credentialId)
      },
      {
        projectId: fixture.projectId,
        deviceId: fixture.deviceId,
        credentialId: fixture.credentialId
      }
    )
  }
})
async function select(page: Page, label: string, option: string) {
  await page
    .locator('.el-select')
    .filter({ has: page.getByRole('combobox', { name: label, exact: true }) })
    .click()
  await page.getByRole('option', { name: option, exact: true }).click()
}
async function add(page: Page) {
  await page.getByRole('button', { name: '添加设备组件', exact: true }).click()
  await expect(page.getByTestId('designer-save-state')).toHaveAttribute('data-status', 'saved')
}

async function rebindGauge(page: Page, deviceId: string, mode: 'MODEL' | 'EXPLICIT') {
  await page.locator('[data-kind="GAUGE"]').click()
  await page.getByRole('button', { name: '读取设备目录', exact: true }).click()
  const metadata = page.waitForResponse((response) =>
    new URL(response.url()).pathname.endsWith(`/${deviceId}/binding-metadata`)
  )
  await page.getByLabel('绑定设备', { exact: true }).selectOption(deviceId)
  expect((await metadata).status()).toBe(200)
  await select(page, '绑定顶层属性', 'temperature（temperature）')
  await select(page, '绑定仪表量程', mode === 'MODEL' ? '使用物模型边界' : '显式设置边界')
  if (mode === 'EXPLICIT') {
    await page.getByRole('textbox', { name: '绑定仪表最小值', exact: true }).fill('0')
    await page.getByRole('textbox', { name: '绑定仪表最大值', exact: true }).fill('10')
  }
  await page.getByRole('button', { name: '替换选中组件绑定', exact: true }).click()
  await expect(page.getByTestId('designer-save-state')).toHaveAttribute('data-status', 'saved')
}
