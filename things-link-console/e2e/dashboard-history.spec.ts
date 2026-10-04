import { expect, test, type Page, type Request, type Route } from '@playwright/test'
import { login, enterProject, OWNER_EMAIL, OWNER_PASSWORD } from './helpers'
import { readBoundModelVersion } from './device-model-fixture'
import { currentValueMessage, publishCurrentValues } from './current-value-mqtt'
test.use({ trace: 'off', video: 'off', screenshot: 'off' })
test('历史图表：真实上报、多系列完整保存、时间预设和版本事实', async ({ page }) => {
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
      suffix = Date.now(),
      key = `history_${suffix}`
    const type = await api.fetchCreateDeviceType(projectId, {
      typeKey: key,
      name: `历史模型${suffix}`,
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
    for (let n = 1; n <= 1; n++) {
      const device = await api.fetchCreateDevice(projectId, {
        deviceTypeId: type.id,
        deviceKey: `${key}_${n}`,
        name: `历史设备${suffix}_${n}`
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
    const first = fixture.devices[0]!
    // PUBACK只确认Broker接收；进入图表前，必须经真实HTTP确认本轮设备历史已持久化。
    await expect
      .poll(
        () =>
          page.evaluate(
            async ({ projectId, deviceId }) => {
              const apiPath = '/src/api/device.ts',
                api = await import(apiPath),
                now = Date.now()
              const history = await api.fetchPropertyHistory(projectId, deviceId, {
                propertyKey: 'temperature',
                from: new Date(now - 3600000).toISOString(),
                to: new Date(now).toISOString(),
                granularity: 'RAW',
                aggregation: 'AVG'
              })
              return history.points.map((point: { value: number }) => point.value)
            },
            { projectId: fixture.projectId, deviceId: first.id }
          ),
        { timeout: 30_000, intervals: [1000], message: '真实MQTT上报必须先进入设备历史' }
      )
      .toContain(10.5)
    await page.goto('/#/dashboard/designer')
    await page.getByTestId('dashboard-create').click()
    await page.getByTestId('dashboard-name').fill('历史图表统一验收')
    await page.getByTestId('dashboard-create-confirm').click()
    await expect(page).toHaveURL(/dashboardId=/)
    await page.getByRole('button', { name: '读取设备目录', exact: true }).click()
    const binding = page.waitForResponse((response) =>
      new URL(response.url()).pathname.endsWith(`/${first.id}/binding-metadata`)
    )
    await page.getByLabel('绑定设备', { exact: true }).selectOption(first.id)
    expect((await binding).status()).toBe(200)
    await page.getByTestId('variable-new').click()
    await page.getByLabel('变量标题', { exact: true }).fill('历史设备')
    await page.getByLabel('变量类型', { exact: true }).selectOption('DEVICE_SINGLE')
    await page.getByLabel('变量模型', { exact: true }).selectOption('__binding')
    await page.getByRole('button', { name: '读取变量设备目录', exact: true }).click()
    await page.getByLabel(`默认设备 ${first.name}`, { exact: true }).check()
    await page.getByTestId('variable-save').click()
    await saved(page)
    const variableKey = (await page
      .getByRole('button', { name: '编辑变量 历史设备', exact: true })
      .getAttribute('data-testid'))!.replace('variable-edit-', '')
    await page.getByLabel('时间变量标题', { exact: true }).fill('查询时段')
    await page.getByTestId('time-variable-save').click()
    await saved(page)
    const timeKey = await page
      .getByLabel('历史系列1时间变量', { exact: true })
      .locator('option')
      .filter({ hasText: '查询时段' })
      .getAttribute('value')
    if (!timeKey) throw new Error('时间变量未进入草稿')
    await page.getByLabel('历史图表标题', { exact: true }).fill('真实温度趋势')
    for (let index = 1; index <= 2; index++) {
      if (index > 1) await page.getByRole('button', { name: '添加历史系列', exact: true }).click()
      await page
        .getByLabel(`历史系列${index}标题`, { exact: true })
        .fill(index === 1 ? '原始温度' : '温度聚合')
      await page.getByLabel(`历史系列${index}设备变量`, { exact: true }).selectOption(variableKey)
      await page.getByLabel(`历史系列${index}时间变量`, { exact: true }).selectOption(timeKey)
      await page.waitForTimeout(1100)
      await page.getByRole('button', { name: `读取系列${index}设备目录`, exact: true }).click()
      const metadata = page.waitForResponse((response) =>
        new URL(response.url()).pathname.endsWith(`/${first.id}/binding-metadata`)
      )
      await page.getByLabel(`历史系列${index}元数据设备`, { exact: true }).selectOption(first.id)
      expect((await metadata).status()).toBe(200)
      await page.getByLabel(`历史系列${index}属性`, { exact: true }).selectOption('temperature')
      if (index > 1)
        await page.getByLabel(`历史系列${index}粒度`, { exact: true }).selectOption('ONE_MINUTE')
    }
    await page.getByTestId('history-component-add').click()
    await saved(page)
    await expect(page.locator('[data-kind="LINE_CHART"]')).toHaveCount(1)
    await page.getByRole('button', { name: '编辑时间变量 查询时段', exact: true }).click()
    await expect(page.getByTestId('time-variable-delete')).toBeDisabled()
    await page.reload()
    await expect(page.locator('[data-kind="LINE_CHART"]')).toHaveCount(1)
    const preview = page.getByRole('region', { name: '草稿设备数据预览' })
    // 自动首读必须完成后再观察显式刷新；两轮请求不能混作一轮去重失败。
    await expect(page.getByTestId('preview-rest-state')).toHaveAttribute('data-state', 'REST_READY')
    const history = preview.getByRole('region', { name: '版本化历史快照' })
    await expect(history.locator('[data-history-series]')).toHaveCount(2)
    await expect(history.locator('[data-history-series]').nth(0)).toContainText('请求粒度：RAW')
    await expect(history.locator('[data-history-series]').nth(1)).toContainText(
      '请求粒度：ONE_MINUTE'
    )
    const requests: string[] = []
    const historyPath = `/api/v1/projects/${fixture.projectId}/devices/${first.id}/telemetry/property/history/versioned`
    const collectHistoryRequest = (request: Request) => {
      if (request.method() === 'GET' && new URL(request.url()).pathname === historyPath)
        requests.push(request.url())
    }
    const historyResponses: {
      status: number
      body: { requestedGranularity: string; points: { value: number }[] }
    }[] = []
    const matchesHistory = (url: URL) => url.pathname === historyPath
    // 草稿读取完成后会关闭流；CDP不保证还能重读正文。只透传本轮真实后端响应，
    // 取证后原样交给页面，不造数据、不放宽状态/粒度/点值与请求次数断言。
    const forwardHistory = async (route: Route) => {
      const response = await route.fetch({ maxRedirects: 0 })
      historyResponses.push({ status: response.status(), body: await response.json() })
      await route.fulfill({ response })
    }
    await page.route(matchesHistory, forwardHistory)
    page.on('request', collectHistoryRequest)
    await page.getByRole('button', { name: '刷新草稿数据', exact: true }).click()
    await expect.poll(() => requests.length).toBe(2)
    await expect(page.getByTestId('preview-rest-state')).toHaveAttribute('data-state', 'REST_READY')
    await expect(history).toContainText('历史读取完成')
    await expect(history.locator('[data-history-series]')).toHaveCount(2)
    await expect(
      history.getByRole('img', { name: '原始温度历史趋势，版本变化或缺桶断开', exact: true })
    ).toHaveCount(1)
    await expect(history).toContainText('10.5')
    await expect(history).toContainText('历史单位未提供')
    expect(requests).toHaveLength(2)
    await page.unroute(matchesHistory, forwardHistory)
    expect(historyResponses).toHaveLength(2)
    expect(historyResponses.map((response) => response.status)).toEqual([200, 200])
    const initial = ['RAW', 'ONE_MINUTE'].map(
      (granularity) =>
        historyResponses.find((response) => response.body.requestedGranularity === granularity)
          ?.body
    )
    expect(initial).toHaveLength(2)
    expect(initial[0]!.requestedGranularity).toBe('RAW')
    expect(initial[0]!.points.some((point) => point.value === 10.5)).toBe(true)
    expect(initial[1]!.requestedGranularity).toBe('ONE_MINUTE')
    const aggregateSeries = history.locator('[data-history-series]').nth(1)
    await expect(aggregateSeries).toContainText('实际粒度：ONE_MINUTE')
    await expect(aggregateSeries.getByRole('img')).toHaveCount(initial[1]!.points.length ? 1 : 0)
    if (!initial[1]!.points.length)
      await expect(aggregateSeries).toContainText('当前时间范围没有数据')

    const firstRequests = requests.map((url) => new URL(url).searchParams)
    expect(firstRequests[0]!.get('to')).toBe(firstRequests[1]!.get('to'))
    expect(
      Date.parse(firstRequests[0]!.get('to')!) - Date.parse(firstRequests[0]!.get('from')!)
    ).toBe(3600000)
    const before = requests.length
    await history.locator('summary').first().click()
    await expect(history.locator('tbody').first()).toContainText('10.5')
    expect(requests).toHaveLength(before)
    await page.waitForTimeout(1100)
    await page.getByLabel('预览时间 查询时段', { exact: true }).selectOption('LAST_24_HOURS')
    await expect(
      history.getByRole('img', { name: '原始温度历史趋势，版本变化或缺桶断开', exact: true })
    ).toHaveCount(1)
    await expect.poll(() => requests.length).toBe(4)
    const next = new URL(requests[2]!).searchParams
    expect(Date.parse(next.get('to')!) - Date.parse(next.get('from')!)).toBe(86400000)
    page.off('request', collectHistoryRequest)
    await page.reload()
    await expect(page.getByLabel('预览时间 查询时段', { exact: true })).toHaveValue('LAST_1_HOUR')
    await page.setViewportSize({ width: 375, height: 812 })
    await page.getByRole('button', { name: '刷新草稿数据', exact: true }).click()
    await expect(
      history.getByRole('img', { name: '原始温度历史趋势，版本变化或缺桶断开', exact: true })
    ).toHaveCount(1)
    const dimensions = await preview.evaluate((element) => ({
      scroll: element.scrollWidth,
      client: element.clientWidth
    }))
    expect(dimensions.scroll).toBeLessThanOrEqual(dimensions.client + 1)
    await page.screenshot({ path: 'test-results/dashboard-history.png', fullPage: true })
  } finally {
    for (const device of fixture.devices) device.secret = ''
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
