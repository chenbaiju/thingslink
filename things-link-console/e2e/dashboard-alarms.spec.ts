import { expect, test, type Page } from '@playwright/test'
import { login, enterProject, OWNER_EMAIL, OWNER_PASSWORD } from './helpers'
import { readBoundModelVersion } from './device-model-fixture'
import { currentValueMessage, publishCurrentValues } from './current-value-mqtt'

test.use({ trace: 'off', video: 'off', screenshot: 'off' })
test('告警列表：真实规则上报、三轴过滤、共享分页与保存恢复', async ({ page }) => {
  test.setTimeout(180_000)
  await page.setViewportSize({ width: 1920, height: 1080 })
  await login(page, OWNER_EMAIL, OWNER_PASSWORD)
  await enterProject(page, 'E2E项目')
  const fixture = await page.evaluate(async () => {
    const devicePath = '/src/api/device.ts',
      userPath = '/src/store/modules/user.ts'
    const api = await import(devicePath),
      { useUserStore } = await import(userPath)
    const projectId = useUserStore().info.currentProjectId as string
    const suffix = Date.now(),
      key = `alarms_${suffix}`
    const type = await api.fetchCreateDeviceType(projectId, {
      typeKey: key,
      name: `告警模型${suffix}`,
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
    const device = await api.fetchCreateDevice(projectId, {
      deviceTypeId: type.id,
      deviceKey: key,
      name: `告警设备${suffix}`
    })
    const credential = await api.fetchGenerateCredential(projectId, device.id)
    return {
      projectId,
      suffix,
      deviceId: device.id as string,
      deviceKey: device.deviceKey as string,
      deviceName: device.name as string,
      credentialId: credential.id as string,
      secret: credential.plainSecret as string
    }
  })
  const rules: { id: string; version: number; severity: string }[] = []
  let primaryFailure = false
  try {
    for (const [index, severity] of ['MAJOR', 'MAJOR', 'MINOR', 'WARNING'].entries()) {
      rules.push(
        await page.evaluate(
          async ({ projectId, deviceId, suffix, index, severity }) => {
            const path = '/src/api/alarm.ts',
              api = await import(path)
            const rule = await api.fetchCreateAlarmRule(projectId, {
              name: `真实看板告警${suffix}_${index}`,
              alarmType: `DASH_E2E_${suffix}_${index}`,
              deviceId,
              propertyKey: 'temperature',
              triggerOperator: 'GT',
              triggerThreshold: 30,
              triggerDurationSeconds: severity === 'WARNING' ? 600 : 0,
              clearOperator: 'LT',
              clearThreshold: 25,
              clearDurationSeconds: 0,
              severity,
              enabled: true
            })
            return { id: rule.id as string, version: rule.version as number, severity }
          },
          {
            projectId: fixture.projectId,
            deviceId: fixture.deviceId,
            suffix: fixture.suffix,
            index,
            severity
          }
        )
      )
    }
    const projectKey = process.env.E2E_PROJECT_KEY
    if (!projectKey) throw new Error('缺少真实上报项目标识')
    await publishCurrentValues({
      projectKey,
      deviceKey: fixture.deviceKey,
      secret: fixture.secret,
      port: Number(process.env.EMQX_MQTT_PORT ?? 1883),
      payload: currentValueMessage(
        '{"temperature":31}',
        await readBoundModelVersion(fixture.projectId, fixture.deviceId)
      )
    })
    fixture.secret = ''
    // PUBACK只证明Broker接收；读取真实事故，最多20次/20秒等待消费与告警评估。
    let incidents: {
      id: string
      ruleId: string
      conditionState: string
      ackState: string
      version: number
    }[] = []
    for (let attempt = 0; attempt < 20; attempt++) {
      incidents = await page.evaluate(
        async ({ projectId, ruleIds }) => {
          const path = '/src/api/alarm.ts',
            api = await import(path)
          const result = await api.fetchAlarmInstances(projectId, undefined, 50)
          return result.items
            .filter((item: { ruleId: string }) => ruleIds.includes(item.ruleId))
            .map(
              (item: {
                id: string
                ruleId: string
                conditionState: string
                ackState: string
                version: number
              }) => ({
                id: item.id,
                ruleId: item.ruleId,
                conditionState: item.conditionState,
                ackState: item.ackState,
                version: item.version
              })
            )
        },
        { projectId: fixture.projectId, ruleIds: rules.map((rule) => rule.id) }
      )
      if (incidents.length === 4) break
      await page.waitForTimeout(1000)
    }
    expect(incidents).toHaveLength(4)
    expect(incidents.filter((item) => item.conditionState === 'ACTIVE')).toHaveLength(3)
    expect(incidents.filter((item) => item.conditionState === 'PENDING')).toHaveLength(1)
    const minor = incidents.find((item) => item.ruleId === rules[2]!.id)!
    const cleared = await page.evaluate(
      async ({ projectId, instanceId, version }) => {
        const path = '/src/api/alarm.ts',
          api = await import(path)
        const acknowledged = await api.fetchAcknowledgeAlarm(projectId, instanceId, { version })
        const result = await api.fetchClearAlarm(projectId, instanceId, {
          version: acknowledged.version
        })
        return { conditionState: result.conditionState, ackState: result.ackState }
      },
      { projectId: fixture.projectId, instanceId: minor.id, version: minor.version }
    )
    expect(cleared).toEqual({ conditionState: 'CLEARED', ackState: 'ACKNOWLEDGED' })

    await page.goto('/#/dashboard/designer')
    await page.getByTestId('dashboard-create').click()
    await page.getByTestId('dashboard-name').fill(`告警过滤统一验收${fixture.suffix}`)
    await page.getByTestId('dashboard-create-confirm').click()
    await expect(page).toHaveURL(/dashboardId=/)
    await page.getByRole('button', { name: '读取设备目录', exact: true }).click()
    const metadata = page.waitForResponse((response) =>
      new URL(response.url()).pathname.endsWith(`/${fixture.deviceId}/binding-metadata`)
    )
    await page.getByLabel('绑定设备', { exact: true }).selectOption(fixture.deviceId)
    expect((await metadata).status()).toBe(200)
    await page.getByTestId('variable-new').click()
    await page.getByLabel('变量标题', { exact: true }).fill('告警设备')
    await page.getByLabel('变量类型', { exact: true }).selectOption('DEVICE_SINGLE')
    await page.getByLabel('变量模型', { exact: true }).selectOption('__binding')
    await page.getByRole('button', { name: '读取变量设备目录', exact: true }).click()
    await page.getByLabel(`默认设备 ${fixture.deviceName}`, { exact: true }).check()
    await page.getByTestId('variable-save').click()
    await saved(page)
    const variableKey = (await page
      .getByRole('button', { name: '编辑变量 告警设备', exact: true })
      .getAttribute('data-testid'))!.replace('variable-edit-', '')
    for (const config of [
      { title: '活跃告警甲', condition: 'ACTIVE', ack: 'UNACKNOWLEDGED', severity: 'MAJOR' },
      { title: '活跃告警乙', condition: 'ACTIVE', ack: 'UNACKNOWLEDGED', severity: 'MAJOR' },
      { title: '待激活告警', condition: 'PENDING', ack: 'UNACKNOWLEDGED', severity: 'WARNING' },
      { title: '已恢复已确认告警', condition: 'CLEARED', ack: 'ACKNOWLEDGED', severity: 'MINOR' }
    ]) {
      await page.getByLabel('告警标题', { exact: true }).fill(config.title)
      await page.getByLabel('告警设备变量', { exact: true }).selectOption(variableKey)
      await page.getByLabel('告警每页数量', { exact: true }).fill('1')
      for (const value of ['ACTIVE', 'PENDING', 'CLEARED'])
        await page
          .getByLabel(`条件 ${value}`, { exact: true })
          .setChecked(value === config.condition)
      for (const value of ['UNACKNOWLEDGED', 'ACKNOWLEDGED'])
        await page.getByLabel(`确认 ${value}`, { exact: true }).setChecked(value === config.ack)
      for (const value of ['CRITICAL', 'MAJOR', 'MINOR', 'WARNING', 'INFO'])
        await page
          .getByLabel(`等级 ${value}`, { exact: true })
          .setChecked(value === config.severity)
      await page.getByTestId('alarm-component-add').click()
      await saved(page)
    }
    await expect(page.locator('[data-kind="ALARM_LIST"]')).toHaveCount(4)
    await page.reload()
    await expect(page.locator('[data-kind="ALARM_LIST"]')).toHaveCount(4)
    // 自动首读必须完成后再观察显式刷新；两轮请求不能混作一轮去重失败。
    await expect(page.getByTestId('preview-rest-state')).toHaveAttribute('data-state', 'REST_READY')
    const requests: { cursor?: string; conditionStates: string[] }[] = []
    page.on('request', (request) => {
      if (new URL(request.url()).pathname.endsWith('/alarms/query'))
        requests.push(request.postDataJSON())
    })
    await page.getByRole('button', { name: '刷新草稿数据', exact: true }).click()
    await expect.poll(() => requests.length).toBe(3)
    await expect(page.getByTestId('preview-rest-state')).toHaveAttribute('data-state', 'REST_READY')
    const preview = page.getByRole('region', { name: '草稿设备数据预览' })
    const lists = preview.getByRole('region', { name: '告警事实快照' })
    await expect(lists).toHaveCount(4)
    for (let index = 0; index < 4; index++)
      await expect(lists.nth(index).locator('tbody tr')).toHaveCount(1)
    expect(requests).toHaveLength(3)
    await expect(lists.nth(0)).toContainText('ACTIVE')
    await expect(lists.nth(0)).toContainText('UNACKNOWLEDGED')
    await expect(lists.nth(0)).toContainText('MAJOR')
    await expect(lists.nth(2)).toContainText('PENDING')
    await expect(lists.nth(2)).toContainText('WARNING')
    await expect(lists.nth(3)).toContainText('CLEARED')
    await expect(lists.nth(3)).toContainText('ACKNOWLEDGED')
    await expect(lists.nth(3)).toContainText('MINOR')
    const firstId = await lists.nth(0).locator('tbody tr').getAttribute('data-alarm-id')
    expect(firstId).toBeTruthy()
    const firstRow = await lists.nth(0).locator('tbody').innerText()
    expect(await lists.nth(1).locator('tbody').innerText()).toBe(firstRow)
    await page.waitForTimeout(1100)
    await lists.nth(0).getByRole('button', { name: '下一页告警', exact: true }).click()
    await expect.poll(() => requests.length).toBe(4)
    await expect(lists.nth(0).locator('tbody tr')).toHaveCount(1)
    await expect(lists.nth(0).locator('tbody tr')).not.toHaveAttribute('data-alarm-id', firstId!)
    await expect
      .poll(async () => lists.nth(1).locator('tbody').innerText())
      .toBe(await lists.nth(0).locator('tbody').innerText())
    expect(requests[3]!.cursor).toBeTruthy()
    await page.waitForTimeout(1100)
    await lists.nth(1).getByRole('button', { name: '返回告警首页', exact: true }).click()
    await expect.poll(() => requests.length).toBe(5)
    await expect(lists.nth(0).locator('tbody tr')).toHaveAttribute('data-alarm-id', firstId!)
    await expect(lists.nth(0).locator('tbody')).toHaveText(firstRow, { useInnerText: true })
    expect(requests[4]!.cursor).toBeUndefined()
    await expect(
      preview.getByRole('button', { name: /^(ACK|CLEAR|确认告警|清除告警|标为已读)$/ })
    ).toHaveCount(0)
    await page.setViewportSize({ width: 375, height: 812 })
    for (let index = 0; index < 4; index++)
      await expect(lists.nth(index).locator('tbody tr')).toHaveCount(1)
    const dimensions = await preview.evaluate((element) => ({
      scroll: element.scrollWidth,
      client: element.clientWidth
    }))
    expect(dimensions.scroll).toBeLessThanOrEqual(dimensions.client + 1)
    await page.screenshot({ path: 'test-results/dashboard-alarms.png', fullPage: true })
  } catch (cause) {
    primaryFailure = true
    throw cause
  } finally {
    fixture.secret = ''
    const cleanups = await page.evaluate(
      async ({ projectId, deviceId, credentialId, rules }) => {
        const devicePath = '/src/api/device.ts',
          alarmPath = '/src/api/alarm.ts'
        const device = await import(devicePath),
          alarm = await import(alarmPath)
        const result = await Promise.allSettled([
          device.fetchRevokeCredential(projectId, deviceId, credentialId),
          ...rules.map((rule) => alarm.fetchDeleteAlarmRule(projectId, rule.id, rule.version))
        ])
        return result.map((item) => item.status)
      },
      {
        projectId: fixture.projectId,
        deviceId: fixture.deviceId,
        credentialId: fixture.credentialId,
        rules: rules.map(({ id, version }) => ({ id, version }))
      }
    )
    if (!cleanups.every((status) => status === 'fulfilled')) {
      if (primaryFailure) {
        test.info().annotations.push({
          type: 'cleanup',
          description: '自有凭据或规则清理失败；保留原始旅程失败。'
        })
      } else {
        expect(
          cleanups.every((status) => status === 'fulfilled'),
          '自有凭据和规则清理'
        ).toBe(true)
      }
    }
  }
})
async function saved(page: Page) {
  await expect(page.getByTestId('designer-save-state')).toHaveAttribute('data-status', 'saved')
}
